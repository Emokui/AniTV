package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,instrumentedPackages={"tv.anitv"},shadows=PlaybackUxTest.Engine.class)
public class PlaybackUxTest {
    @org.junit.Before public void disablePlatformAnimationsForTimingAssertions(){
        org.robolectric.util.ReflectionHelpers.callStaticMethod(android.animation.ValueAnimator.class,"setDurationScale",org.robolectric.util.ReflectionHelpers.ClassParameter.from(float.class,0f));
    }

    @Implements(value=NativePlayback.class,isInAndroidSdk=false) public static class Engine {
        long position=300000;float speed=1;int aspect,playCalls,openCalls;boolean playing;
        @Implementation protected void open(PlaySource source,long position,boolean play){playing=play;openCalls++;}
        @Implementation protected boolean prepared(){return true;}
        @Implementation protected boolean playing(){return playing;}
        @Implementation protected long position(){return position;}
        @Implementation protected long duration(){return 1440000;}
        @Implementation protected void seek(long target){position=target;}
        @Implementation protected void play(){playing=true;playCalls++;}
        @Implementation protected void pause(){playing=false;}
        @Implementation protected float speed(){return speed;}
        @Implementation protected int aspectMode(){return aspect;}
        @Implementation protected void setSpeed(float value){speed=value;}
        @Implementation protected void setAspectMode(int mode){aspect=mode;}
    }
    private ActivityController<PlayerActivity> player(){return Robolectric.buildActivity(PlayerActivity.class,new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("directUrl","http://127.0.0.1:1/video")).setup().visible();}
    private void key(PlayerActivity player,int code){player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private View option(AlertDialog dialog,int index){return dialog.getWindow().getDecorView().findViewWithTag("playback-option-"+index);}
    private void layout(AlertDialog dialog){
        View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void optionsStartAtCurrentSpeedAndRememberCursorAfterCancelAndSelection()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();NativePlayback engine=(NativePlayback)RefreshRaceTest.field(p,"engine");engine.setSpeed(1.5f);
            key(p,KeyEvent.KEYCODE_MENU);AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);
            assertTrue(option(options,2).hasFocus());
            key(p,KeyEvent.KEYCODE_DPAD_DOWN);key(p,KeyEvent.KEYCODE_DPAD_RIGHT);layout(options);
            assertTrue(option(options,5).hasFocus());key(p,KeyEvent.KEYCODE_BACK);
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);assertTrue(option(options,5).hasFocus());
            option(options,6).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,engine.aspectMode());
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);assertTrue(option(options,6).hasFocus());assertEquals(1.5f,engine.speed(),0);
        }
    }
    @Test public void seekShowsTargetWithoutControlsAndOneOverlayExpiresAfterLastPress()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();TextView overlay=(TextView)RefreshRaceTest.field(p,"feedback");View controls=(View)RefreshRaceTest.field(p,"controls");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));key(p,KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(View.GONE,controls.getVisibility());assertEquals(View.VISIBLE,overlay.getVisibility());assertEquals("快进 10 秒　05:10 / 24:00",overlay.getText().toString());
            p.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,1));
            assertEquals("Throttled held events must not hide the overlay",View.VISIBLE,overlay.getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));key(p,KeyEvent.KEYCODE_DPAD_LEFT);
            assertSame(overlay,RefreshRaceTest.field(p,"feedback"));assertEquals("快退 10 秒　05:00 / 24:00",overlay.getText().toString());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499));assertEquals(View.VISIBLE,overlay.getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(View.GONE,overlay.getVisibility());
        }
    }
    @Test public void firstBackExplainsExitButClosingOptionsDoesNotArmIt()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();key(p,KeyEvent.KEYCODE_MENU);AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");assertTrue(options.isShowing());
            key(p,KeyEvent.KEYCODE_BACK);assertFalse(options.isShowing());assertFalse(p.isFinishing());assertEquals(false,RefreshRaceTest.field(p,"backExitArmed"));
            TextView overlay=(TextView)RefreshRaceTest.field(p,"feedback");assertEquals(View.GONE,overlay.getVisibility());
            key(p,KeyEvent.KEYCODE_BACK);assertEquals("5 秒内再次返回退出播放",overlay.getText().toString());assertEquals(View.VISIBLE,overlay.getVisibility());assertFalse(p.isFinishing());
            key(p,KeyEvent.KEYCODE_BACK);assertTrue(p.isFinishing());
        }
    }
    @Test public void choosingSpeedAndAspectPreservesPausedIntent()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();key(p,KeyEvent.KEYCODE_MEDIA_PAUSE);key(p,KeyEvent.KEYCODE_MENU);
            AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");option(options,2).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            NativePlayback engine=(NativePlayback)RefreshRaceTest.field(p,"engine");assertEquals(1.5f,engine.speed(),0);assertFalse(engine.playing());assertEquals(false,RefreshRaceTest.field(p,"desiredPlaying"));
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");option(options,5).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,engine.aspectMode());assertFalse(engine.playing());
            key(p,KeyEvent.KEYCODE_DPAD_CENTER);assertTrue(engine.playing());
        }
    }
    @Test public void repeatedFeedbackUpdatesTextWithoutResettingVisibleAlpha()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();TextView overlay=(TextView)RefreshRaceTest.field(p,"feedback");
            java.lang.reflect.Method feedback=PlayerActivity.class.getDeclaredMethod("feedback",String.class,long.class);feedback.setAccessible(true);
            feedback.invoke(p,"第一次",1500L);overlay.setAlpha(.6f);feedback.invoke(p,"第二次",1500L);
            assertEquals("第二次",overlay.getText().toString());assertEquals(View.VISIBLE,overlay.getVisibility());assertEquals(.6f,overlay.getAlpha(),0);
        }
    }

    @Test public void menuCanSwitchPreviousAndNextEpisodeWithoutLeavingAnOpenDialog()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();java.lang.reflect.Field values=PlayerActivity.class.getDeclaredField("episodes");values.setAccessible(true);values.set(p,java.util.Arrays.asList(new Episode(1,"一"),new Episode(2,"二"),new Episode(3,"三")));
            java.lang.reflect.Field number=PlayerActivity.class.getDeclaredField("number");number.setAccessible(true);number.setInt(p,2);
            key(p,KeyEvent.KEYCODE_MENU);AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");for(int i=0;i<9;i++)assertNotNull(option(options,i));option(options,7).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,number.getInt(p));assertFalse(options.isShowing());assertEquals(false,RefreshRaceTest.field(p,"dialogOpen"));assertEquals(true,RefreshRaceTest.field(p,"desiredPlaying"));
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");option(options,8).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,number.getInt(p));
        }
    }
    @Test public void endedAndFailedMenusNeverResumeOnCancelOrMissingNextEpisode()throws Exception{
        for(boolean ended:new boolean[]{false,true})for(int menu:new int[]{KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU}){
            try(ActivityController<PlayerActivity> controller=player()){
                PlayerActivity p=controller.get();Engine engine=org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p,"engine"));
                if(ended)p.ended();else p.error("失败");int before=engine.playCalls;
                key(p,menu);AlertDialog drawer=(AlertDialog)RefreshRaceTest.field(p,"dialog");assertNotNull(drawer);key(p,KeyEvent.KEYCODE_BACK);
                assertEquals(before,engine.playCalls);assertFalse(engine.playing);assertFalse(p.isFinishing());
            }
        }
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();Engine engine=org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p,"engine"));key(p,KeyEvent.KEYCODE_MENU);int before=engine.playCalls;p.error("菜单打开期间失败");key(p,KeyEvent.KEYCODE_BACK);assertEquals(before,engine.playCalls);
        }
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();Engine engine=org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p,"engine"));p.ended();int before=engine.playCalls;key(p,KeyEvent.KEYCODE_MENU);
            AlertDialog menu=(AlertDialog)RefreshRaceTest.field(p,"dialog");option(menu,8).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,engine.playCalls);assertFalse(engine.playing);
        }
    }
    @Test public void playbackEpisodeCardLabelsCurrentEpisode()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();key(p,KeyEvent.KEYCODE_DPAD_UP);AlertDialog drawer=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(drawer);
            androidx.recyclerview.widget.RecyclerView grid=drawer.getWindow().getDecorView().findViewWithTag("episode-grid");
            Object holder=grid.findViewHolderForAdapterPosition(0);assertNotNull(holder);TextView number=(TextView)RefreshRaceTest.field(holder,"number");assertTrue(number.getText().toString().contains("当前播放"));
        }
    }
    @Test public void dedicatedPlayKeyRetriesFailuresRestartsEndedVideoAndNeverTogglesToPause()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();Engine engine=org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p,"engine"));
            java.lang.reflect.Field ready=PlayerActivity.class.getDeclaredField("surfaceReady");ready.setAccessible(true);ready.setBoolean(p,true);
            p.error("失败");int opens=engine.openCalls;key(p,KeyEvent.KEYCODE_MEDIA_PLAY);assertEquals(false,RefreshRaceTest.field(p,"failed"));assertEquals(opens+1,engine.openCalls);assertTrue(engine.playing);
            p.ended();engine.position=1440000;key(p,KeyEvent.KEYCODE_MEDIA_PLAY);assertEquals(false,RefreshRaceTest.field(p,"finished"));assertEquals(0,engine.position);assertTrue(engine.playing);
            key(p,KeyEvent.KEYCODE_MEDIA_PLAY);assertTrue(engine.playing);assertEquals(true,RefreshRaceTest.field(p,"desiredPlaying"));
        }
    }
}
