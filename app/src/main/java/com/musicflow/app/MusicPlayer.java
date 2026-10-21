package com.musicflow.app;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** Main-looper audio player. Audio focus and notifications belong to the service. */
final class MusicPlayer {
    interface PreparedListener { void onPrepared(MusicPlayer player); }
    interface ErrorListener { boolean onError(MusicPlayer player, int what, int extra); }
    interface CompletionListener { void onCompletion(MusicPlayer player); }
    private final ExoPlayer engine;
    private PreparedListener preparedListener;
    private ErrorListener errorListener;
    private CompletionListener completionListener;
    private boolean readyDelivered, endedDelivered, released;

    MusicPlayer(Context context) {
        Context app = context.getApplicationContext();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("MusicFlow/0.10")
                .setConnectTimeoutMs(8000).setReadTimeoutMs(8000);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(app, http))
                // The service owns the single fresh-link retry; avoid nested retry loops.
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0));
        engine = new ExoPlayer.Builder(app)
                .setMediaSourceFactory(sources)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 750, 2000)
                        .setPrioritizeTimeOverSizeThresholds(true).build())
                .build();
        engine.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
        engine.setWakeMode(C.WAKE_MODE_LOCAL);
        engine.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (released) return;
                if (state == Player.STATE_READY && !readyDelivered) {
                    readyDelivered = true;
                    if (preparedListener != null) preparedListener.onPrepared(MusicPlayer.this);
                }
                if (state == Player.STATE_ENDED && !endedDelivered) {
                    endedDelivered = true;
                    if (completionListener != null) completionListener.onCompletion(MusicPlayer.this);
                }
                if (state != Player.STATE_ENDED) endedDelivered = false;
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (!released && errorListener != null) errorListener.onError(MusicPlayer.this, error.errorCode, 0);
            }
        });
    }
    void setOnPreparedListener(PreparedListener listener) { preparedListener = listener; }
    void setOnErrorListener(ErrorListener listener) { errorListener = listener; }
    void setOnCompletionListener(CompletionListener listener) { completionListener = listener; }
    void setDataSource(Context context, Uri uri) {
        readyDelivered = false; endedDelivered = false;
        engine.setMediaItem(MediaItem.fromUri(uri));
    }
    void prepareAsync() { engine.prepare(); }
    void start() { engine.play(); }
    void pause() { engine.pause(); }
    // Preserve transport intent during rebuffering, so pause and audio focus still work.
    boolean isPlaying() { return !released && engine.getPlayWhenReady()
            && engine.getPlaybackState() != Player.STATE_ENDED && engine.getPlaybackState() != Player.STATE_IDLE; }
    boolean isBuffering() { return !released && engine.getPlaybackState() == Player.STATE_BUFFERING; }
    long bufferedDuration() { return released ? 0 : engine.getTotalBufferedDuration(); }
    void seekTo(int position) { endedDelivered = false; engine.seekTo(position); }
    int getDuration() { long d = engine.getDuration(); return d == C.TIME_UNSET ? 0 : (int) Math.min(Integer.MAX_VALUE, d); }
    int getCurrentPosition() { return (int) Math.min(Integer.MAX_VALUE, engine.getCurrentPosition()); }
    void release() {
        if (released) return;
        released = true; preparedListener = null; errorListener = null; completionListener = null;
        engine.release();
    }
}
