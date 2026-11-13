package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import java.lang.reflect.Field;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, application=AniApp.class)
public class PlayerBackTest {
    @Test public void pausedPreparedPlayerDoesNotKeepLoadingSpinnerVisible()throws Exception{
        try(ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")){
            PlayerActivity player=controller.get();key(player,KeyEvent.KEYCODE_MEDIA_PAUSE);player.buffering(true);player.ready();
            assertEquals(View.GONE,((View)field(player,"loading")).getVisibility());assertEquals("",((android.widget.TextView)field(player,"status")).getText().toString());
        }
    }
    private ActivityController<PlayerActivity> player(String url) {
        return Robolectric.buildActivity(PlayerActivity.class,new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class)
                .putExtra("directUrl",url).putExtra("title","返回行为")).create().start().resume();
    }
    private Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private void key(PlayerActivity player,int code) {
        player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));
        player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void cancelSourceDrawerConsumesBackAndRestoresPlaybackIntent() throws Exception {
        try (MockWebServer server=new MockWebServer(); ActivityController<PlayerActivity> controller=player(server.url("/video").toString())) {
            server.enqueue(new MockResponse().setBody("v"));
            PlayerActivity player=controller.get(); NativePlayback engine=(NativePlayback)field(player,"engine");
            key(player,KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog drawer=(AlertDialog)field(player,"dialog");assertTrue(drawer.isShowing());
            assertFalse(engine.wantsPlay());
            drawer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
            assertTrue(drawer.isShowing());
            drawer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(drawer.isShowing());assertFalse(player.isFinishing());assertTrue(engine.wantsPlay());
            assertEquals(0,field(player,"sourceIndex"));
            // Some TV firmware forwards an orphan key release after closing a window.
            player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
            assertFalse(player.isFinishing());assertEquals(View.GONE,((View)field(player,"controls")).getVisibility());
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            assertEquals(View.GONE,((View)field(player,"controls")).getVisibility());
            key(player,KeyEvent.KEYCODE_BACK);assertTrue(player.isFinishing());
        }
    }
    @Test public void cancelDrawerPreservesPauseAndOtherInputResetsExit() throws Exception {
        try (MockWebServer server=new MockWebServer(); ActivityController<PlayerActivity> controller=player(server.url("/video").toString())) {
            server.enqueue(new MockResponse().setBody("v"));PlayerActivity player=controller.get();
            key(player,KeyEvent.KEYCODE_MEDIA_PAUSE);key(player,KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog drawer=(AlertDialog)field(player,"dialog");
            drawer.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(player.isFinishing());assertFalse(((NativePlayback)field(player,"engine")).wantsPlay());
            assertEquals(Boolean.FALSE,field(player,"desiredPlaying"));
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            key(player,KeyEvent.KEYCODE_DPAD_RIGHT);
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            key(player,KeyEvent.KEYCODE_BACK);assertTrue(player.isFinishing());
        }
    }
    @Test public void hiddenControlsStillRequireTwoPressesAndLongPressCountsOnce() throws Exception {
        try (ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();((View)field(player,"controls")).setVisibility(View.GONE);
            player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
            for(int i=1;i<5;i++)player.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,i));
            player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
            assertFalse(player.isFinishing());
            key(player,KeyEvent.KEYCODE_BACK);assertTrue(player.isFinishing());
        }
    }
    @Test public void activityRoutedRemoteMovesAndConfirmsInOneDrawer() throws Exception {
        try (MockWebServer server=new MockWebServer(); ActivityController<PlayerActivity> controller=player(server.url("/a").toString())) {
            server.enqueue(new MockResponse().setBody("v"));server.enqueue(new MockResponse().setBody("v"));
            PlayerActivity player=controller.get();
            Field sources=PlayerActivity.class.getDeclaredField("sources");sources.setAccessible(true);
            sources.set(player,java.util.Arrays.asList(
                    new PlaySource(server.url("/a").toString(),"线路 A","",java.util.Collections.emptyMap()),
                    new PlaySource(server.url("/b").toString(),"线路 B","",java.util.Collections.emptyMap())));
            key(player,KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog drawer=(AlertDialog)field(player,"dialog");
            drawer.getWindow().getDecorView().measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));
            drawer.getWindow().getDecorView().layout(0,0,480,1080);
            key(player,KeyEvent.KEYCODE_DPAD_DOWN);
            assertSame("Down must not stack another drawer",drawer,field(player,"dialog"));
            assertEquals(1,drawer.getListView().getSelectedItemPosition());
            key(player,KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(1,field(player,"sourceIndex"));assertFalse(drawer.isShowing());assertFalse(player.isFinishing());
            key(player,KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog reopened=(AlertDialog)field(player,"dialog");assertTrue(reopened.isShowing());
            key(player,KeyEvent.KEYCODE_BACK);
            assertFalse(reopened.isShowing());assertFalse(player.isFinishing());
            assertTrue(((NativePlayback)field(player,"engine")).wantsPlay());
        }
    }

    @Test public void secondBackWithinFiveSecondsExits() throws Exception {
        try (ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5000));
            key(player,KeyEvent.KEYCODE_BACK);assertTrue(player.isFinishing());
        }
    }
    @Test public void backAfterFiveSecondsStartsNewExitWindow() throws Exception {
        try (ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5001));
            key(player,KeyEvent.KEYCODE_BACK);assertFalse(player.isFinishing());
            key(player,KeyEvent.KEYCODE_BACK);assertTrue(player.isFinishing());
        }
    }

    @Test public void progressOnlyShowsWhenPausedAndStaysHiddenAfterPlaybackActions() throws Exception {
        try(ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();View controls=(View)field(player,"controls");
            assertEquals(View.GONE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(View.GONE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(View.VISIBLE,controls.getVisibility());
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(View.VISIBLE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_DPAD_LEFT);assertEquals(View.VISIBLE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(View.GONE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_MEDIA_PLAY);assertEquals(View.GONE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_MEDIA_PAUSE);assertEquals(View.VISIBLE,controls.getVisibility());
            key(player,KeyEvent.KEYCODE_BACK);assertEquals(View.GONE,controls.getVisibility());assertFalse(player.isFinishing());
        }
    }

    @Test public void hiddenControlsDoNotRebindTextAndPausedTextOnlyChangesOnce() throws Exception {
        try(ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();android.widget.TextView heading=(android.widget.TextView)field(player,"heading");
            java.util.concurrent.atomic.AtomicInteger changes=new java.util.concurrent.atomic.AtomicInteger();
            heading.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){changes.incrementAndGet();}
                public void afterTextChanged(android.text.Editable value){}
            });
            for(int i=0;i<100;i++)player.changed();assertEquals(0,changes.get());
            key(player,KeyEvent.KEYCODE_MEDIA_PAUSE);int afterPause=changes.get();
            for(int i=0;i<100;i++)player.changed();assertEquals(afterPause,changes.get());
        }
    }

    @Test public void heldSeekAcceleratesAndNewPressReturnsToTenSeconds() {
        assertEquals(10000,PlayerActivity.seekStepMillis(new KeyEvent(100,100,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0)));
        assertEquals(10000,PlayerActivity.seekStepMillis(new KeyEvent(100,1099,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,1)));
        assertEquals(30000,PlayerActivity.seekStepMillis(new KeyEvent(100,1100,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,2)));
        assertEquals(60000,PlayerActivity.seekStepMillis(new KeyEvent(100,3100,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,10)));
        assertEquals(10000,PlayerActivity.seekStepMillis(new KeyEvent(4000,4000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,0)));
        assertEquals(60000,PlayerActivity.seekStepMillis(new KeyEvent(100,5100,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,20)));
    }

    @Test public void reopeningSourceResetsPendingSeekAndHiddenTickUsesFiveSeconds() throws Exception {
        try(ActivityController<PlayerActivity> controller=player("http://127.0.0.1:1/video")) {
            PlayerActivity player=controller.get();
            Field pending=PlayerActivity.class.getDeclaredField("pendingSeek");pending.setAccessible(true);pending.setLong(player,90000);
            Field surface=PlayerActivity.class.getDeclaredField("surfaceReady");surface.setAccessible(true);surface.setBoolean(player,true);
            java.lang.reflect.Method prepare=PlayerActivity.class.getDeclaredMethod("prepare");prepare.setAccessible(true);prepare.invoke(player);
            assertEquals(-1L,pending.getLong(player));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            long delay=Shadows.shadowOf(Looper.getMainLooper()).getNextScheduledTaskTime().toMillis()
                    - android.os.SystemClock.uptimeMillis();
            assertEquals(5000L,delay);
            key(player,KeyEvent.KEYCODE_MEDIA_PAUSE);
            delay=Shadows.shadowOf(Looper.getMainLooper()).getNextScheduledTaskTime().toMillis()
                    - android.os.SystemClock.uptimeMillis();
            assertEquals(500L,delay);
        }
    }

}
