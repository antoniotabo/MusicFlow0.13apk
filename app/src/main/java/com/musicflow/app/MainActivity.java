package com.musicflow.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public final class MainActivity extends Activity {
    private WebView web;
    private final java.util.concurrent.ExecutorService searches = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ExecutorService downloadStatusWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> searchJob;
    private int searchGeneration;
    private WebView searchWeb;
    private boolean downloadPolling;
    private int uiTicks;
    private LinearLayout root;
    private LinearLayout searchPanel;
    private Button useVideo;
    private TextView searchStatus;
    private String lastSearchQuery;
    private String pendingExport;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler audioUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable audioUiUpdate = new Runnable() {
        @Override public void run() {
            if (web != null) web.evaluateJavascript("window.receiveAudioState && window.receiveAudioState(" + AudioPlaybackService.state(MainActivity.this) + ")", null);
            if (++uiTicks % 6 == 0) refreshDownloads();
            audioUiHandler.postDelayed(this, 500);
        }
    };
    private static final String HOST = "com.musicflow.app";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, -1));
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setBackgroundColor(android.graphics.Color.rgb(17,17,19));
        setContentView(root);
        web.setBackgroundColor(android.graphics.Color.rgb(16, 17, 26));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if(android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return android.view.WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void resetShuffleHistory() {
                runOnUiThread(() -> AudioPlaybackService.resetHistory(MainActivity.this));
            }
            @JavascriptInterface public void importAudio() {
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, 3);
                });
            }
            @JavascriptInterface public void addAudioStream(String title, String url) {
                if (url == null || title == null || title.length() > 300) return;
                runOnUiThread(() -> {
                    try { AudioLibrary.add(MainActivity.this, url.trim(), title, true); publishAudioLibrary(); }
                    catch (Exception error) { audioMessage("Usa un enlace HTTPS de audio directo o de radio, no una página de YouTube."); }
                });
            }
            @JavascriptInterface public void removeAudio(String id) {
                if (id == null) return;
                runOnUiThread(() -> {
                    if (id.equals(AudioPlaybackService.state().optJSONObject("track") == null ? "" : AudioPlaybackService.state().optJSONObject("track").optString("id"))) stopService(new Intent(MainActivity.this, AudioPlaybackService.class));
                    AudioLibrary.remove(MainActivity.this, id); publishAudioLibrary();
                });
            }
            @JavascriptInterface public void playAudio(String id, String queue) { playAudioQueue(id, queue, "", false); }
            @JavascriptInterface public void playAudioQueue(String id, String queue, String query, boolean automatic) {
                if (id == null || queue == null || queue.length() > 250000 || query == null || query.length() > 500) return;
                runOnUiThread(() -> {
                    if (AudioLibrary.find(MainActivity.this, id) == null) { audioMessage("Este audio no está disponible. Vuelve a añadirlo desde Mi música."); return; }
                    Intent intent = new Intent(MainActivity.this, AudioPlaybackService.class).setAction(AudioPlaybackService.PLAY).putExtra("id", id).putExtra("queue", queue).putExtra("autoQuery", query).putExtra("automatic", automatic);
                    try { startForegroundService(intent); } catch (RuntimeException error) { audioMessage("Android no permitió iniciar el reproductor. Vuelve a abrir MusicFlow y reintenta."); }
                });
            }
            @JavascriptInterface public void audioControl(String command, long position) {
                runOnUiThread(() -> {
                    String action;
                    if ("pause".equals(command)) action = AudioPlaybackService.PAUSE;
                    else if ("resume".equals(command)) action = AudioPlaybackService.RESUME;
                    else if ("toggle".equals(command)) action = AudioPlaybackService.TOGGLE;
                    else if ("next".equals(command)) action = AudioPlaybackService.NEXT;
                    else if ("previous".equals(command)) action = AudioPlaybackService.PREVIOUS;
                    else if ("shuffle".equals(command)) {
                        getSharedPreferences("musicflow.player", 0).edit().putBoolean("shuffle", position != 0).apply();
                        action = AudioPlaybackService.SHUFFLE;
                    }
                    else if ("seek".equals(command)) action = AudioPlaybackService.SEEK;
                    else if ("stop".equals(command)) { stopService(new Intent(MainActivity.this, AudioPlaybackService.class)); return; }
                    else return;
                    if (!AudioPlaybackService.state().optBoolean("active")) {
                        if (AudioPlaybackService.SHUFFLE.equals(action) || !AudioPlaybackService.state(MainActivity.this).optBoolean("active")) return;
                        try { startForegroundService(new Intent(MainActivity.this, AudioPlaybackService.class).setAction(action).putExtra("position", position)); }
                        catch(RuntimeException error) { audioMessage("No se pudo recuperar la sesión. Pulsa reproducir."); }
                        return;
                    }
                    try { startService(new Intent(MainActivity.this, AudioPlaybackService.class).setAction(action).putExtra("position", position)); }
                    catch (RuntimeException error) { audioMessage("No se pudo cambiar la reproducción."); }
                });
            }
            @JavascriptInterface public void searchYouTube(String query, int requestId) { searchCatalog(query, "songs", "", requestId); }
            @JavascriptInterface public void searchCatalog(String query, String mode, String token, int requestId) {
                if (query == null || query.trim().isEmpty() || query.length() > 500 || token == null || token.length() > 100) return;
                runOnUiThread(() -> {
                    SearchPreview.clear();
                    final int generation = ++searchGeneration;
                    if (searchJob != null) searchJob.cancel(true);
                    searchJob = searches.submit(() -> {
                        JSONObject response = new JSONObject(); String error = "";
                        try { response = MusicCatalog.searchPage(query.trim(), mode, token); } catch (Exception | LinkageError e) { error = MusicCatalog.error(e); }
                        final JSONObject data = response; final String message = error;
                        runOnUiThread(() -> {
                            if (isDestroyed() || generation != searchGeneration) return;
                            org.json.JSONArray items = data.optJSONArray("items");
                            if(token.isEmpty() && items!=null && items.length()>0 && !AudioPlaybackService.state().optBoolean("active")) SearchPreview.prepare(MainActivity.this, items.optJSONObject(0).optString("id"));
                            web.evaluateJavascript("window.receiveSearchResults && window.receiveSearchResults(" + requestId + "," + (items == null ? "[]" : items) + "," + JSONObject.quote(message) + "," + data + ")", null);
                        });
                    });
                });
            }
            @JavascriptInterface public void downloadTrack(String id, String title) {
                runOnUiThread(() -> {
                    try { Downloads.start(MainActivity.this, id, title); refreshDownloads(); }
                    catch (Exception e) { audioMessage(e.getMessage() == null ? "No se pudo iniciar la descarga." : e.getMessage()); }
                });
            }
            @JavascriptInterface public void removeDownload(String id) {
                runOnUiThread(() -> {
                    JSONObject track = AudioPlaybackService.state().optJSONObject("track");
                    if (track != null && id.equals(track.optString("id"))) stopService(new Intent(MainActivity.this, AudioPlaybackService.class));
                    Downloads.remove(MainActivity.this, id); refreshDownloads();
                });
            }
            @JavascriptInterface public void exportLibrary(String json) {
                if (json == null || json.length() > 5000000) return;
                runOnUiThread(() -> {
                    pendingExport = json;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "MusicFlow-biblioteca.json");
                    startActivityForResult(intent, 2);
                });
            }
        }, "MusicFlowAndroid");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
                if (!HOST.equals(Uri.parse(url).getHost())) { result.cancel(); return true; }
                android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setText(defaultValue);
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("MusicFlow").setMessage(message).setView(input)
                    .setPositiveButton("Aceptar", (dialog, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("Cancelar", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                if (!HOST.equals(Uri.parse(url).getHost())) { result.cancel(); return true; }
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("MusicFlow").setMessage(message)
                    .setPositiveButton("Aceptar", (dialog, which) -> result.confirm())
                    .setNegativeButton("Cancelar", (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, 1);
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (HOST.equals(Uri.parse(url).getHost())) publishAudioLibrary();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!HOST.equals(request.getUrl().getHost())) return null;
                String path = request.getUrl().getPath();
                String file = "/".equals(path) ? "index.html" : path.substring(1);
                if (!file.matches("[a-zA-Z0-9._-]+")) return new WebResourceResponse("text/plain", "UTF-8", null);
                String mime = file.endsWith(".js") ? "application/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
                try { return new WebResourceResponse(mime, "UTF-8", getAssets().open(file)); }
                catch (IOException error) { return new WebResourceResponse("text/plain", "UTF-8", null); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (HOST.equals(request.getUrl().getHost())) return false;
                if ("https".equals(request.getUrl().getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (android.content.ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });
        web.loadUrl("https://" + HOST + "/index.html");
    }
    @Override protected void onPause() {
        SearchPreview.clear();
        audioUiHandler.removeCallbacks(audioUiUpdate);
        // Playback belongs to the foreground service and survives Activity suspension.
        web.evaluateJavascript("window.pauseMusic && window.pauseMusic()", null);
        if (searchWeb != null) { searchWeb.onPause(); searchWeb.loadUrl("about:blank"); }
        web.onPause(); super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        audioUiHandler.removeCallbacks(audioUiUpdate); audioUiHandler.post(audioUiUpdate);
        if (web != null) web.onResume();
    }
    @Override protected void onDestroy() { SearchPreview.clear(); searchGeneration++; searches.shutdownNow(); downloadStatusWorker.shutdownNow(); audioUiHandler.removeCallbacksAndMessages(null); if (searchWeb != null) searchWeb.destroy(); web.destroy(); super.onDestroy(); }
    private void audioMessage(String message) { web.evaluateJavascript("window.receiveAudioMessage && window.receiveAudioMessage(" + JSONObject.quote(message) + ")", null); }
    private void refreshDownloads() {
        if (downloadPolling || isDestroyed()) return;
        downloadPolling = true;
        downloadStatusWorker.submit(() -> {
            org.json.JSONArray items;
            try { items = Downloads.snapshot(getApplicationContext()); }
            catch (RuntimeException e) { runOnUiThread(() -> downloadPolling = false); return; }
            runOnUiThread(() -> {
                downloadPolling = false;
                if (!isDestroyed()) web.evaluateJavascript("window.receiveDownloads && window.receiveDownloads(" + items + ")", null);
            });
        });
    }
    private void publishAudioLibrary() {
        refreshDownloads();
        if (web != null) {
            web.evaluateJavascript("window.receiveAudioLibrary && window.receiveAudioLibrary(" + AudioLibrary.metadata(this) + ")", null);
            web.evaluateJavascript("window.receiveAudioState && window.receiveAudioState(" + AudioPlaybackService.state(MainActivity.this) + ")", null);
        }
    }
    private void importAudioUri(Uri uri, int flags) throws Exception {
        getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String title = "Audio importado";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) title = cursor.getString(0);
        }
        AudioLibrary.add(this, uri.toString(), title, false);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 3 && result == RESULT_OK && data != null) {
            int failures = 0;
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    try { importAudioUri(data.getClipData().getItemAt(i).getUri(), data.getFlags()); } catch (Exception error) { failures++; }
                }
            } else if (data.getData() != null) { try { importAudioUri(data.getData(), data.getFlags()); } catch (Exception error) { failures++; } }
            publishAudioLibrary();
            if (failures > 0) audioMessage("Algunos archivos no pudieron añadirse. Elige archivos de audio que este dispositivo pueda abrir.");
        }
        if (request == 1 && fileCallback != null) {
            fileCallback.onReceiveValue(result == RESULT_OK && data != null ? new Uri[]{data.getData()} : null);
            fileCallback = null;
        }
        if (request == 2 && result == RESULT_OK && data != null && pendingExport != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                android.widget.Toast.makeText(this, "No se pudo exportar la biblioteca", android.widget.Toast.LENGTH_LONG).show();
            }
        }
        if (request == 2) pendingExport = null;
    }
}
