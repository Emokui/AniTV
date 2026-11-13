package tv.anitv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One ExoPlayer path for both progressive media and HLS. */
@OptIn(markerClass = UnstableApi.class)
final class NativePlayback implements AutoCloseable {
    interface Listener {
        void ready(); void buffering(boolean buffering); void firstFrame(int width,int height);
        void ended(); void error(String message); void changed();
    }
    private final Activity activity;
    private final SurfaceView surface;
    private final OkHttpClient http;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=WorkPools.fixed(1);
    private ExoPlayer player;
    private Future<?> probing;
    private volatile Call probeCall;
    private boolean ready,rendered,wantPlay,closed;
    private int generation,aspectMode,videoWidth,videoHeight;
    private float speed;
    NativePlayback(Activity activity,SurfaceView surface,OkHttpClient http,Listener listener){
        this.activity=activity;this.surface=surface;this.http=http;this.listener=listener;
        android.content.SharedPreferences prefs=new Library(activity).prefs;
        speed=prefs.getFloat("playback-speed",1f);aspectMode=prefs.getInt("video-aspect",0);
    }
    void open(PlaySource source,long position,boolean play){
        if(closed)return;
        stop();final int token=generation;wantPlay=play;listener.buffering(true);
        if(source.hls()){openPlayer(source,position,token,true);return;}
        String path=okhttp3.HttpUrl.get(source.url).encodedPath().toLowerCase(Locale.ROOT);
        if(path.endsWith(".mp4") || source.type.toLowerCase(Locale.ROOT).contains("mp4")){
            openPlayer(source,position,token,false);return;
        }
        // Extensionless provider URLs need content detection, not a forced MP4/HLS MIME type.
        Request.Builder request=new Request.Builder().url(source.url);
        for(Map.Entry<String,String> header:source.headers.entrySet())request.header(header.getKey(),header.getValue());
        request.header("Range","bytes=0-127").header("Accept-Encoding","identity");
        final Call call=http.newCall(request.build());probeCall=call;
        probing=worker.submit(()->{
            try(Response response=call.execute()){
                if(!response.isSuccessful() || response.body()==null)throw new IOException("视频 HTTP "+response.code());
                okio.BufferedSource input=response.body().source();input.request(128);
                String prefix=new String(input.readByteArray(Math.min(128,input.getBuffer().size())),StandardCharsets.UTF_8);
                final boolean hls=prefix.replace("\uFEFF","").trim().startsWith("#EXTM3U");
                main.post(()->{if(token==generation && !closed){probeCall=null;openPlayer(source,position,token,hls);}});
            }catch(IOException error){
                main.post(()->{if(token==generation && !closed){probeCall=null;listener.buffering(false);listener.error("视频连接失败："+error.getMessage());}});
            }
        });
    }
    static DefaultHttpDataSource.Factory httpDataSource(PlaySource source){
        return new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(source.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000).setReadTimeoutMs(15000);
    }
    private void openPlayer(PlaySource source,long position,int token,boolean hls){
        final ExoPlayer opened=new ExoPlayer.Builder(activity)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSource(source))).build();
        player=opened;
        opened.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        opened.setHandleAudioBecomingNoisy(true);opened.setVideoSurfaceView(surface);
        opened.setSeekParameters(SeekParameters.CLOSEST_SYNC);opened.setPlaybackSpeed(speed);
        opened.addListener(new Player.Listener(){
            private boolean current(){return token==generation && player==opened;}
            @Override public void onPlaybackStateChanged(int state){
                if(!current())return;
                listener.buffering(state==Player.STATE_BUFFERING);
                if(state==Player.STATE_READY){ready=true;listener.ready();}
                if(state==Player.STATE_ENDED)listener.ended();
                listener.changed();
            }
            @Override public void onIsPlayingChanged(boolean playing){if(current())listener.changed();}
            @Override public void onRenderedFirstFrame(){
                if(!current())return;rendered=true;
                androidx.media3.common.VideoSize size=opened.getVideoSize();
                fit(Math.round(size.width*size.pixelWidthHeightRatio),size.height);
                listener.firstFrame(size.width,size.height);
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size){
                if(current())fit(Math.round(size.width*size.pixelWidthHeightRatio),size.height);
            }
            @Override public void onPlayerError(PlaybackException error){
                if(current()){ready=false;listener.buffering(false);listener.error("播放失败："+error.getErrorCodeName()+"，可重试或切换线路");}
            }
        });
        MediaItem.Builder media=new MediaItem.Builder().setUri(source.url);
        if(hls)media.setMimeType(MimeTypes.APPLICATION_M3U8);
        opened.setMediaItem(media.build());opened.seekTo(Math.max(0,position));
        opened.setPlayWhenReady(wantPlay);opened.prepare();
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
    void setSpeed(float value){speed=value;new Library(activity).prefs.edit().putFloat("playback-speed",speed).apply();if(player!=null)player.setPlaybackSpeed(speed);}
    boolean prepared(){return ready;}
    boolean wantsPlay(){return wantPlay;}
    String engineName(){return "Media3";}
    String videoResolution(){
        if(player==null)return "";
        androidx.media3.common.VideoSize size=player.getVideoSize();
        return size.width>0 && size.height>0?size.width+"×"+size.height:"";
    }
    boolean videoRendered(){return rendered;}
    int audioTracks(){
        int count=0;
        if(player!=null)for(androidx.media3.common.Tracks.Group group:player.getCurrentTracks().getGroups())
            if(group.getType()==C.TRACK_TYPE_AUDIO)count+=group.length;
        return count;
    }
    boolean playing(){return player!=null && player.isPlaying();}
    long position(){return player==null?0:Math.max(0,player.getCurrentPosition());}
    long duration(){return player==null?0:Math.max(0,player.getDuration());}
    void play(){wantPlay=true;if(player!=null)player.play();listener.changed();}
    void pause(){wantPlay=false;if(player!=null)player.pause();listener.changed();}
    void seek(long position){
        long length=duration();long target=Math.max(0,length>0?Math.min(length-500,position):position);
        if(player!=null)player.seekTo(target);listener.changed();
    }
    void stop(){
        generation++;ready=false;rendered=false;wantPlay=false;
        Call call=probeCall;probeCall=null;if(call!=null)call.cancel();
        if(probing!=null){probing.cancel(true);probing=null;}
        if(player!=null){ExoPlayer old=player;player=null;old.release();}
    }
    @Override public void close(){closed=true;stop();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
}
