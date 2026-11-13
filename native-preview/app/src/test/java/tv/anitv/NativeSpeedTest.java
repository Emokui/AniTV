package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.view.SurfaceView;
import java.lang.reflect.Field;
import java.util.Collections;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,shadows=NativeSpeedTest.Parameters.class)
public class NativeSpeedTest {
    @Implements(MediaPlayer.class) public static class Parameters extends ShadowMediaPlayer {
        @RealObject MediaPlayer player;float applied=1f;int writes,starts;boolean invalidState;
        @Implementation protected void start(){starts++;super.start();}
        @Implementation protected void setPlaybackParams(PlaybackParams params){
            writes++;
            if(invalidState)throw new IllegalStateException("Released engine for test");
            if(params.getSpeed()==2f)throw new IllegalArgumentException("Unsupported rate for test codec");
            applied=params.getSpeed();player.start(); // Android documents that a positive speed starts playback.
        }
    }
    private void set(NativePlayback engine,String name,Object value)throws Exception{Field f=NativePlayback.class.getDeclaredField(name);f.setAccessible(true);f.set(engine,value);}
    private Object get(NativePlayback engine,String name)throws Exception{Field f=NativePlayback.class.getDeclaredField(name);f.setAccessible(true);return f.get(engine);}
    private int errors;private String lastError;
    private NativePlayback engine(Activity activity){return new NativePlayback(activity,new SurfaceView(activity),new OkHttpClient(),new NativePlayback.Listener(){public void ready(){}public void buffering(boolean b){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){errors++;lastError=m;}public void changed(){}});}
    @Test public void invalidSpeedStateReportsErrorAndDoesNotContinueToStartOrRetry()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            MediaPlayer player=new MediaPlayer();Parameters params=org.robolectric.shadow.api.Shadow.extract(player);
            params.setState(ShadowMediaPlayer.State.PAUSED);params.invalidState=true;
            set(engine,"nativePlayer",player);set(engine,"ready",true);
            engine.setSpeed(1.5f);engine.play();
            assertEquals(1,params.writes);assertEquals(0,params.starts);assertFalse(engine.prepared());assertFalse(engine.wantsPlay());
            assertEquals(1,errors);assertEquals("播放器状态已失效，请重试或切换线路",lastError);
            engine.play();assertEquals(1,params.writes);assertEquals(0,params.starts);assertEquals(1,errors);
        }
    }
    @Test public void nativeSpeedAppliesOnResumeAndRejectedRateKeepsThePriorRate()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            String url="http://test.local/speed.mp4";
            ShadowMediaPlayer.addMediaInfo(org.robolectric.shadows.util.DataSource.toDataSource(url),new ShadowMediaPlayer.MediaInfo(1440000,0));
            MediaPlayer player=new MediaPlayer();player.setDataSource(url);
            Parameters params=org.robolectric.shadow.api.Shadow.extract(player);params.setState(ShadowMediaPlayer.State.PAUSED);
            set(engine,"nativePlayer",player);set(engine,"ready",true);
            engine.setSpeed(1.5f);assertEquals(0,params.writes);assertFalse(player.isPlaying());
            engine.play();assertEquals(1.5f,params.applied,0);assertTrue(player.isPlaying());assertEquals(1,params.writes);
            engine.pause();engine.setSpeed(1.25f);assertFalse(player.isPlaying());assertEquals(1,params.writes);
            engine.play();assertEquals(1.25f,params.applied,0);assertTrue(player.isPlaying());assertEquals(2,params.writes);
            engine.setSpeed(2f);assertEquals(1.25f,engine.speed(),0);assertEquals(1.25f,params.applied,0);assertTrue(player.isPlaying());
            assertEquals("设备不支持此倍速，已保留原速度",ShadowToast.getTextOfLatestToast());
            engine.play();assertEquals("A rejected speed must not be retried on each play",3,params.writes);
        }
    }
    @Test public void hlsUsesSameSpeedPreferenceAndDoesNotUnpauseWhenChanged()throws Exception{
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();NativePlayback engine=engine(controller.get())){
            engine.setSpeed(1.25f);engine.open(new PlaySource("http://127.0.0.1:1/test.m3u8","","",Collections.emptyMap()),0,false);
            androidx.media3.exoplayer.ExoPlayer player=(androidx.media3.exoplayer.ExoPlayer)get(engine,"hlsPlayer");
            assertEquals(1.25f,player.getPlaybackParameters().speed,0);assertFalse(player.getPlayWhenReady());
            engine.setSpeed(2f);assertEquals(2f,player.getPlaybackParameters().speed,0);assertFalse(player.getPlayWhenReady());
        }
    }
}
