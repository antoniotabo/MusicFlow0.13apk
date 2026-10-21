package com.musicflow.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/** A single silent prepared candidate, owned on the main looper. Never requests audio focus. */
public final class SearchPreview {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static java.util.concurrent.Future<?> job;
    private static int generation;
    private static Candidate candidate;
    private static final Runnable timeout = () -> clear();
    public static final class Candidate {
        MusicPlayer player; MusicCatalog.Resolved info; String id; boolean ready;
    }
    public static void prepare(Context context, String id) {
        clear();
        if (!MusicCatalog.videoId(id)) return;
        org.json.JSONObject local = AudioLibrary.find(context, id);
        if (local != null && !local.optString("uri").isEmpty()) return;
        Context app = context.getApplicationContext(); final int request = generation;
        main.postDelayed(timeout, 45000);
        job = worker.submit(() -> {
            try {
                MusicCatalog.Resolved info = MusicCatalog.resolve(id);
                main.post(() -> {
                    if (request != generation) return;
                    try {
                        Candidate c = new Candidate(); c.id = id; c.info = info; c.player = new MusicPlayer(app); candidate = c;
                        c.player.setOnPreparedListener(mp -> {
                            if (candidate != c) return; c.ready = true; main.removeCallbacks(timeout); main.postDelayed(timeout, 120000);
                        });
                        c.player.setOnErrorListener((mp, what, extra) -> { if (candidate == c) clear(); return true; });
                        c.player.setDataSource(app, Uri.parse(info.url)); c.player.prepareAsync();
                    } catch (Exception e) { clear(); }
                });
            } catch (Exception | LinkageError ignored) { main.post(() -> { if (request == generation) clear(); }); }
        });
    }
    public static boolean ready() { return candidate != null && candidate.ready; }
    static Candidate take(String id) {
        Candidate found = candidate;
        if (found != null && found.id.equals(id) && found.info.validUntil > System.currentTimeMillis()) {
            candidate = null; found.player.setOnPreparedListener(null); found.player.setOnErrorListener(null); clear(); return found;
        }
        clear(); return null;
    }
    public static void clear() {
        generation++; main.removeCallbacks(timeout);
        if (job != null) { job.cancel(true); job = null; }
        if (candidate != null) {
            candidate.player.setOnPreparedListener(null); candidate.player.setOnErrorListener(null); candidate.player.release(); candidate = null;
        }
    }
}
