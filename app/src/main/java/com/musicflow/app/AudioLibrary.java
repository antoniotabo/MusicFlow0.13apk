package com.musicflow.app;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;

/** Local audio registry; public video IDs are resolved on demand. */
final class AudioLibrary {
    private static final String PREFS = "musicflow.audio";
    static JSONArray read(Context context) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            if (!prefs.contains("tracks")) {
                JSONObject demo = new JSONObject().put("id", "audio-00000000-0000-4000-8000-000000000001").put("kind", "audio").put("title", "MusicFlow · melodía de prueba").put("channel", "Audio incluido · prueba de pantalla bloqueada").put("uri", "android.resource://" + context.getPackageName() + "/raw/musicflow_demo");
                prefs.edit().putString("tracks", new JSONArray().put(demo).toString()).apply();
            }
            return new JSONArray(prefs.getString("tracks", "[]"));
        }
        catch (Exception error) { return new JSONArray(); }
    }
    static JSONObject find(Context context, String id) {
        JSONArray tracks = read(context);
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject item = tracks.optJSONObject(i);
            if (item != null && item.optString("id").equals(id)) return item;
        }
        if (MusicCatalog.videoId(id)) try { return new JSONObject().put("id", id).put("kind", "youtube").put("title", "Cargando canción…").put("channel", "YouTube"); } catch (Exception ignored) { }
        return null;
    }
    static boolean allowedStream(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || value.length() > 2000) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String blocked : new String[]{"youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com"}) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) return false;
            }
            return true;
        } catch (Exception error) { return false; }
    }
    static synchronized void add(Context context, String uri, String title, boolean stream) throws Exception {
        if (stream ? !allowedStream(uri) : !"content".equals(Uri.parse(uri).getScheme())) throw new IllegalArgumentException("Fuente de audio inválida");
        JSONArray items = read(context);
        for (int i = 0; i < items.length(); i++) if (uri.equals(items.getJSONObject(i).optString("uri"))) return;
        if (items.length() >= 5000) throw new IllegalArgumentException("Biblioteca llena");
        JSONObject item = new JSONObject();
        item.put("id", "audio-" + UUID.randomUUID());
        item.put("kind", "audio");
        item.put("title", title == null || title.trim().isEmpty() ? "Audio sin título" : title.trim().substring(0, Math.min(title.trim().length(), 300)));
        item.put("channel", stream ? "Radio / audio en línea" : "Música del dispositivo");
        item.put("uri", uri);
        items.put(item);
        context.getSharedPreferences(PREFS, 0).edit().putString("tracks", items.toString()).apply();
    }
    static synchronized void remove(Context context, String id) {
        JSONArray old = read(context), next = new JSONArray();
        for (int i = 0; i < old.length(); i++) { JSONObject t = old.optJSONObject(i); if (t != null && !id.equals(t.optString("id"))) next.put(t); }
        context.getSharedPreferences(PREFS, 0).edit().putString("tracks", next.toString()).apply();
    }
    static synchronized void addDownloaded(Context context, String id, String uri, String title, String channel) throws Exception {
        if (!MusicCatalog.videoId(id) || !"content".equals(Uri.parse(uri).getScheme())) throw new IllegalArgumentException("Descarga inválida");
        JSONArray items = read(context);
        for (int i = 0; i < items.length(); i++) if (id.equals(items.getJSONObject(i).optString("id"))) return;
        items.put(new JSONObject().put("id", id).put("kind", "youtube").put("title", title).put("channel", channel).put("uri", uri));
        context.getSharedPreferences(PREFS, 0).edit().putString("tracks", items.toString()).apply();
    }
    static JSONArray metadata(Context context) {
        JSONArray items = read(context), out = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject copy = new JSONObject();
            try { for (String field : new String[]{"id", "kind", "title", "channel"}) copy.put(field, item.optString(field)); out.put(copy); }
            catch (Exception ignored) { }
        }
        return out;
    }
}
