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
        long position=300000;float speed=1;int aspect,playCalls,openCalls;boolean playing;PlaySource lastOpened;
        @Implementation protected void open(PlaySource source,long position,boolean play){lastOpened=source;playing=play;openCalls++;}
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
    @Implements(value=PlaybackApi.class,isInAndroidSdk=false) public static class SyncPlaybackApi {
        @Implementation protected PlaybackApi.Task episodes(long id,String title,PlaybackApi.Result<java.util.List<Episode>> result){
            result.success(java.util.Arrays.asList(new Episode(1,"一"),new Episode(2,"二")));
            return new PlaybackApi.Task();
        }
        @Implementation protected PlaybackApi.Task sources(long id,String[] titles,Episode episode,PlaybackApi.Result<java.util.List<PlaySource>> result){
            result.success(java.util.Collections.singletonList(
                    new PlaySource("http://127.0.0.1:1/sync-ep"+episode.number+".mp4","同步线路"+episode.number,"mp4",null)));
            return new PlaybackApi.Task();
        }
    }
    private ActivityController<PlayerActivity> player(){return Robolectric.buildActivity(PlayerActivity.class,new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("directUrl","http://127.0.0.1:1/video")).setup().visible();}
    private void key(PlayerActivity player,int code){player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private View option(AlertDialog dialog,int index){return dialog.getWindow().getDecorView().findViewWithTag("playback-option-"+index);}
    private void layout(AlertDialog dialog){
        View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void timeoutAdvancesOncePerSourceAndKeepsPosition() throws Exception {
        try (ActivityController<PlayerActivity> controller=player()){
            PlayerActivity player=controller.get();
            java.util.List<PlaySource> sources=(java.util.List<PlaySource>)RefreshRaceTest.field(player,"sources");
            sources.add(new PlaySource("https://video.example/second.mp4","第二线路","mp4",null));
            player.timedOut(125000);
            assertEquals(1, RefreshRaceTest.field(player,"sourceIndex"));
            assertEquals(125000L, RefreshRaceTest.field(player,"resumePosition"));
            player.timedOut(125000);
            assertEquals(true, RefreshRaceTest.field(player,"failed"));
            assertEquals(1, RefreshRaceTest.field(player,"sourceIndex"));
        }
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
            key(p,KeyEvent.KEYCODE_BACK);assertEquals("再按一次返回退出播放",overlay.getText().toString());assertEquals(View.VISIBLE,overlay.getVisibility());assertFalse(p.isFinishing());
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
            assertTrue(menu.isShowing());
        }
    }
    @Test public void aSourceThatBecomesReadyDuringSettingsWaitsForTheDrawerToClose() throws Exception {
        try (ActivityController<PlayerActivity> controller = player()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            java.lang.reflect.Field ready = PlayerActivity.class.getDeclaredField("surfaceReady");
            ready.setAccessible(true);
            ready.setBoolean(p, true);
            key(p, KeyEvent.KEYCODE_MENU);
            int opens = engine.openCalls;
            java.lang.reflect.Method prepare = PlayerActivity.class.getDeclaredMethod("prepare");
            prepare.setAccessible(true);
            prepare.invoke(p);
            assertFalse(engine.playing);
            assertEquals(opens, engine.openCalls);
            key(p, KeyEvent.KEYCODE_BACK);
            assertEquals(opens + 1, engine.openCalls);
            assertTrue(engine.playing);
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
    @Test public void rewindFromEndResumesAndConfirmPausesWithoutRestarting() throws Exception {
        try (ActivityController<PlayerActivity> controller = player()) {
            PlayerActivity player = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(player, "engine"));
            engine.position = 1440000;
            player.ended();
            assertEquals(0L, RefreshRaceTest.field(player, "resumePosition"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            key(player, KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(1430000, engine.position);
            assertTrue(engine.playing);
            key(player, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(1430000, engine.position);
            assertFalse(engine.playing);
        }
    }
    @Test public void timeoutDuringOpenSourcePickerUpdatesBadgeAndResetsSwitchesOnConfirm() throws Exception {
        try (ActivityController<PlayerActivity> controller = player()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            java.lang.reflect.Field ready = PlayerActivity.class.getDeclaredField("surfaceReady");
            ready.setAccessible(true);
            ready.setBoolean(p, true);
            java.util.List<PlaySource> sources = (java.util.List<PlaySource>) RefreshRaceTest.field(p, "sources");
            sources.add(new PlaySource("http://127.0.0.1:1/second.mp4", "第二线路", "mp4", null));
            sources.add(new PlaySource("http://127.0.0.1:1/third.mp4", "第三线路", "mp4", null));
            key(p, KeyEvent.KEYCODE_MEDIA_PAUSE);
            key(p, KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog drawer = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(drawer);
            assertEquals(0, drawer.getListView().getCheckedItemPosition());

            // Timeout advances sourceIndex from 0 -> 1 while SourcePicker is open.
            p.timedOut(88000);
            layout(drawer);
            assertEquals(1, RefreshRaceTest.field(p, "sourceIndex"));
            assertEquals(1, RefreshRaceTest.field(p, "timeoutSwitches"));
            assertEquals(1, drawer.getListView().getCheckedItemPosition());
            assertEquals(1, drawer.getListView().getSelectedItemPosition());

            // Pressing Confirm on the updated "使用中" row must NOT revert to timed-out source 0,
            // and MUST reset timeoutSwitches=0 and desiredPlaying=true.
            int opensBefore = engine.openCalls;
            key(p, KeyEvent.KEYCODE_DPAD_CENTER);
            assertFalse(drawer.isShowing());
            assertEquals(1, RefreshRaceTest.field(p, "sourceIndex"));
            assertEquals(0, RefreshRaceTest.field(p, "timeoutSwitches"));
            assertEquals(true, RefreshRaceTest.field(p, "desiredPlaying"));
            assertEquals(88000L, RefreshRaceTest.field(p, "resumePosition"));
            assertEquals(opensBefore + 1, engine.openCalls);
            assertTrue(engine.playing);

            // Exhausting all sources while SourcePicker is open clears active badge and stays failed on Back.
            key(p, KeyEvent.KEYCODE_DPAD_DOWN);
            drawer = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(drawer);
            p.timedOut(90000);
            p.timedOut(90000);
            p.timedOut(90000);
            layout(drawer);
            assertEquals(true, RefreshRaceTest.field(p, "failed"));
            assertEquals(android.widget.AdapterView.INVALID_POSITION, drawer.getListView().getCheckedItemPosition());
            int opensAfterFail = engine.openCalls;
            key(p, KeyEvent.KEYCODE_BACK);
            assertEquals(opensAfterFail, engine.openCalls);
            assertEquals(true, RefreshRaceTest.field(p, "failed"));
        }
    }
    @Test public void foregroundReturnWhileFailedKeepsErrorStateUntilExplicitRetry() throws Exception {
        try (ActivityController<PlayerActivity> controller = player()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            java.lang.reflect.Field ready = PlayerActivity.class.getDeclaredField("surfaceReady");
            ready.setAccessible(true);
            ready.setBoolean(p, true);
            p.error("线路超时 · 确认重试");
            int opensBefore = engine.openCalls;
            controller.pause().stop();
            p.surfaceDestroyed(null);
            controller.restart().start().resume();
            p.surfaceCreated(null);
            assertEquals(true, RefreshRaceTest.field(p, "failed"));
            assertEquals("线路超时 · 确认重试", ((TextView) RefreshRaceTest.field(p, "status")).getText().toString());
            assertEquals(View.GONE, ((View) RefreshRaceTest.field(p, "controls")).getVisibility());
            assertEquals(opensBefore, engine.openCalls);
            assertFalse(engine.playing);

            key(p, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(false, RefreshRaceTest.field(p, "failed"));
            assertEquals(true, RefreshRaceTest.field(p, "desiredPlaying"));
            assertEquals(opensBefore + 1, engine.openCalls);
            assertTrue(engine.playing);
        }
    }
    @Test public void sourcePickerHandlesFailedOpenPausedConfirmAndUserMovedTimeoutExhaustion() throws Exception {
        try (ActivityController<PlayerActivity> controller = player()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            java.lang.reflect.Field ready = PlayerActivity.class.getDeclaredField("surfaceReady");
            ready.setAccessible(true);
            ready.setBoolean(p, true);
            java.util.List<PlaySource> sources = (java.util.List<PlaySource>) RefreshRaceTest.field(p, "sources");
            sources.add(new PlaySource("http://127.0.0.1:1/second.mp4", "第二线路", "mp4", null));
            sources.add(new PlaySource("http://127.0.0.1:1/third.mp4", "第三线路", "mp4", null));

            // 1. Timeout happened BEFORE opening SourcePicker, and player is paused:
            // confirming the active source resets timeoutSwitches=0 and resumes without re-opening.
            p.timedOut(64000);
            assertEquals(1, RefreshRaceTest.field(p, "sourceIndex"));
            assertEquals(1, RefreshRaceTest.field(p, "timeoutSwitches"));
            key(p, KeyEvent.KEYCODE_MEDIA_PAUSE);
            assertEquals(false, RefreshRaceTest.field(p, "desiredPlaying"));
            int opensBefore = engine.openCalls, playsBefore = engine.playCalls;
            key(p, KeyEvent.KEYCODE_DPAD_DOWN);
            AlertDialog drawer = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(drawer);
            key(p, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(0, RefreshRaceTest.field(p, "timeoutSwitches"));
            assertEquals(true, RefreshRaceTest.field(p, "desiredPlaying"));
            assertEquals(opensBefore, engine.openCalls);
            assertEquals(playsBefore + 1, engine.playCalls);
            assertTrue(engine.playing);

            // 2. Opening SourcePicker when ALREADY failed shows no checked "使用中" row,
            // preserves cursor on sourceIndex, and retries on Confirm.
            p.error("播放失败");
            key(p, KeyEvent.KEYCODE_DPAD_DOWN);
            drawer = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(drawer);
            assertEquals(android.widget.AdapterView.INVALID_POSITION, drawer.getListView().getCheckedItemPosition());
            assertEquals(1, drawer.getListView().getSelectedItemPosition());
            TextView summary = drawer.getWindow().getDecorView().findViewWithTag("drawer-current-summary");
            assertEquals("当前线路不可用 · 请选择线路", summary.getText().toString());
            key(p, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(false, RefreshRaceTest.field(p, "failed"));
            assertEquals(1, RefreshRaceTest.field(p, "sourceIndex"));
            assertTrue(engine.playing);

            // 3. User manually scrolls cursor (userMoved=true) while consecutive timedOut() calls
            // advance sourceIndex and then exhaust into error(): cursor stays where user moved it!
            key(p, KeyEvent.KEYCODE_DPAD_DOWN);
            drawer = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(drawer);
            key(p, KeyEvent.KEYCODE_DPAD_DOWN); // cursor moves 1 -> 2, userMoved=true
            layout(drawer);
            assertEquals(2, drawer.getListView().getSelectedItemPosition());
            p.timedOut(75000); // sourceIndex 1 -> 2
            p.timedOut(75000); // sourceIndex 2 -> 0
            p.timedOut(75000); // exhausted -> failed=true
            layout(drawer);
            assertEquals(true, RefreshRaceTest.field(p, "failed"));
            assertEquals(android.widget.AdapterView.INVALID_POSITION, drawer.getListView().getCheckedItemPosition());
            assertEquals(2, drawer.getListView().getSelectedItemPosition());
            opensBefore = engine.openCalls;
            key(p, KeyEvent.KEYCODE_DPAD_CENTER);
            assertFalse(drawer.isShowing());
            assertEquals(false, RefreshRaceTest.field(p, "failed"));
            assertEquals(2, RefreshRaceTest.field(p, "sourceIndex"));
            assertEquals(0, RefreshRaceTest.field(p, "timeoutSwitches"));
            assertEquals(opensBefore + 1, engine.openCalls);
            assertTrue(engine.playing);
        }
    }
    @Test public void episodeSwitchAndLoadSourcesClearStaleDeferredPrepareFlag() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        java.lang.reflect.Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        java.util.concurrent.CountDownLatch ep2Started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ep2Release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> ep1Gate =
                new java.util.concurrent.atomic.AtomicReference<>();
        http.set(app, new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            okhttp3.Request req = chain.request();
            String body;
            if (req.url().encodedPath().equals("/vod/episode")) {
                body = "[\"第1集\",\"第2集\"]";
            } else if ("第2集".equals(req.url().queryParameter("episode"))) {
                ep2Started.countDown();
                try {
                    if (!ep2Release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("ep2 latch timeout");
                } catch (InterruptedException e) {
                    throw new java.io.IOException(e);
                }
                body = "[{\"url\":\"http://127.0.0.1:1/ep2.mp4\",\"caption\":\"第二集线路\"}]";
            } else {
                java.util.concurrent.CountDownLatch gate = ep1Gate.get();
                if (gate != null) {
                    try {
                        if (!gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("ep1 latch timeout");
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                }
                body = "[{\"url\":\"http://127.0.0.1:1/ep1.mp4\",\"caption\":\"第一集线路\"}]";
            }
            return new okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(body, okhttp3.MediaType.get("application/json"))).build();
        }).build());

        Intent intent = new Intent(app, PlayerActivity.class).putExtra("showId", 42L).putExtra("title", "切集延迟准备测试").putExtra("episode", 1);
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, intent).setup().visible()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            java.lang.reflect.Field ready = PlayerActivity.class.getDeclaredField("surfaceReady");
            ready.setAccessible(true);
            ready.setBoolean(p, true);

            long deadline = System.nanoTime() + 5_000_000_000L;
            while ((((java.util.List<?>) RefreshRaceTest.field(p, "episodes")).size() < 2
                    || ((java.util.List<?>) RefreshRaceTest.field(p, "sources")).isEmpty()) && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(2, ((java.util.List<?>) RefreshRaceTest.field(p, "episodes")).size());

            // 1. Open playback settings drawer and defer a background prepare so prepareAfterDialog == true.
            key(p, KeyEvent.KEYCODE_MENU);
            AlertDialog options = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(options);
            java.lang.reflect.Method prepare = PlayerActivity.class.getDeclaredMethod("prepare");
            prepare.setAccessible(true);
            int opensBefore = engine.openCalls, playsBefore = engine.playCalls;
            prepare.invoke(p);
            assertEquals(true, RefreshRaceTest.field(p, "prepareAfterDialog"));
            assertEquals(opensBefore, engine.openCalls);

            // Direct loadSources() while dialog stays open must immediately clear stale prepareAfterDialog.
            java.lang.reflect.Method loadSources = PlayerActivity.class.getDeclaredMethod("loadSources");
            loadSources.setAccessible(true);
            loadSources.invoke(p);
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));

            // Wait for Episode 1 sources to arrive again while dialog is STILL open -> prepareAfterDialog becomes true again.
            deadline = System.nanoTime() + 5_000_000_000L;
            while (!((Boolean) RefreshRaceTest.field(p, "prepareAfterDialog")) && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(true, RefreshRaceTest.field(p, "prepareAfterDialog"));
            assertEquals(opensBefore, engine.openCalls);

            // 2. Calling changeEpisode() while dialog is STILL open (the exact state before dialog.dismiss() runs)
            // must immediately clear prepareAfterDialog without waiting for onDismissListener.
            java.lang.reflect.Method changeEpisode = PlayerActivity.class.getDeclaredMethod("changeEpisode", int.class);
            changeEpisode.setAccessible(true);
            changeEpisode.invoke(p, 2);
            assertTrue(options.isShowing());
            assertEquals(true, RefreshRaceTest.field(p, "dialogOpen"));
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));
            assertTrue(((java.util.List<?>) RefreshRaceTest.field(p, "sources")).isEmpty());

            // Switch back to Episode 1 while dialog is still open so Episode 1 resolves and re-arms prepareAfterDialog=true,
            // then exercise the full UI click in playbackMenu ("下一集") -> dismiss -> async Episode 2 completion.
            changeEpisode.invoke(p, 1);
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));
            deadline = System.nanoTime() + 5_000_000_000L;
            while (!((Boolean) RefreshRaceTest.field(p, "prepareAfterDialog")) && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(true, RefreshRaceTest.field(p, "prepareAfterDialog"));

            option(options, 8).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, RefreshRaceTest.field(p, "number"));
            assertFalse(options.isShowing());
            assertEquals(false, RefreshRaceTest.field(p, "dialogOpen"));
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));
            assertEquals(opensBefore, engine.openCalls);
            assertEquals(playsBefore, engine.playCalls);
            assertTrue(ep2Started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));

            // Once Episode 2 source arrives, engine opens Episode 2 cleanly.
            ep2Release.countDown();
            deadline = System.nanoTime() + 5_000_000_000L;
            while (engine.openCalls == opensBefore && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(opensBefore + 1, engine.openCalls);
            assertNotNull(engine.lastOpened);
            assertEquals("http://127.0.0.1:1/ep2.mp4", engine.lastOpened.url);
            assertTrue(engine.playing);

            // 3. Also exercise EpisodePicker (DPAD_UP) card selection while prepareAfterDialog == true:
            //    switching back to Episode 1 clears stale Episode 2 deferred prepare before dismiss.
            java.util.concurrent.CountDownLatch holdEp1 = new java.util.concurrent.CountDownLatch(1);
            ep1Gate.set(holdEp1);
            try {
                key(p, KeyEvent.KEYCODE_DPAD_UP);
                AlertDialog picker = (AlertDialog) RefreshRaceTest.field(p, "dialog");
                layout(picker);
                int opensOnEp2 = engine.openCalls, playsOnEp2 = engine.playCalls;
                prepare.invoke(p);
                assertEquals(true, RefreshRaceTest.field(p, "prepareAfterDialog"));

                androidx.recyclerview.widget.RecyclerView grid = picker.getWindow().getDecorView().findViewWithTag("episode-grid");
                androidx.recyclerview.widget.RecyclerView.ViewHolder ep1Holder = grid.findViewHolderForAdapterPosition(0);
                assertNotNull(ep1Holder);
                ep1Holder.itemView.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertFalse(picker.isShowing());
                assertEquals(1, RefreshRaceTest.field(p, "number"));
                assertEquals(false, RefreshRaceTest.field(p, "dialogOpen"));
                assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));
                assertEquals(opensOnEp2, engine.openCalls);
                assertEquals(playsOnEp2, engine.playCalls);

                holdEp1.countDown();
                deadline = System.nanoTime() + 5_000_000_000L;
                while (engine.openCalls == opensOnEp2 && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    Thread.sleep(10);
                }
                assertEquals(opensOnEp2 + 1, engine.openCalls);
                assertEquals("http://127.0.0.1:1/ep1.mp4", engine.lastOpened.url);
                assertTrue(engine.playing);
            } finally {
                holdEp1.countDown();
            }
        } finally {
            ep2Release.countDown();
        }
    }
    @Test @Config(shadows={PlaybackUxTest.Engine.class,PlaybackUxTest.SyncPlaybackApi.class})
    public void synchronousSourceResolutionDuringEpisodePickerOpensNewEpisodeOnceOnDismiss() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        Intent intent = new Intent(app, PlayerActivity.class).putExtra("showId", 42L).putExtra("title", "同步取源切集测试").putExtra("episode", 1);
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, intent).setup().visible()) {
            PlayerActivity p = controller.get();
            Engine engine = org.robolectric.shadow.api.Shadow.extract(RefreshRaceTest.field(p, "engine"));
            p.surfaceCreated(null);
            assertEquals("http://127.0.0.1:1/sync-ep1.mp4", engine.lastOpened.url);

            key(p, KeyEvent.KEYCODE_DPAD_UP);
            AlertDialog picker = (AlertDialog) RefreshRaceTest.field(p, "dialog");
            layout(picker);
            java.lang.reflect.Method prepare = PlayerActivity.class.getDeclaredMethod("prepare");
            prepare.setAccessible(true);
            prepare.invoke(p);
            assertEquals(true, RefreshRaceTest.field(p, "prepareAfterDialog"));

            int opensBefore = engine.openCalls;
            androidx.recyclerview.widget.RecyclerView grid = picker.getWindow().getDecorView().findViewWithTag("episode-grid");
            androidx.recyclerview.widget.RecyclerView.ViewHolder ep2Holder = grid.findViewHolderForAdapterPosition(1);
            assertNotNull(ep2Holder);
            ep2Holder.itemView.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse(picker.isShowing());
            assertEquals(2, RefreshRaceTest.field(p, "number"));
            assertEquals(false, RefreshRaceTest.field(p, "dialogOpen"));
            assertEquals(false, RefreshRaceTest.field(p, "prepareAfterDialog"));
            assertEquals(opensBefore + 1, engine.openCalls);
            assertEquals("http://127.0.0.1:1/sync-ep2.mp4", engine.lastOpened.url);
            assertTrue(engine.playing);
        }
    }
}
