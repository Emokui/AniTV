package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Looper;
import android.view.SurfaceView;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class)
public class ExoOnlyPlaybackTest {
    private NativePlayback engine(Activity activity){return new NativePlayback(activity,new SurfaceView(activity),new OkHttpClient(),new NativePlayback.Listener(){public void ready(){}public void buffering(boolean b){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){}public void changed(){}});}
    private ExoPlayer player(NativePlayback engine)throws Exception{Field field=NativePlayback.class.getDeclaredField("player");field.setAccessible(true);return (ExoPlayer)field.get(engine);}
    @Test public void mp4ResumeAndSpeedKeepSamePlayerAndPosition()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            engine.open(new PlaySource("http://127.0.0.1:1/movie.mp4","","",Collections.emptyMap()),60000,false);
            ExoPlayer player=player(engine);assertNotNull(player);assertEquals("Media3",engine.engineName());
            assertNull(player.getCurrentMediaItem().localConfiguration.mimeType);
            assertEquals(60000,engine.position());assertFalse(player.getPlayWhenReady());
            engine.setSpeed(1.5f);assertFalse(player.getPlayWhenReady());assertEquals(1.5f,player.getPlaybackParameters().speed,0);
            engine.play();assertTrue(player.getPlayWhenReady());engine.pause();assertFalse(player.getPlayWhenReady());engine.play();
            assertSame(player,player(engine));assertEquals(60000,engine.position());
            engine.stop();assertNull(player(engine));assertFalse(engine.wantsPlay());
        }
    }
    @Test public void typedHlsUsesSameExoPath()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            engine.open(new PlaySource("http://127.0.0.1:1/video","","hls",Collections.emptyMap()),0,false);
            ExoPlayer player=player(engine);assertNotNull(player);
            assertEquals("application/x-mpegURL",player.getCurrentMediaItem().localConfiguration.mimeType);
            engine.pause();engine.play();assertSame(player,player(engine));
        }
    }
    @Test public void extensionlessHlsIsDetectedWithSmallRangeAndProviderHeaders()throws Exception{
        try(MockWebServer server=new MockWebServer();ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            server.enqueue(new MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-ENDLIST\n"));
            server.enqueue(new MockResponse().setResponseCode(404));
            engine.open(new PlaySource(server.url("/signed-video").toString(),"","",Collections.singletonMap("Referer","https://provider.example/")),0,false);
            RecordedRequest request=server.takeRequest(3,TimeUnit.SECONDS);assertNotNull(request);
            assertEquals("bytes=0-127",request.getHeader("Range"));assertEquals("https://provider.example/",request.getHeader("Referer"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(player(engine)==null && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertNotNull(player(engine));assertEquals("application/x-mpegURL",player(engine).getCurrentMediaItem().localConfiguration.mimeType);
        }
    }
    @Test public void closingDuringProbeCannotCreatePlayerLater()throws Exception{
        try(MockWebServer server=new MockWebServer();ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            server.enqueue(new MockResponse().setHeadersDelay(200,TimeUnit.MILLISECONDS).setBody("#EXTM3U\n"));
            engine.open(new PlaySource(server.url("/signed-video").toString(),"","",Collections.emptyMap()),0,true);
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));engine.close();Thread.sleep(250);
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(player(engine));assertFalse(engine.wantsPlay());
        }
    }
}
