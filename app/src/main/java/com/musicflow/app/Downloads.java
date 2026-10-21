package com.musicflow.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android owns transfers once enqueued, including while the Activity is closed. */
public final class Downloads {
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final java.util.Set<String> resolving = new java.util.HashSet<>();
    private static JSONObject read(Context c) {
        try { return new JSONObject(c.getSharedPreferences("musicflow.downloads", 0).getString("items", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    private static void save(Context c, JSONObject data) { c.getSharedPreferences("musicflow.downloads", 0).edit().putString("items", data.toString()).apply(); }
    public static synchronized void start(Context context, String id, String title) throws Exception {
        if (!MusicCatalog.videoId(id)) throw new IllegalArgumentException("Canción inválida");
        Context c = context.getApplicationContext(); JSONObject all = read(c), old = all.optJSONObject(id);
        if (old != null && !"error".equals(old.optString("status"))) return;
        if (all.length() >= 100 && old == null) throw new IllegalArgumentException("Máximo de 100 descargas; elimina alguna para continuar.");
        if (resolving.size() >= 2) throw new IllegalArgumentException("Espera a que se preparen las descargas actuales.");
        if (old != null && old.optLong("job", -1) > 0) c.getSystemService(DownloadManager.class).remove(old.optLong("job"));
        String attempt = java.util.UUID.randomUUID().toString();
        all.put(id, new JSONObject().put("id", id).put("title", title == null ? "Canción" : title.substring(0, Math.min(300, title.length())))
            .put("channel", "YouTube").put("status", "resolving").put("attempt", attempt));
        save(c, all); resolving.add(attempt);
        worker.submit(() -> {
            try {
                MusicCatalog.Resolved info = MusicCatalog.resolve(id);
                synchronized (Downloads.class) {
                    JSONObject data = read(c), item = data.optJSONObject(id);
                    if (item == null || !attempt.equals(item.optString("attempt"))) return;
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.url))
                        .setTitle(info.title).setDescription("MusicFlow · descarga de audio")
                        .setMimeType(info.mime).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverRoaming(false)
                        .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MUSIC, id + "-" + attempt + "." + info.extension);
                    long job = c.getSystemService(DownloadManager.class).enqueue(request);
                    item.put("job", job).put("title", info.title).put("channel", info.channel).put("status", "queued");
                    save(c, data);
                }
            } catch (Exception | LinkageError e) {
                synchronized (Downloads.class) {
                    JSONObject data = read(c), item = data.optJSONObject(id);
                    if (item != null && attempt.equals(item.optString("attempt"))) try { item.put("status", "error").put("error", MusicCatalog.error(e)); save(c, data); } catch (Exception ignored) { }
                }
            } finally { synchronized (Downloads.class) { resolving.remove(attempt); } }
        });
    }
    public static synchronized JSONArray snapshot(Context c) {
        JSONObject all = read(c); JSONArray result = new JSONArray(); DownloadManager dm = c.getSystemService(DownloadManager.class);
        java.util.Iterator<String> ids = all.keys();
        while (ids.hasNext()) {
            String id = ids.next(); JSONObject item = all.optJSONObject(id); if (item == null) continue;
            try {
                long job = item.optLong("job", -1);
                if (job > 0) try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(job))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        item.put("progress", total > 0 ? Math.min(100, bytes * 100 / total) : -1).put("bytes", bytes);
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Uri local = dm.getUriForDownloadedFile(job);
                            if (local == null || bytes <= 0) throw new java.io.IOException("Archivo no disponible");
                            if (!"done".equals(item.optString("status"))) {
                                AudioLibrary.addDownloaded(c, id, local.toString(), item.optString("title"), item.optString("channel"));
                            }
                            item.put("status", "done");
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                            item.put("status", "error").put("error", reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ? "No hay espacio suficiente en el teléfono." : "La descarga falló (" + reason + "). Pulsa reintentar.");
                            MusicCatalog.invalidate(id);
                        } else item.put("status", status == DownloadManager.STATUS_PAUSED ? "waiting" : status == DownloadManager.STATUS_PENDING ? "queued" : "downloading");
                    } else { item.put("status", "error").put("error", "El archivo ya no está disponible. Vuelve a descargarlo."); AudioLibrary.remove(c, id); }
                }
                else if (!resolving.contains(item.optString("attempt"))) item.put("status", "error").put("error", "Preparación interrumpida. Pulsa reintentar.");
                JSONObject out = new JSONObject();
                for (String key : new String[]{"id", "title", "channel", "status", "progress", "bytes", "error"}) if (item.has(key)) out.put(key, item.get(key));
                result.put(out);
            } catch (Exception e) {
                try { item.put("status", "error").put("error", "No se pudo leer la descarga. Reintenta."); result.put(new JSONObject().put("id", id).put("title", item.optString("title")).put("status", "error").put("error", "No se pudo leer la descarga.")); } catch (Exception ignored) { }
            }
        }
        save(c, all); return result;
    }
    public static synchronized void remove(Context c, String id) {
        JSONObject all = read(c), item = all.optJSONObject(id);
        if (item == null) return;
        all.remove(id); save(c, all);
        if (item.optLong("job", -1) > 0) c.getSystemService(DownloadManager.class).remove(item.optLong("job"));
        AudioLibrary.remove(c, id);
    }
}
