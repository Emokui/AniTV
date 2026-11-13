package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackDeviceTest {
    private PlaybackFixture fixture;
    private ActivityScenario<PlayerActivity> scenario;
    private ActivityScenario<DetailActivity> detailScenario;
    private String originalEndpoint;
    @Before public void setup() throws Exception {
        Library library = new Library(InstrumentationRegistry.getInstrumentation().getTargetContext());
        originalEndpoint = library.endpoint(); fixture = new PlaybackFixture();
        library.prefs.edit().putString("endpoint", fixture.url("/")).commit();
    }
    @After public void close() throws Exception {
        if (scenario != null) scenario.close();
        if (detailScenario != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (a instanceof PlayerActivity) a.finish();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); detailScenario.close();
        }
        if (originalEndpoint != null) new Library(InstrumentationRegistry.getInstrumentation().getTargetContext()).prefs.edit().putString("endpoint", originalEndpoint).commit();
        if (fixture != null) fixture.close();
    }
    private void launch(String path) {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), PlayerActivity.class)
                .putExtra("directUrl", fixture.url(path)).putExtra("title", "AniTV 原生播放测试")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scenario = ActivityScenario.launch(intent);
    }
    private NativePlayback engine(PlayerActivity activity) {
        try { java.lang.reflect.Field f = PlayerActivity.class.getDeclaredField("engine"); f.setAccessible(true); return (NativePlayback) f.get(activity); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void await(String description, Predicate<NativePlayback> condition) {
        long end = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < end) {
            AtomicBoolean okay = new AtomicBoolean(); scenario.onActivity(a -> okay.set(condition.test(engine(a))));
            if (okay.get()) return; SystemClock.sleep(100);
        }
        fail(description);
    }
    private long position() {
        AtomicLong value = new AtomicLong(); scenario.onActivity(a -> value.set(engine(a).position())); return value.get();
    }
    private void key(int code) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private void screenshot(String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "playback-evidence");
        if (!dir.isDirectory()) assertTrue(dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
    @Test public void mp4RendersAudioTrackAndRemotePauseSeekResumeWork() throws Exception {
        launch("/sample.mp4"); await("MP4 first frame", p -> p.videoRendered() && p.playing() && p.audioTracks() > 0);
        await("MP4 clock advances", p -> p.position() > 1000); screenshot("mp4-first-frame.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("OK pauses", p -> !p.wantsPlay() && !p.playing());
        long paused = position(); SystemClock.sleep(500); assertTrue(Math.abs(position() - paused) < 300);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await("seek while paused", p -> p.position() >= paused + 7000 && !p.playing());
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("OK resumes", NativePlayback::playing);
        screenshot("mp4-after-seek.png");
    }
    @Test public void hlsRendersAndRecreationRestoresPausedProgress() throws Exception {
        launch("/sample.m3u8"); await("HLS first frame", p -> p.videoRendered() && p.playing() && p.audioTracks() > 0);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await("HLS seek", p -> p.position() > 8000);
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("pause HLS", p -> !p.wantsPlay() && !p.playing());
        long saved = position(); scenario.recreate();
        await("HLS paused progress survives recreation", p -> p.prepared() && !p.wantsPlay() && !p.playing() && p.position() >= saved - 1500);
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("resume recreated HLS", NativePlayback::playing);
        screenshot("hls-resumed.png");
    }
    @Test public void episodeApiAndSourceSwitchWorkWithoutLosingProgress() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), PlayerActivity.class)
                .putExtra("showId", 42L).putExtra("episode", 1).putExtra("title", "原生选集换源测试")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scenario = ActivityScenario.launch(intent);
        await("episode resolves to playable MP4", p -> p.videoRendered() && p.playing());
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await("seek before source switch", p -> p.position() > 8000);
        long saved = position(); key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        await("source switch uses HLS and retains clock", p -> p.videoRendered() && p.playing() &&
                p.engineName().equals("Media3") && p.position() >= saved - 2000);
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_BACK);
        await("cancel episode panel resumes current stream", NativePlayback::playing);
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
        await("next episode renders", p -> p.videoRendered() && p.playing());
        AtomicLong number = new AtomicLong(); scenario.onActivity(a -> {
            try { java.lang.reflect.Field f = PlayerActivity.class.getDeclaredField("number"); f.setAccessible(true); number.set(f.getInt(a)); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertEquals(2, number.get()); screenshot("episode-two.png");
    }
    @Test public void detailEpisodeSelectionStartsNativePlayback() throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), DetailActivity.class)
                .putExtra("id", 42L).putExtra("title", "详情到播放测试")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        detailScenario = ActivityScenario.launch(intent);
        long end = SystemClock.elapsedRealtime() + 15000; AtomicBoolean loaded = new AtomicBoolean();
        while (!loaded.get() && SystemClock.elapsedRealtime() < end) {
            detailScenario.onActivity(a -> {
                try { java.lang.reflect.Field f = DetailActivity.class.getDeclaredField("episodes"); f.setAccessible(true); loaded.set(((java.util.List<?>) f.get(a)).size() == 2); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }); SystemClock.sleep(100);
        }
        assertTrue("detail loads episode list", loaded.get());
        key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_DPAD_CENTER);
        AtomicBoolean rendered = new AtomicBoolean(); end = SystemClock.elapsedRealtime() + 25000;
        while (!rendered.get() && SystemClock.elapsedRealtime() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (a instanceof PlayerActivity) rendered.set(engine((PlayerActivity) a).videoRendered());
            }); SystemClock.sleep(100);
        }
        assertTrue("detail selection reaches native first frame", rendered.get()); screenshot("detail-to-playback.png");
    }
}
