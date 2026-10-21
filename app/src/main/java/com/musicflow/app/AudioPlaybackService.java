package com.musicflow.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Playback survives the Activity; public videos are resolved on demand, with a bounded memory cache and one prepared next song. */
public final class AudioPlaybackService extends Service {
    static final String PLAY = "com.musicflow.app.AUDIO_PLAY";
    static final String PAUSE = "com.musicflow.app.AUDIO_PAUSE";
    static final String RESUME = "com.musicflow.app.AUDIO_RESUME";
    static final String TOGGLE = "com.musicflow.app.AUDIO_TOGGLE";
    static final String NEXT = "com.musicflow.app.AUDIO_NEXT";
    static final String PREVIOUS = "com.musicflow.app.AUDIO_PREVIOUS";
    static final String SHUFFLE = "com.musicflow.app.AUDIO_SHUFFLE";
    static final String SEEK = "com.musicflow.app.AUDIO_SEEK";
    static final String STOP = "com.musicflow.app.AUDIO_STOP";
    private static final String CHANNEL = "musicflow.playback";
    private static final int NOTIFICATION_ID = 44;
    private static AudioPlaybackService instance;
    private static String lastError = "";
    private final java.util.concurrent.ExecutorService resolver = java.util.concurrent.Executors.newFixedThreadPool(2);
    private java.util.concurrent.Future<?> resolveJob;
    private int generation;
    private MusicPlayer player, warmPlayer, previousPlayer;
    private JSONObject previousTrack;
    private long previousAt;
    private final Runnable previousTimeout = () -> clearPrevious();
    private void clearPrevious() {
        handler.removeCallbacks(previousTimeout);
        if (previousPlayer != null) { previousPlayer.release(); previousPlayer = null; }
        previousTrack = null;
    }
    private String warmId;
    private MusicCatalog.Resolved warmInfo;
    private boolean warmReady, retried;
    private long warmAt, loadStarted, startupMs;
    private long requestStarted, resolutionMs = -1, prepareStarted, preparationMs = -1;
    private String loadSource = "online";
    private final Runnable totalLoadTimeout = () -> {
        if (this.loading) fail("La canción no respondió en 35 segundos. Puedes reintentar o elegir otra; tus descargas funcionan sin conexión.");
    };
    private String phase = "";
    private int warmGeneration;
    private java.util.concurrent.Future<?> warmJob;
    private final Runnable deferredWarm = () -> warmNext();
    private final Runnable warmTimeout = () -> clearWarm();
    private MusicPlayer newPlayer() {
        MusicPlayer mp = new MusicPlayer(this);
        return mp;
    }
    private void clearWarm() {
        warmGeneration++; handler.removeCallbacks(warmTimeout); handler.removeCallbacks(deferredWarm);
        if (warmJob != null) { warmJob.cancel(true); warmJob = null; }
        if (warmPlayer != null) { warmPlayer.setOnPreparedListener(null); warmPlayer.setOnErrorListener(null); warmPlayer.release(); warmPlayer = null; }
        warmId = null; warmInfo = null; warmReady = false;
    }
    private void warmNext() {
        if (!playing() || index + 1 >= queue.size() || warmId != null) return;
        if (player.isBuffering() || player.bufferedDuration() < 5000) {
            handler.removeCallbacks(deferredWarm); handler.postDelayed(deferredWarm, 1000); return;
        }
        final String id = queue.get(index + 1);
        if (!MusicCatalog.videoId(id)) return;
        JSONObject local = AudioLibrary.find(this, id);
        if (local != null && !local.optString("uri").isEmpty()) return;
        warmId = id;
        final int request = ++warmGeneration;
        handler.postDelayed(warmTimeout, 45000);
        warmJob = resolver.submit(() -> {
            try {
                MusicCatalog.Resolved info = MusicCatalog.resolve(id);
                handler.post(() -> {
                    if (request != warmGeneration || instance != this || !playing()) return;
                    try {
                        warmInfo = info; warmPlayer = newPlayer();
                        warmPlayer.setOnPreparedListener(mp -> {
                            if (mp != warmPlayer) return;
                            warmReady = true; warmAt = android.os.SystemClock.elapsedRealtime();
                            handler.removeCallbacks(warmTimeout);
                            // Do not retain a prepared connection indefinitely on long songs.
                            handler.postDelayed(warmTimeout, 10 * 60_000L);
                        });
                        warmPlayer.setOnErrorListener((mp, what, extra) -> { clearWarm(); return true; });
                        warmPlayer.setDataSource(this, Uri.parse(info.url)); warmPlayer.prepareAsync();
                    } catch (Exception e) { clearWarm(); }
                });
            } catch (Exception | LinkageError e) { handler.post(() -> { if (request == warmGeneration) clearWarm(); }); }
        });
    }
    private void attachPlaybackListeners() {
        player.setOnPreparedListener(null);
        player.setOnErrorListener((mp, what, extra) -> { playbackError(); return true; });
        player.setOnCompletionListener(mp -> completeTrack());
    }
    private void playbackError() {
        if (!retried && current != null && MusicCatalog.videoId(current.optString("id"))) {
            MusicCatalog.invalidate(current.optString("id"));
            load(index, true);
        } else fail("No se pudo reproducir esta canción. Revisa la conexión y pulsa reproducir para reintentar.");
    }
    private void holdWifi() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && wifiLock == null) wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicFlow:audio");
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
    }
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> queue = new ArrayList<>();
    private final ArrayList<String> originalQueue = new ArrayList<>();

    private final java.util.concurrent.ExecutorService discovery = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> discoveryJob;
    private boolean automatic, discovering, awaitingMore, endReached, discoveryExhausted;
    private String autoQuery = "", autoToken = "", autoMessage = "";
    private int discoveryGeneration;
    private final ArrayList<JSONObject> autoTracks = new ArrayList<>();
    private final Runnable discoveryTimeout = () -> {
        discoveryGeneration++; if (discoveryJob != null) discoveryJob.cancel(true);
        discovering = false; awaitingMore = false; discoveryExhausted = true;
        autoMessage = "La continuación tardó demasiado. Tus canciones actuales siguen disponibles."; notifyState();
    };
    private void resetDiscovery() {
        discoveryGeneration++; if(discoveryJob != null) discoveryJob.cancel(true);
        handler.removeCallbacks(discoveryTimeout); discovering=false; awaitingMore=false;
        discoveryExhausted=false; autoToken=""; autoMessage=""; autoTracks.clear();
    }
    private void completeTrack() {
        endReached = true;
        if (index + 1 < queue.size()) { load(index + 1); return; }
        if (automatic && !discoveryExhausted) { awaitingMore=true; maybeDiscover(true); notifyState(); }
        else { pause(false); notifyState(); }
    }
    private void maybeDiscover(boolean force) {
        if (closing) return;
        if(!automatic || discovering || discoveryExhausted || current == null) return;
        if(queue.size()>=5000) { discoveryExhausted=true; awaitingMore=false; autoMessage="La cola llegó a 5000 canciones. Inicia otra búsqueda para continuar."; return; }
        if(!force && (!playing() || queue.size()-index-1 > 3 || player.bufferedDuration()<5000)) return;
        String artist=current.optString("channel").replace(" - Topic", "").trim();
        if(artist.isEmpty() || artist.equals("YouTube")) artist=current.optString("title");
        if(autoQuery.isEmpty()) autoQuery=artist;
        if(autoQuery.isEmpty()) { discoveryExhausted=true; awaitingMore=false; autoMessage="Busca un artista para continuar con más canciones."; return; }
        final String query=autoQuery, token=autoToken, related=artist;
        final java.util.HashSet<String> excluded=new java.util.HashSet<>(queue); excluded.addAll(heard);
        final int request=discoveryGeneration;
        discovering=true; autoMessage="Buscando hasta 20 canciones más…";
        handler.postDelayed(discoveryTimeout,45000);
        discoveryJob=discovery.submit(() -> {
            final ArrayList<JSONObject> added=new ArrayList<>();
            String q=query, cursor=token; boolean finished=false; String error="";
            try {
                for(int page=0;page<4 && added.size()<20;page++) {
                    if(Thread.currentThread().isInterrupted()) return;
                    JSONObject response;
                    try { response=MusicCatalog.searchPage(q,"songs",cursor); }
                    catch(Exception stale) { if(cursor.isEmpty()) throw stale; cursor=""; response=MusicCatalog.searchPage(q,"songs",""); }
                    JSONArray items=response.optJSONArray("items");
                    if(items!=null) for(int i=0;i<items.length() && added.size()<20;i++) {
                        JSONObject item=items.optJSONObject(i); if(item==null) continue;
                        String id=item.optString("id");
                        if(MusicCatalog.videoId(id) && excluded.add(id)) added.add(item);
                    }
                    cursor=response.optString("next");
                    if(cursor.isEmpty()) {
                        if(!related.isEmpty() && !q.equalsIgnoreCase(related)) { q=related; }
                        else { finished=true; break; }
                    }
                }
            } catch(Exception | LinkageError failure) { error="No se pudo ampliar la cola. Puedes volver a buscar más canciones."; }
            final String nextQuery=q, nextToken=cursor, failureText=error;
            final boolean exhausted=finished || added.isEmpty();
            handler.post(() -> {
                if(request!=discoveryGeneration || instance!=this) return;
                handler.removeCallbacks(discoveryTimeout); discovering=false;
                autoQuery=nextQuery; autoToken=nextToken; discoveryExhausted=exhausted;
                ArrayList<String> appended=new ArrayList<>();
                for(JSONObject item:added) {
                    String id=item.optString("id");
                    if(queue.size()+appended.size()>=5000) break;
                    if(!queue.contains(id) && !heard.contains(id)) { appended.add(id); autoTracks.add(item); originalQueue.add(id); }
                }
                if(shuffled) java.util.Collections.shuffle(appended);
                queue.addAll(appended);
                autoMessage=appended.isEmpty()?(failureText.isEmpty()?"No quedan canciones nuevas para esta continuación.":failureText):appended.size()+" canciones añadidas automáticamente";
                boolean continueNow=awaitingMore; awaitingMore=false;
                if(continueNow && index+1<queue.size()) load(index+1);
                else { warmNext(); notifyState(); }
            });
        });
    }

    private final java.util.LinkedHashSet<String> heard = new java.util.LinkedHashSet<>();
    private long resumePosition;
    private String resumeId = "";
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() { saveSession(); maybeDiscover(false); handler.postDelayed(this, 2000); }
    };
    private boolean closing;
    private void saveSession() { saveSession(false); }
    private void saveSession(boolean flush) {
        if (closing) return;
        if (current == null) return;
        try {
            JSONObject saved = state();
            saved.put("queue", new JSONArray(queue)).put("originalQueue", new JSONArray(originalQueue));
            saved.put("position", prepared ? position() : resumePosition);
            saved.put("heard", new JSONArray(heard));
            saved.put("automatic", automatic).put("autoQuery",autoQuery).put("autoTracks",new JSONArray(autoTracks)).put("endReached",endReached);
            android.content.SharedPreferences.Editor editor = getSharedPreferences("musicflow.player", 0).edit().putString("session", saved.toString());
            if (flush) editor.commit(); else editor.apply();
        } catch (Exception ignored) { }
    }
    private void restoreSession() {
        try {
            JSONObject saved = new JSONObject(getSharedPreferences("musicflow.player", 0).getString("session", "{}"));
            automatic=saved.optBoolean("automatic"); autoQuery=saved.optString("autoQuery"); endReached=saved.optBoolean("endReached");
            JSONArray storedAuto=saved.optJSONArray("autoTracks");
            if(storedAuto!=null) for(int i=0;i<storedAuto.length();i++) if(storedAuto.optJSONObject(i)!=null) autoTracks.add(storedAuto.optJSONObject(i));
            JSONArray history = saved.optJSONArray("heard");
            if (history != null) for (int i=0; i<history.length(); i++) heard.add(history.optString(i));
            JSONObject track = saved.optJSONObject("track");
            if (track == null || AudioLibrary.find(this, track.optString("id")) == null) return;
            JSONArray ids = saved.optJSONArray("queue"), original = saved.optJSONArray("originalQueue");
            if (ids != null) for(int i=0;i<ids.length();i++) if(AudioLibrary.find(this,ids.optString(i))!=null) queue.add(ids.optString(i));
            if (original != null) for(int i=0;i<original.length();i++) if(AudioLibrary.find(this,original.optString(i))!=null) originalQueue.add(original.optString(i));
            index = queue.indexOf(track.optString("id"));
            if(index < 0) { queue.clear(); originalQueue.clear(); return; }
            current = track; resumeId = track.optString("id"); resumePosition = Math.max(0,saved.optLong("position"));
        } catch(Exception ignored) { }
    }
    static void resetHistory(Context context) {
        if(instance != null) { instance.heard.clear(); if(instance.current != null && instance.playing()) instance.heard.add(instance.current.optString("id")); instance.saveSession(); return; }
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("musicflow.player",0);
            JSONObject saved = new JSONObject(prefs.getString("session","{}")); saved.put("heard",new JSONArray());
            prefs.edit().putString("session",saved.toString()).apply();
        }catch(Exception ignored) { }
    }
    static JSONObject state(Context context) {
        if(instance != null) return state();
        try {
            JSONObject saved = new JSONObject(context.getSharedPreferences("musicflow.player",0).getString("session","{}"));
            saved.put("active", saved.optJSONObject("track") != null && AudioLibrary.find(context, saved.optJSONObject("track").optString("id")) != null).put("restored",true)
                .put("playing",false).put("loading",false).put("buffering",false).put("awaitingMore",false).put("discovering",false).put("error","");
            return saved;
        } catch(Exception ignored) { return new JSONObject(); }
    }
    private boolean shuffled;
    private int index = -1;
    private void shuffleRemaining() {
        if (index < 0 || index >= queue.size()) return;
        ArrayList<String> remaining = new ArrayList<>(originalQueue);
        remaining.removeAll(queue.subList(0, index + 1));
        if (shuffled) { remaining.removeAll(heard); java.util.Collections.shuffle(remaining); }
        queue.subList(index + 1, queue.size()).clear(); queue.addAll(remaining);
        clearWarm(); warmNext(); notifyState();
    }
    private JSONObject current;
    private boolean prepared, loading, resumeAfterFocus;
    private final Runnable loadTimeout = () -> playbackError();
    private final Runnable pausedTimeout = () -> { if (!playing()) stopSelf(); };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(false); }
    };
    @Override public void onCreate() {
        super.onCreate(); instance = this; lastError = "";
        shuffled = getSharedPreferences("musicflow.player", 0).getBoolean("shuffle", false);
        restoreSession(); handler.postDelayed(checkpoint, 2000);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Reproducción musical", NotificationManager.IMPORTANCE_LOW));
        audioManager = getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) { if (resumeAfterFocus) { resumeAfterFocus = false; resume(); } }
            else if (change == AudioManager.AUDIOFOCUS_LOSS) pause(false);
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause(true);
        }).build();
        session = new MediaSession(this, "MusicFlow audio");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(false); }
            @Override public void onStop() { stopSelf(); }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
            @Override public void onSeekTo(long position) { seek(position); }
        });
        session.setActive(true);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, filter);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (closing) { stopSelf(); return START_NOT_STICKY; }
        // Android requires a foreground notification immediately after startForegroundService.
        startForeground(NOTIFICATION_ID, notification());
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (PLAY.equals(action)) {
            String id = intent.getStringExtra("id");
            if (current != null && current.optString("id").equals(id) && lastError.isEmpty()) {
                if(intent.hasExtra("automatic")) {
                    boolean nextAutomatic=intent.getBooleanExtra("automatic",false);
                    if(automatic && !nextAutomatic) resetDiscovery();
                    automatic=nextAutomatic;
                    if(autoQuery.isEmpty()) { String q=intent.getStringExtra("autoQuery"); if(q!=null)autoQuery=q; }
                }
                // Repeated taps must not discard a request already loading this song.
                if (!loading && !playing()) resume();
                notifyState(); return START_NOT_STICKY;
            }
            resetDiscovery(); automatic=intent.getBooleanExtra("automatic",false); autoQuery=intent.getStringExtra("autoQuery"); if(autoQuery==null)autoQuery="";
            queue.clear();
            try { JSONArray ids = new JSONArray(intent.getStringExtra("queue")); for (int i = 0; i < ids.length() && i < 5000; i++) { String candidate = ids.optString(i); if (AudioLibrary.find(this, candidate) != null && !queue.contains(candidate)) queue.add(candidate); } } catch (Exception ignored) { }
            if (AudioLibrary.find(this, id) == null) { fail("Este archivo no está disponible. Vuelve a añadirlo desde Mi música."); return START_NOT_STICKY; }
            if (!queue.contains(id)) queue.add(id);
            originalQueue.clear(); originalQueue.addAll(queue);
            if (shuffled) { queue.remove(id); queue.removeAll(heard); java.util.Collections.shuffle(queue); queue.add(0, id); }
            load(queue.indexOf(id));
        } else if (STOP.equals(action)) stopSelf();
        else if (PAUSE.equals(action)) pause(false);
        else if (RESUME.equals(action)) resume();
        else if (TOGGLE.equals(action)) { if (playing() || loading || awaitingMore) pause(false); else resume(); }
        else if (NEXT.equals(action)) step(1);
        else if (PREVIOUS.equals(action)) step(-1);
        else if (SHUFFLE.equals(action)) {
            boolean requested = intent.getLongExtra("position", 0) != 0;
            if (shuffled != requested) {
                shuffled = requested;
                getSharedPreferences("musicflow.player", 0).edit().putBoolean("shuffle", shuffled).apply();
                shuffleRemaining();
            }
        }
        else if (SEEK.equals(action)) seek(intent.getLongExtra("position", 0));
        if (current == null && !PLAY.equals(action)) stopSelf();
        return START_NOT_STICKY;
    }
    private void load(int nextIndex) { load(nextIndex, false); }
    private void load(int nextIndex, boolean retry) {
        if (closing) return;
        if (nextIndex < 0 || nextIndex >= queue.size()) return;
        endReached=false; awaitingMore=false;
        if (!retry) requestStarted = android.os.SystemClock.elapsedRealtime();
        if (!queue.get(nextIndex).equals(resumeId)) { resumePosition = 0; resumeId = ""; }
        MusicPlayer ready = null; MusicCatalog.Resolved info = null; boolean candidateReady = true;
        JSONObject restored = null;
        if (!retry && previousPlayer != null && previousTrack != null
                && queue.get(nextIndex).equals(previousTrack.optString("id"))
                && android.os.SystemClock.elapsedRealtime() - previousAt < 10 * 60_000L) {
            ready = previousPlayer; restored = previousTrack; previousPlayer = null;
        }
        if (ready == null && !retry && queue.get(nextIndex).equals(warmId) && warmPlayer != null && warmInfo != null
                && warmInfo.validUntil > System.currentTimeMillis() && (!warmReady || android.os.SystemClock.elapsedRealtime() - warmAt < 10 * 60_000L)) {
            ready = warmPlayer; info = warmInfo; candidateReady = warmReady; warmPlayer = null;
        }
        SearchPreview.Candidate preview = SearchPreview.take(queue.get(nextIndex));
        if (ready == null && !retry && preview != null) { ready = preview.player; info = preview.info; candidateReady = preview.ready; }
        else if (preview != null) preview.player.release();
        MusicPlayer retiring = null; JSONObject retiringTrack = null;
        if (!retry && prepared && player != null && current != null && !queue.get(nextIndex).equals(current.optString("id"))) {
            try {
                player.pause(); player.setOnPreparedListener(null); player.setOnErrorListener(null); player.setOnCompletionListener(null);
                retiring = player; retiringTrack = current; player = null;
            } catch (IllegalStateException ignored) { }
        }
        releasePlayer(); retried = retry;
        if (retiring != null) {
            previousPlayer = retiring; previousTrack = retiringTrack; previousAt = android.os.SystemClock.elapsedRealtime();
            previousPlayer.setOnErrorListener((mp, what, extra) -> { clearPrevious(); return true; });
            handler.postDelayed(previousTimeout, 10 * 60_000L);
        }
        loadStarted = android.os.SystemClock.elapsedRealtime(); startupMs = -1; phase = "Conectando con la canción…";
        resolutionMs = -1; preparationMs = -1; prepareStarted = 0; loadSource = "online";
        index = nextIndex; current = AudioLibrary.find(this, queue.get(index)); lastError = "";
        if (current == null) { if (ready != null) ready.release(); fail("El audio ya no está en la biblioteca."); return; }
        loading = true; notifyState();
        long remaining = 35000 - (android.os.SystemClock.elapsedRealtime() - requestStarted);
        if (remaining <= 0) { if (ready != null) ready.release(); totalLoadTimeout.run(); return; }
        handler.postDelayed(totalLoadTimeout, remaining);
        if (ready != null) {
            player = ready; prepared = candidateReady; loading = !candidateReady;
            loadSource = "preparada"; resolutionMs = 0; prepareStarted = android.os.SystemClock.elapsedRealtime();
            if (candidateReady) preparationMs = 0;
            holdWifi();
            if (restored != null) { current = restored; player.seekTo(0); }
            else try { current.put("title", info.title).put("channel", info.channel); } catch (Exception ignored) { }
            if (candidateReady) { attachPlaybackListeners(); resume(); }
            else {
                phase = "Preparando audio…";
                player.setOnPreparedListener(mp -> {
                    if (mp != player) return;
                    handler.removeCallbacks(loadTimeout); preparationMs = android.os.SystemClock.elapsedRealtime() - prepareStarted; prepared = true; loading = false; attachPlaybackListeners(); resume();
                });
                player.setOnErrorListener((mp, what, extra) -> { if (mp == player) playbackError(); return true; });
                handler.postDelayed(loadTimeout, 12000); notifyState();
            }
            return;
        }
        if (MusicCatalog.videoId(current.optString("id")) && current.optString("uri").isEmpty()) {
            final int request = generation;
            final String id = current.optString("id");
            handler.postDelayed(loadTimeout, 60000);
            resolveJob = resolver.submit(() -> {
                try {
                    MusicCatalog.Resolved result = MusicCatalog.resolveForPlayback(id);
                    handler.post(() -> {
                        if (request != generation || instance != this) return;
                        resolutionMs = android.os.SystemClock.elapsedRealtime() - loadStarted;
                        try { current.put("title", result.title).put("channel", result.channel); } catch (Exception ignored) { }
                        prepare(Uri.parse(result.url));
                    });
                } catch (Exception | LinkageError error) { handler.post(() -> { if (request == generation && instance == this) fail(MusicCatalog.error(error)); }); }
            });
            return;
        }
        resolutionMs = 0; loadSource = current.optString("uri").startsWith("content:") ? "archivo" : "directa";
        Uri uri = Uri.parse(current.optString("uri"));
        boolean bundled = ("android.resource://" + getPackageName() + "/raw/musicflow_demo").equals(uri.toString());
        if (!(bundled || "content".equals(uri.getScheme()) || AudioLibrary.allowedStream(uri.toString()))) { fail("Fuente de audio no válida."); return; }
        prepare(uri);
    }
    private void prepare(Uri uri) {
        handler.removeCallbacks(loadTimeout);
        phase = "Recibiendo audio…"; prepareStarted = android.os.SystemClock.elapsedRealtime();
        try {
            player = newPlayer();
            if ("https".equals(uri.getScheme())) holdWifi();
            player.setDataSource(this, uri);
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                handler.removeCallbacks(loadTimeout); preparationMs = android.os.SystemClock.elapsedRealtime() - prepareStarted; loading = false; prepared = true; attachPlaybackListeners(); resume();
            });
            player.setOnErrorListener((mp, what, extra) -> { playbackError(); return true; });
            player.setOnCompletionListener(mp -> completeTrack());
            player.prepareAsync();
            handler.postDelayed(loadTimeout, 12000);
        } catch (Exception error) { playbackError(); }
    }
    private boolean playing() { try { return prepared && player != null && player.isPlaying(); } catch (IllegalStateException error) { return false; } }
    private void resume() {
        if (closing) return;
        if (current == null) return;
        if(endReached) { completeTrack(); return; }
        if (!prepared || player == null) { if (!loading) load(index); return; }
        handler.removeCallbacks(pausedTimeout);
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { lastError = "Otra aplicación está usando el audio. Pulsa reproducir para reintentar."; notifyState(); return; }
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            if (resumePosition > 0 && current.optString("id").equals(resumeId)) {
                int d = player.getDuration();
                if(d > 0 && resumePosition < d) player.seekTo((int)Math.min(Integer.MAX_VALUE,resumePosition));
                resumePosition = 0; resumeId = "";
            }
            player.start(); heard.add(current.optString("id")); handler.removeCallbacks(totalLoadTimeout); if (startupMs < 0) startupMs = android.os.SystemClock.elapsedRealtime() - requestStarted; phase = ""; lastError = ""; notifyState(); warmNext();
        } catch (Exception error) { fail("No se pudo reanudar el audio."); }
    }
    private void pause(boolean transientLoss) {
        awaitingMore=false;
        clearWarm();
        clearPrevious();
        resumeAfterFocus = transientLoss && playing();
        try { if (playing()) player.pause(); } catch (IllegalStateException ignored) { }
        if (loading) { releasePlayer(); }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (!transientLoss) audioManager.abandonAudioFocusRequest(focus);
        notifyState(); handler.removeCallbacks(pausedTimeout); handler.postDelayed(pausedTimeout, 600000);
    }
    private void seek(long position) {
        if (!prepared || player == null) return;
        try { int duration = player.getDuration(); if (duration > 0) player.seekTo((int) Math.max(0, Math.min(duration, position))); notifyState(); } catch (IllegalStateException ignored) { }
    }
    private void step(int direction) { if (index + direction >= 0 && index + direction < queue.size()) load(index + direction); }
    private void fail(String message) {
        if (loading && requestStarted > 0) {
            startupMs = android.os.SystemClock.elapsedRealtime() - requestStarted;
            if (resolutionMs < 0) resolutionMs = android.os.SystemClock.elapsedRealtime() - loadStarted;
            else if (preparationMs < 0 && prepareStarted > 0) preparationMs = android.os.SystemClock.elapsedRealtime() - prepareStarted;
        }
        lastError = message; releasePlayer(); audioManager.abandonAudioFocusRequest(focus); notifyState(); handler.removeCallbacks(pausedTimeout); handler.postDelayed(pausedTimeout, 60000);
    }
    private void releasePlayer() {
        clearWarm();
        clearPrevious();
        generation++; if (resolveJob != null) { resolveJob.cancel(true); resolveJob = null; }
        handler.removeCallbacks(loadTimeout); handler.removeCallbacks(totalLoadTimeout); handler.removeCallbacks(pausedTimeout);
        if (player != null) { player.setOnPreparedListener(null); player.setOnErrorListener(null); player.setOnCompletionListener(null); player.release(); player = null; }
        prepared = false; loading = false;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
    }
    private long position() { try { return prepared && player != null ? player.getCurrentPosition() : 0; } catch (IllegalStateException error) { return 0; } }
    private long duration() { try { return prepared && player != null ? Math.max(0, player.getDuration()) : 0; } catch (IllegalStateException error) { return 0; } }
    static JSONObject state() {
        JSONObject out = new JSONObject(); AudioPlaybackService s = instance;
        try {
            out.put("automatic",s!=null && s.automatic); out.put("autoQuery",s==null?"":s.autoQuery);
            out.put("autoMessage", s == null ? "" : s.autoMessage); out.put("discovering",s!=null && s.discovering); out.put("awaitingMore",s!=null && s.awaitingMore);
            out.put("autoTracks",s==null?new JSONArray():new JSONArray(s.autoTracks)); out.put("queueIds",s==null?new JSONArray():new JSONArray(s.queue));
            out.put("heard", new JSONArray(s == null ? java.util.Collections.emptySet() : s.heard));
            out.put("error", lastError); out.put("shuffle", s != null && s.shuffled);
            if (s == null || s.current == null) { out.put("active", false); return out; }
            out.put("active", true); out.put("track", new JSONObject().put("id", s.current.optString("id")).put("kind", s.current.optString("kind")).put("title", s.current.optString("title")).put("channel", s.current.optString("channel")));
            out.put("elapsedMs", s.loading ? android.os.SystemClock.elapsedRealtime() - s.requestStarted : Math.max(0, s.startupMs));
            out.put("resolutionMs", s.resolutionMs); out.put("preparationMs", s.preparationMs); out.put("loadSource", s.loadSource); out.put("retry", s.retried);
            out.put("phase", s.phase); out.put("startupMs", s.startupMs); out.put("nextReady", s.warmReady);
            out.put("offline", "content".equals(Uri.parse(s.current.optString("uri")).getScheme()));
            out.put("buffering", s.player != null && s.player.isBuffering()); out.put("bufferedMs", s.player == null ? 0 : s.player.bufferedDuration());
            out.put("playing", s.playing()); out.put("loading", s.loading); out.put("position", s.prepared ? s.position() : s.resumePosition); out.put("duration", s.duration()); out.put("index", s.index); out.put("size", s.queue.size());
        } catch (Exception ignored) { }
        return out;
    }
    private void notifyState() {
        if (closing) return;
        saveSession();
        boolean isPlaying = playing();
        int playback = loading ? PlaybackState.STATE_BUFFERING : isPlaying ? PlaybackState.STATE_PLAYING : lastError.isEmpty() ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_ERROR;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
        if (index > 0) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (index + 1 < queue.size()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(playback, position(), isPlaying ? 1f : 0f).build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, current == null ? "MusicFlow" : current.optString("title")).putString(MediaMetadata.METADATA_KEY_ARTIST, current == null ? "" : current.optString("channel")).putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }
    private PendingIntent command(String action, int code) { return PendingIntent.getService(this, code, new Intent(this, AudioPlaybackService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean isPlaying = playing();
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(current == null ? "MusicFlow" : current.optString("title"))
            .setContentText(!lastError.isEmpty() ? lastError : loading ? "Cargando audio…" : isPlaying ? "Reproduciendo · también con pantalla bloqueada" : "En pausa")
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(isPlaying || loading)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", command(PREVIOUS, 1)).build())
            .addAction(new Notification.Action.Builder(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, isPlaying ? "Pausar" : "Reproducir", command(TOGGLE, 2)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Siguiente", command(NEXT, 3)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Detener", command(STOP, 4)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
    }
    /** Removing the task is an explicit close; Home and screen lock still allow playback. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        finishPlayback();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    private void finishPlayback() {
        if (closing) return;
        // Capture position before releasing the engine; flush before Android may end the process.
        saveSession(true);
        closing = true;
        resumeAfterFocus = false;
        resetDiscovery(); discovery.shutdownNow(); resolver.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        releasePlayer(); SearchPreview.clear();
        audioManager.abandonAudioFocusRequest(focus);
        unregisterReceiver(noisy);
        session.setActive(false); session.release();
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }
    @Override public void onDestroy() {
        finishPlayback();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
