package com.musicflow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import java.lang.reflect.Method;

public class AudioSmoke extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private Method state;
    private Context target;
    private JSONObject snapshot() throws Exception {
        final JSONObject[] result = new JSONObject[1];
        runOnMainSync(() -> { try { result[0] = (JSONObject) state.invoke(null); } catch (Exception error) { throw new RuntimeException(error); } });
        return result[0];
    }
    private void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); report.append("PASS: ").append(message).append('\n'); }
    private Intent command(String action) { return new Intent().setClassName("com.musicflow.app", "com.musicflow.app.AudioPlaybackService").setAction("com.musicflow.app.AUDIO_" + action); }
    private void awaitPlaying(boolean value) throws Exception {
        for (int i = 0; i < 60; i++) { if (snapshot().optBoolean("playing") == value && (value || !snapshot().optBoolean("loading"))) return; Thread.sleep(100); }
        throw new AssertionError("Playback did not enter expected state: " + snapshot());
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            target = getTargetContext();
            Class<?> service = target.getClassLoader().loadClass("com.musicflow.app.AudioPlaybackService");
            state = service.getDeclaredMethod("state"); state.setAccessible(true);
            Activity activity = startActivitySync(new Intent().setClassName("com.musicflow.app", "com.musicflow.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(700);
            String id = "audio-00000000-0000-4000-8000-000000000001";
            runOnMainSync(() -> target.startForegroundService(command("PLAY").putExtra("id", id).putExtra("queue", "[\"" + id + "\"]")));
            awaitPlaying(true); check(snapshot().optLong("duration") > 9000, "Bundled audio prepared with correct duration");
            long before = snapshot().optLong("position");
            runOnMainSync(() -> activity.moveTaskToBack(true));
            Thread.sleep(2200);
            JSONObject background = snapshot();
            check(background.optBoolean("playing"), "Playback continues after Activity is minimized");
            check(background.optLong("position") > before + 1300, "Audio position advances in background");
            target.startService(command("PAUSE")); awaitPlaying(false);
            long paused = snapshot().optLong("position"); Thread.sleep(500);
            check(Math.abs(snapshot().optLong("position") - paused) < 150, "Pause command holds playback position");
            target.startService(command("SEEK").putExtra("position", 1000L)); Thread.sleep(350);
            check(Math.abs(snapshot().optLong("position") - 1000) < 500, "Native seeking works");
            target.startService(command("RESUME")); awaitPlaying(true); Thread.sleep(700);
            check(snapshot().optLong("position") > 1400, "Native resume advances playback again");
            target.stopService(command("STOP")); Thread.sleep(500);
            check(!snapshot().optBoolean("active"), "Stop releases playback service");
            Class<?> catalog = target.getClassLoader().loadClass("com.musicflow.app.MusicCatalog");
            org.json.JSONArray found = (org.json.JSONArray) catalog.getMethod("search", String.class).invoke(null, "Bad Bunny");
            check(found.length() > 0, "Public artist search returns native results: " + found.length());
            String videoId = found.getJSONObject(0).getString("id");
            String secondId = found.getJSONObject(1).getString("id");
            runOnMainSync(() -> target.startActivity(new Intent().setClassName("com.musicflow.app", "com.musicflow.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            Thread.sleep(800);
            String onlineQueue = "[\"" + videoId + "\",\"" + secondId + "\"]";
            runOnMainSync(() -> target.startForegroundService(command("PLAY").putExtra("id", videoId).putExtra("queue", onlineQueue)));
            for (int i = 0; i < 600 && !snapshot().optBoolean("playing"); i++) {
                if (!snapshot().optString("error").isEmpty()) throw new AssertionError(snapshot().optString("error"));
                Thread.sleep(100);
            }
            check(snapshot().optBoolean("playing"), "Online song prepared and playing");
            report.append("METRIC: cold start ").append(snapshot().optLong("startupMs")).append(" ms\n");
            long onlineBefore = snapshot().optLong("position");
            runOnMainSync(() -> activity.moveTaskToBack(true)); Thread.sleep(2500);
            check(snapshot().optBoolean("playing") && snapshot().optLong("position") > onlineBefore + 1000, "Online playback advances while minimized");
            for (int i = 0; i < 450 && !snapshot().optBoolean("nextReady"); i++) Thread.sleep(100);
            check(snapshot().optBoolean("nextReady"), "Next online song is prepared during current playback");
            target.startService(command("NEXT")); Thread.sleep(200); awaitPlaying(true);
            check(secondId.equals(snapshot().getJSONObject("track").getString("id")), "Next command uses the prepared song");
            report.append("METRIC: prepared transition ").append(snapshot().optLong("startupMs")).append(" ms\n");
            check(snapshot().optLong("startupMs") < 1200, "Prepared transition starts within 1.2 seconds");
            Object cached = catalog.getMethod("resolve", String.class).invoke(null, secondId);
            long cacheStart = android.os.SystemClock.elapsedRealtime();
            Object repeated = catalog.getMethod("resolve", String.class).invoke(null, secondId);
            check(cached == repeated, "Repeated resolution reuses the memory cache");
            report.append("METRIC: cached resolution ").append(android.os.SystemClock.elapsedRealtime() - cacheStart).append(" ms\n");
            target.startService(command("PREVIOUS"));
            for (int i = 0; i < 600 && (!snapshot().optBoolean("playing") || !videoId.equals(snapshot().getJSONObject("track").getString("id"))); i++) Thread.sleep(100);
            check(videoId.equals(snapshot().getJSONObject("track").getString("id")) && snapshot().optBoolean("playing"), "Previous song can replay using cached link: " + snapshot());
            target.startService(command("PAUSE")); awaitPlaying(false);
            check(!snapshot().optBoolean("nextReady"), "Pause releases speculative next player");
            target.startService(command("RESUME")); awaitPlaying(true);
            for (int i = 0; i < 450 && !snapshot().optBoolean("nextReady"); i++) Thread.sleep(100);
            check(snapshot().optBoolean("nextReady"), "Resume prepares the next song again");
            target.startService(command("SEEK").putExtra("position", snapshot().optLong("duration") - 1500));
            for (int i = 0; i < 100 && !secondId.equals(snapshot().getJSONObject("track").getString("id")); i++) Thread.sleep(100);
            check(secondId.equals(snapshot().getJSONObject("track").getString("id")) && snapshot().optBoolean("playing"), "Completion advances automatically to prepared song");
            target.stopService(command("STOP")); Thread.sleep(500);
            runOnMainSync(() -> target.startActivity(new Intent().setClassName("com.musicflow.app", "com.musicflow.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            result.putString("stream", report.toString()); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            report.append("FAIL: ").append(android.util.Log.getStackTraceString(error));
            if (target != null) target.stopService(command("STOP"));
            result.putString("stream", report.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
