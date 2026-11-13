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
    @Implements(value=NativePlayback.class,isInAndroidSdk=false) public static class Engine {
        long position=300000;float speed=1;int aspect;boolean playing;
        @Implementation protected void open(PlaySource source,long position,boolean play){playing=play;}
        @Implementation protected boolean prepared(){return true;}
        @Implementation protected boolean playing(){return playing;}
        @Implementation protected long position(){return position;}
        @Implementation protected long duration(){return 1440000;}
        @Implementation protected void seek(long target){position=target;}
        @Implementation protected void play(){playing=true;}
        @Implementation protected void pause(){playing=false;}
        @Implementation protected float speed(){return speed;}
        @Implementation protected int aspectMode(){return aspect;}
        @Implementation protected void setSpeed(float value){speed=value;}
        @Implementation protected void setAspectMode(int mode){aspect=mode;}
    }
    private ActivityController<PlayerActivity> player(){return Robolectric.buildActivity(PlayerActivity.class,new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("directUrl","http://127.0.0.1:1/video")).setup().visible();}
    private void key(PlayerActivity player,int code){player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void layout(AlertDialog dialog){
        View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void optionsStartAtCurrentSpeedAndRememberCursorAfterCancelAndSelection()throws Exception{
        try(ActivityController<PlayerActivity> controller=player()){
            PlayerActivity p=controller.get();NativePlayback engine=(NativePlayback)RefreshRaceTest.field(p,"engine");engine.setSpeed(1.5f);
            key(p,KeyEvent.KEYCODE_MENU);AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);
            assertEquals(2,options.getListView().getSelectedItemPosition());
            for(int i=0;i<3;i++){key(p,KeyEvent.KEYCODE_DPAD_DOWN);layout(options);}
            assertEquals(5,options.getListView().getSelectedItemPosition());key(p,KeyEvent.KEYCODE_BACK);
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);assertEquals(5,options.getListView().getSelectedItemPosition());
            options.getListView().performItemClick(null,6,6);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,engine.aspectMode());
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");layout(options);assertEquals(6,options.getListView().getSelectedItemPosition());assertEquals(1.5f,engine.speed(),0);
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
            AlertDialog options=(AlertDialog)RefreshRaceTest.field(p,"dialog");options.getListView().performItemClick(null,2,2);Shadows.shadowOf(Looper.getMainLooper()).idle();
            NativePlayback engine=(NativePlayback)RefreshRaceTest.field(p,"engine");assertEquals(1.5f,engine.speed(),0);assertFalse(engine.playing());assertEquals(false,RefreshRaceTest.field(p,"desiredPlaying"));
            key(p,KeyEvent.KEYCODE_MENU);options=(AlertDialog)RefreshRaceTest.field(p,"dialog");options.getListView().performItemClick(null,5,5);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,engine.aspectMode());assertFalse(engine.playing());
            key(p,KeyEvent.KEYCODE_DPAD_CENTER);assertTrue(engine.playing());
        }
    }
}
