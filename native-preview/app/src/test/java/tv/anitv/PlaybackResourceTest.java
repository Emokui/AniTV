package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.view.SurfaceView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class PlaybackResourceTest {
    @Test public void fitCropAndStretchPreserveTheirIntendedGeometry(){
        assertArrayEquals(new int[]{1440,1080},NativePlayback.displaySize(1920,1080,640,480,0));
        assertArrayEquals(new int[]{1920,1440},NativePlayback.displaySize(1920,1080,640,480,1));
        assertArrayEquals(new int[]{1920,1080},NativePlayback.displaySize(1920,1080,640,480,2));
        assertArrayEquals(new int[]{1920,1080},NativePlayback.displaySize(1920,1080,1920,1080,0));
    }
    @Test public void changingPausedNativeSpeedDoesNotStartPlaybackAndSettingsSurviveNewEngine()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();NativePlayback.Listener listener=new NativePlayback.Listener(){public void ready(){}public void buffering(boolean b){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){}public void changed(){}};
            try(NativePlayback engine=new NativePlayback(activity,new SurfaceView(activity),new OkHttpClient(),listener)){
                android.media.MediaPlayer player=new android.media.MediaPlayer();
                org.robolectric.shadows.ShadowMediaPlayer shadow=Shadows.shadowOf(player);shadow.setState(org.robolectric.shadows.ShadowMediaPlayer.State.PAUSED);
                field("nativePlayer").set(engine,player);field("ready").setBoolean(engine,true);
                engine.setSpeed(1.5f);engine.setAspectMode(1);
                assertEquals(org.robolectric.shadows.ShadowMediaPlayer.State.PAUSED,shadow.getState());assertFalse(engine.playing());assertEquals(1.5f,engine.speed(),0);
            }
            try(NativePlayback reopened=new NativePlayback(activity,new SurfaceView(activity),new OkHttpClient(),listener)){
                assertEquals(1.5f,reopened.speed(),0);assertEquals(1,reopened.aspectMode());
            }
        }
    }
    private Field field(String name)throws Exception{Field field=NativePlayback.class.getDeclaredField(name);field.setAccessible(true);return field;}
    @Test public void transientAudioFocusLossStillAbandonsRequestAndCloseRejectsLateCallback()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity activity=controller.get();AudioManager audio=(AudioManager)activity.getSystemService(android.content.Context.AUDIO_SERVICE);AtomicInteger changes=new AtomicInteger();
            NativePlayback engine=new NativePlayback(activity,new SurfaceView(activity),new OkHttpClient(),new NativePlayback.Listener(){public void ready(){}public void buffering(boolean b){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){}public void changed(){changes.incrementAndGet();}});
            AudioFocusRequest focus=(AudioFocusRequest)field("focus").get(engine);assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(focus));
            field("hasFocus").setBoolean(engine,true);field("focusRequested").setBoolean(engine,true);
            java.lang.reflect.Method getter=AudioFocusRequest.class.getDeclaredMethod("getOnAudioFocusChangeListener");getter.setAccessible(true);AudioManager.OnAudioFocusChangeListener listener=(AudioManager.OnAudioFocusChangeListener)getter.invoke(focus);
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);assertFalse(field("hasFocus").getBoolean(engine));
            engine.stop();assertSame(focus,Shadows.shadowOf(audio).getLastAbandonedAudioFocusRequest());int before=changes.get();
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);assertEquals(before,changes.get());assertFalse(field("hasFocus").getBoolean(engine));
            engine.close();listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);assertEquals(before,changes.get());
        }
    }
}
