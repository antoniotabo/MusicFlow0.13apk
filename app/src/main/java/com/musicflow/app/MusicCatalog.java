package com.musicflow.app;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** GPL-3.0-or-later. Public catalog access through NewPipeExtractor, without accounts. */
public final class MusicCatalog {
    // Expiring URLs stay in memory only, bounded to 24 recently played songs.
    private static final Map<String, Resolved> cache = new java.util.LinkedHashMap<String, Resolved>(24, .75f, true);
    public static synchronized void invalidate(String id) { cache.remove(id); }
    private static boolean initialized;
    private static synchronized void init() {
        if (initialized) return;
        NewPipe.init(new Downloader() {
            @Override public Response execute(Request request) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelado");
                HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                c.setRequestMethod(request.httpMethod());
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36");
                c.setRequestProperty("Accept-Encoding", "gzip");
                for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                    if ("Accept-Encoding".equalsIgnoreCase(h.getKey())) continue;
                    for (String value : h.getValue()) c.addRequestProperty(h.getKey(), value);
                }
                ResolveJob owner = activeJob.get();
                if (owner != null) owner.connections.add(c);
                try {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelado");
                    if (request.dataToSend() != null) {
                        c.setDoOutput(true);
                        try (java.io.OutputStream out = c.getOutputStream()) { out.write(request.dataToSend()); }
                    }
                    int status = c.getResponseCode();
                    InputStream input = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (input != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) input = new java.util.zip.GZIPInputStream(input);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (input != null) try (InputStream stream = input) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = stream.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelado");
                            if (bytes.size() + n > 12000000) throw new IOException("Respuesta demasiado grande");
                            bytes.write(buffer, 0, n);
                        }
                    }
                    return new Response(status, c.getResponseMessage(), c.getHeaderFields(), bytes.toString(StandardCharsets.UTF_8.name()), c.getURL().toString());
                } finally { if (owner != null) owner.connections.remove(c); c.disconnect(); }
            }
        });
        initialized = true;
    }
    public static boolean videoId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{11}"); }
    private static final Map<String, SearchSession> pages = new java.util.LinkedHashMap<>();
    private static final Map<String, SearchCache> searches = new java.util.LinkedHashMap<>();
    private static class SearchSession {
        String query, mode; org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler handler;
        org.schabi.newpipe.extractor.Page page; long created = System.currentTimeMillis();
    }
    private static class SearchCache {
        String json; long created = System.currentTimeMillis();
        SearchCache(String value) { json = value; }
    }
    public static JSONArray search(String query) throws Exception { return searchPage(query, "songs", "").getJSONArray("items"); }
    public static JSONObject searchPage(String query, String mode, String token) throws Exception {
        init();
        if (query == null || query.trim().isEmpty() || query.length() > 500) throw new IllegalArgumentException("Búsqueda inválida");
        mode = "songs";
        String key = mode + ":" + query.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<InfoItem> found;
        org.schabi.newpipe.extractor.Page next;
        org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler link;
        if (token == null || token.isEmpty()) {
            synchronized (MusicCatalog.class) {
                SearchCache hit = searches.get(key);
                if (hit != null && System.currentTimeMillis() - hit.created < 600000) {
                    JSONObject saved = new JSONObject(hit.json);
                    if (saved.optString("next").isEmpty() || pages.containsKey(saved.optString("next"))) return saved.put("cached", true);
                }
            }
            org.schabi.newpipe.extractor.search.SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(query, java.util.Collections.singletonList("music_songs"), "");
            ex.fetchPage(); SearchInfo info = SearchInfo.getInfo(ex);
            if (info.getRelatedItems().isEmpty() && !info.getErrors().isEmpty()) throw new IOException("Error al leer resultados", info.getErrors().get(0));
            found = info.getRelatedItems(); next = info.getNextPage(); link = ex.getLinkHandler();
        } else {
            SearchSession session;
            synchronized (MusicCatalog.class) { session = pages.get(token); }
            if (session == null || !session.query.equalsIgnoreCase(query) || !session.mode.equals(mode) || System.currentTimeMillis() - session.created > 1800000)
                throw new IOException("La búsqueda caducó. Vuelve a buscar.");
            link = session.handler;
            org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<InfoItem> page = SearchInfo.getMoreItems(ServiceList.YouTube, link, session.page);
            found = page.getItems(); next = page.getNextPage();
            if (found.isEmpty() && !page.getErrors().isEmpty()) throw new IOException("Error al cargar más resultados", page.getErrors().get(0));
        }
        JSONArray items = new JSONArray();
        for (InfoItem item : found) {
            if (!(item instanceof StreamInfoItem)) continue;
            StreamInfoItem stream = (StreamInfoItem) item;
            String id = ServiceList.YouTube.getStreamLHFactory().getId(stream.getUrl());
            if (videoId(id)) items.put(new JSONObject().put("id", id).put("title", stream.getName()).put("channel", stream.getUploaderName()));
        }
        String nextToken = "";
        if (org.schabi.newpipe.extractor.Page.isValid(next)) {
            nextToken = java.util.UUID.randomUUID().toString();
            SearchSession session = new SearchSession(); session.query = query; session.mode = mode; session.handler = link; session.page = next;
            synchronized (MusicCatalog.class) { pages.put(nextToken, session); while (pages.size() > 80) pages.remove(pages.keySet().iterator().next()); }
        }
        JSONObject result = new JSONObject().put("items", items).put("next", nextToken).put("cached", false);
        if (token == null || token.isEmpty()) synchronized (MusicCatalog.class) {
            searches.put(key, new SearchCache(result.toString())); while (searches.size() > 12) searches.remove(searches.keySet().iterator().next());
        }
        return result;
    }
    public static final class Resolved {
        public final String url, title, channel;
        public String extension = "m4a", mime = "audio/mp4";
        final long validUntil;
        Resolved(String u, String t, String c) {
            url = u; title = t; channel = c;
            long expires = System.currentTimeMillis() + 10 * 60_000L;
            try {
                String value = android.net.Uri.parse(u).getQueryParameter("expire");
                if (value != null) expires = Math.min(expires, Long.parseLong(value) * 1000L - 60_000L);
            } catch (RuntimeException ignored) { }
            validUntil = expires;
        }
    }
    // Playback never queues behind unrelated downloads or speculative preparation.
    private static final java.util.concurrent.ThreadPoolExecutor foreground = (java.util.concurrent.ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final java.util.concurrent.ThreadPoolExecutor background = (java.util.concurrent.ThreadPoolExecutor) java.util.concurrent.Executors.newFixedThreadPool(1);
    private static final java.util.concurrent.ScheduledExecutorService cleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static final ThreadLocal<ResolveJob> activeJob = new ThreadLocal<>();
    private static final Map<String, ResolveJob> pending = new java.util.HashMap<>();
    private static final class ResolveJob {
        int waiters;
        final java.util.Set<HttpURLConnection> connections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.FutureTask<Resolved> task;
        ResolveJob(String id) {
            task = new java.util.concurrent.FutureTask<Resolved>(() -> {
                activeJob.set(this);
                try { return resolveFresh(id); } finally { activeJob.remove(); }
            }) {
                @Override protected void done() {
                    synchronized (MusicCatalog.class) { if (pending.get(id) == ResolveJob.this) pending.remove(id); }
                }
            };
        }
    }
    public static Resolved resolve(String id) throws Exception { return resolve(id, false); }
    public static Resolved resolveForPlayback(String id) throws Exception { return resolve(id, true); }
    private static Resolved resolve(String id, boolean priority) throws Exception {
        if (!videoId(id)) throw new IllegalArgumentException("Video inválido");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelado");
        final ResolveJob job;
        synchronized (MusicCatalog.class) {
            Resolved hit = cache.get(id);
            if (hit != null && hit.validUntil > System.currentTimeMillis()) return hit;
            ResolveJob existing = pending.get(id);
            if (existing == null) {
                if (pending.size() >= 12) throw new IOException("Hay demasiadas canciones en preparación");
                job = new ResolveJob(id); pending.put(id, job);
                (priority ? foreground : background).execute(job.task);
            } else {
                job = existing;
                // Promote queued work; a running request for the same song is shared.
                if (priority && background.remove(job.task)) foreground.execute(job.task);
            }
            job.waiters++;
        }
        try { return job.task.get(priority ? 35 : 60, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause(); if (cause instanceof Exception) throw (Exception) cause; throw new IOException("Extractor no disponible", cause);
        } finally {
            synchronized (MusicCatalog.class) { job.waiters--; }
            // Brief handoff window lets an interrupted preview become selected playback.
            cleanup.schedule(() -> {
                synchronized (MusicCatalog.class) {
                    if (job.waiters != 0 || job.task.isDone()) return;
                    job.task.cancel(true); foreground.remove(job.task); background.remove(job.task);
                }
                for (HttpURLConnection connection : job.connections) connection.disconnect();
            }, 750, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
    private static Resolved resolveFresh(String id) throws Exception {
        if (!videoId(id)) throw new IllegalArgumentException("Video inválido");
        synchronized (MusicCatalog.class) {
            Resolved cached = cache.get(id);
            if (cached != null && cached.validUntil > System.currentTimeMillis()) return cached;
            cache.remove(id);
        }
        init();
        // Only extract what playback needs. StreamInfo also extracts video formats,
        // related items, subtitles and other metadata before returning any audio.
        StreamExtractor info = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + id);
        info.fetchPage();
        AudioStream best = null;
        for (AudioStream audio : info.getAudioStreams()) {
            if (!audio.isUrl() || audio.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !audio.getContent().startsWith("https://")) continue;
            if (best == null || (audio.getFormat() == MediaFormat.M4A && best.getFormat() != MediaFormat.M4A)
                || (audio.getFormat() == best.getFormat() && audio.getAverageBitrate() > best.getAverageBitrate())) best = audio;
        }
        if (best == null) throw new IOException("Este video no ofrece audio compatible");
        String channel = "YouTube";
        try { channel = info.getUploaderName(); } catch (Exception ignored) { }
        Resolved result = new Resolved(best.getContent(), info.getName(), channel);
        if (best.getFormat() != MediaFormat.M4A) { result.extension = "webm"; result.mime = "audio/webm"; }
        if (!Thread.currentThread().isInterrupted()) synchronized (MusicCatalog.class) {
            cache.put(id, result);
            while (cache.size() > 24) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }
    static String error(Throwable error) {
        String type = error.getClass().getSimpleName();
        if (type.contains("Captcha") || type.contains("Restricted")) return "YouTube solicita una verificación o restringe este contenido. Prueba otra canción.";
        if (type.contains("Unavailable") || type.contains("Age")) return "Esta canción no está disponible para reproducción pública.";
        if (error instanceof java.net.SocketTimeoutException || error instanceof java.util.concurrent.TimeoutException) return "La conexión tardó demasiado. Vuelve a intentarlo.";
        return "No se pudo conectar con el catálogo (" + type + "). Reintenta; si persiste, puede necesitar una actualización de NewPipe.";
    }
}
