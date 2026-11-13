package tv.anitv;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
final class NativePlayback implements AutoCloseable {
    interface Listener {
        void ready();
        void buffering(boolean buffering);
        void firstFrame(int width, int height);
        void ended();
        void error(String message);
        void changed();
    }
    private final Activity activity;
    private final SurfaceView surface;
    private final OkHttpClient http;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = WorkPools.fixed(1);
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private MediaPlayer nativePlayer;
    private ExoPlayer hlsPlayer;
    private RangeMediaSource data;
    private Future<?> preparing;
    private boolean ready, rendered, resumeAfterFocus, hasFocus, wantPlay, focusRequested, closed;
    private int generation;
    private float speed, nativeSpeed=1f;
    private int aspectMode, videoWidth, videoHeight;
    NativePlayback(Activity activity, SurfaceView surface, OkHttpClient http, Listener listener) {
        this.activity = activity; this.surface = surface; this.http = http; this.listener = listener;
        android.content.SharedPreferences prefs=new Library(activity).prefs;
        speed=prefs.getFloat("playback-speed",1f);aspectMode=prefs.getInt("video-aspect",0);
        audio = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(change -> {
                    if(closed || !focusRequested)return;
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && playing();
                        if (nativePlayer != null && ready) nativePlayer.pause();
                        hasFocus = false; listener.changed();
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        hasFocus = true; if (nativePlayer != null) nativePlayer.setVolume(1, 1);
                        if (resumeAfterFocus && nativePlayer != null && ready) nativePlayer.start();
                        resumeAfterFocus = false; listener.changed();
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && nativePlayer != null) nativePlayer.setVolume(.2f, .2f);
                }, main).build();
    }
    void open(PlaySource source, long position, boolean play) {
        if(closed)return;
        stop(); final int token = generation; wantPlay = play; listener.buffering(true);
        if (source.hls()) { openHls(source, position, token); return; }
        RangeMediaSource input = new RangeMediaSource(http, source); data = input;
        preparing = worker.submit(() -> {
            try {
                boolean manifest = input.probeIsHls();
                main.post(() -> {
                    if (token != generation) { input.close(); return; }
                    if (manifest) { input.close(); data = null; openHls(source, position, token); }
                    else openNative(input, position, token);
                });
            } catch (Exception error) {
                main.post(() -> { if (token == generation) listener.error(error.getMessage() == null ? "视频连接失败" : error.getMessage()); });
            }
        });
    }
    private void openNative(RangeMediaSource input, long position, int token) {
        MediaPlayer player = new MediaPlayer(); nativePlayer = player;
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        player.setDisplay(surface.getHolder()); player.setScreenOnWhilePlaying(true); player.setVolume(1, 1);
        player.setOnPreparedListener(p -> {
            if (token != generation || nativePlayer != p) return;
            ready = true; listener.ready();
            if (position > 0 && position < p.getDuration()) {
                p.setOnSeekCompleteListener(s -> {
                    s.setOnSeekCompleteListener(null); if (token == generation && wantPlay) play();
                    listener.changed();
                }); p.seekTo(position, MediaPlayer.SEEK_CLOSEST_SYNC);
            } else if (wantPlay) play();
            listener.changed();
        });
        player.setOnVideoSizeChangedListener((p, w, h) -> { if (token == generation) fit(w, h); });
        player.setOnInfoListener((p, what, extra) -> {
            if (token != generation) return true;
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                rendered = true;
                listener.buffering(false); listener.firstFrame(p.getVideoWidth(), p.getVideoHeight());
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) listener.buffering(true);
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) listener.buffering(false);
            return false;
        });
        player.setOnCompletionListener(p -> { if (token == generation) listener.ended(); });
        player.setOnErrorListener((p, what, extra) -> {
            if (token == generation) { ready = false; listener.error("播放失败（" + what + "/" + extra + "），可重试或切换线路"); }
            return true;
        });
        try { player.setDataSource(input); player.prepareAsync(); }
        catch (Exception error) { listener.error("无法准备视频，请重试或切换线路"); }
    }
    private void openHls(PlaySource source, long position, int token) {
        DefaultHttpDataSource.Factory network = new DefaultHttpDataSource.Factory()
                .setUserAgent(PlaySource.USER_AGENT).setDefaultRequestProperties(source.headers)
                .setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
        hlsPlayer = new ExoPlayer.Builder(activity).setMediaSourceFactory(new DefaultMediaSourceFactory(network)).build();
        hlsPlayer.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        hlsPlayer.setHandleAudioBecomingNoisy(true); hlsPlayer.setVideoSurfaceView(surface);
        hlsPlayer.setPlaybackSpeed(speed);
        hlsPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (token != generation) return;
                listener.buffering(state == Player.STATE_BUFFERING);
                if (state == Player.STATE_READY) { ready = true; listener.ready(); }
                if (state == Player.STATE_ENDED) listener.ended();
                listener.changed();
            }
            @Override public void onIsPlayingChanged(boolean playing) { if (token == generation) listener.changed(); }
            @Override public void onRenderedFirstFrame() {
                if (token != generation || hlsPlayer == null) return;
                rendered = true;
                androidx.media3.common.VideoSize size = hlsPlayer.getVideoSize();
                fit(Math.round(size.width*size.pixelWidthHeightRatio), size.height); listener.firstFrame(size.width, size.height);
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) { if (token == generation) fit(Math.round(size.width*size.pixelWidthHeightRatio), size.height); }
            @Override public void onPlayerError(PlaybackException error) {
                if (token == generation) { ready = false; listener.error("播放失败：" + error.getErrorCodeName() + "，可重试或切换线路"); }
            }
        });
        MediaItem.Builder media = new MediaItem.Builder().setUri(source.url);
        media.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8); // Includes sniffed manifests without an extension.
        hlsPlayer.setMediaItem(media.build()); hlsPlayer.seekTo(Math.max(0, position));
        hlsPlayer.setPlayWhenReady(wantPlay); hlsPlayer.prepare();
    }
    private void fit(int width, int height) {
        if (width <= 0 || height <= 0) return;
        videoWidth=width;videoHeight=height;
        android.view.View parent=(android.view.View)surface.getParent();
        if(parent==null || parent.getWidth()<=0 || parent.getHeight()<=0)return;
        int[] size=displaySize(parent.getWidth(),parent.getHeight(),width,height,aspectMode);
        android.view.ViewGroup.LayoutParams old=surface.getLayoutParams();
        if(old.width!=size[0] || old.height!=size[1])surface.setLayoutParams(new android.widget.FrameLayout.LayoutParams(size[0],size[1],android.view.Gravity.CENTER));
    }
    static int[] displaySize(int screenWidth,int screenHeight,int width,int height,int mode) {
        if(mode==2)return new int[]{screenWidth,screenHeight};
        float x=(float)screenWidth/width,y=(float)screenHeight/height;
        float scale=mode==1?Math.max(x,y):Math.min(x,y);
        return new int[]{Math.round(width*scale),Math.round(height*scale)};
    }
    float speed(){return speed;}
    int aspectMode(){return aspectMode;}
    void setAspectMode(int mode){aspectMode=mode;new Library(activity).prefs.edit().putInt("video-aspect",mode).apply();fit(videoWidth,videoHeight);}
    void setSpeed(float value){
        speed=value;new Library(activity).prefs.edit().putFloat("playback-speed",speed).apply();
        if(hlsPlayer!=null)hlsPlayer.setPlaybackSpeed(speed);
        else if(nativePlayer!=null && ready && playing())applyNativeSpeed();
    }
    private boolean applyNativeSpeed(){
        if(speed==nativeSpeed)return true;
        try{nativePlayer.setPlaybackParams(new PlaybackParams().setPitch(1f).setSpeed(speed));nativeSpeed=speed;}
        catch(IllegalArgumentException unsupported){
            speed=nativeSpeed;new Library(activity).prefs.edit().putFloat("playback-speed",speed).apply();
            android.widget.Toast.makeText(activity,"设备不支持此倍速，已保留原速度",android.widget.Toast.LENGTH_SHORT).show();
        }
        catch(IllegalStateException invalidState){
            ready=false;wantPlay=false;
            listener.error("播放器状态已失效，请重试或切换线路");return false;
        }
        return true;
    }
    boolean prepared() { return ready; }
    boolean wantsPlay() { return wantPlay; }
    String engineName() { return hlsPlayer != null ? "Media3" : "NuPlayer"; }
    boolean videoRendered() { return rendered; }
    int audioTracks() {
        if (nativePlayer != null && ready) {
            int count = 0;
            for (MediaPlayer.TrackInfo info : nativePlayer.getTrackInfo()) if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) count++;
            return count;
        }
        if (hlsPlayer != null) {
            int count = 0;
            for (androidx.media3.common.Tracks.Group group : hlsPlayer.getCurrentTracks().getGroups())
                if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) count += group.length;
            return count;
        }
        return 0;
    }
    boolean playing() {
        if (hlsPlayer != null) return hlsPlayer.isPlaying();
        try { return ready && nativePlayer != null && nativePlayer.isPlaying(); } catch (IllegalStateException e) { return false; }
    }
    long position() {
        if (hlsPlayer != null) return Math.max(0, hlsPlayer.getCurrentPosition());
        try { return ready && nativePlayer != null ? Math.max(0, nativePlayer.getCurrentPosition()) : 0; } catch (IllegalStateException e) { return 0; }
    }
    long duration() {
        if (hlsPlayer != null) return Math.max(0, hlsPlayer.getDuration());
        try { return ready && nativePlayer != null ? Math.max(0, nativePlayer.getDuration()) : 0; } catch (IllegalStateException e) { return 0; }
    }
    void play() {
        wantPlay = true;
        if (hlsPlayer != null) hlsPlayer.play();
        else if (nativePlayer != null && ready) {
            focusRequested=true;
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (hasFocus && applyNativeSpeed()) nativePlayer.start();
        }
        listener.changed();
    }
    void pause() {
        wantPlay = false; resumeAfterFocus = false;
        if (hlsPlayer != null) hlsPlayer.pause();
        else if (nativePlayer != null && ready) nativePlayer.pause();
        listener.changed();
    }
    void seek(long position) {
        long target = Math.max(0, duration() > 0 ? Math.min(duration() - 500, position) : position);
        if (hlsPlayer != null) hlsPlayer.seekTo(target);
        else if (nativePlayer != null && ready) nativePlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST_SYNC);
        listener.changed();
    }
    void stop() {
        generation++; ready = false; rendered = false;nativeSpeed=1f;
        if (data != null) { data.close(); data = null; }
        if (preparing != null) { preparing.cancel(true); preparing = null; }
        if (nativePlayer != null) { nativePlayer.release(); nativePlayer = null; }
        if (hlsPlayer != null) { hlsPlayer.release(); hlsPlayer = null; }
        if (focusRequested) {focusRequested=false;audio.abandonAudioFocusRequest(focus);}
        hasFocus = false; resumeAfterFocus = false;
    }
    @Override public void close() { closed=true;stop(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
