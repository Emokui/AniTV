package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Sends actual Android D-pad events, including rows outside the current viewport. */
@RunWith(AndroidJUnit4.class)
public class RemoteNavigationTest {
    private ActivityScenario<HomeActivity> scenario;
    @Before public void launch() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), HomeActivity.class)
                .putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scenario = ActivityScenario.launch(intent);
        waitIdle();
        scenario.onActivity(a -> assertEquals(240, grid(a.getWindow().getDecorView()).getAdapter().getItemCount()));
    }
    @After public void close() { if (scenario != null) scenario.close(); }
    private void key(int key) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); waitIdle();
    }
    private void waitIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(120);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static RecyclerView grid(View view) {
        if (view instanceof RecyclerView) return (RecyclerView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                RecyclerView found = grid(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }
    private int position() {
        AtomicInteger value = new AtomicInteger(-1);
        scenario.onActivity(a -> {
            RecyclerView list = grid(a.getWindow().getDecorView());
            RecyclerView.ViewHolder holder = list.findContainingViewHolder(a.getCurrentFocus());
            if (holder != null) value.set(holder.getBindingAdapterPosition());
        });
        return value.get();
    }
    private int span() {
        AtomicInteger value = new AtomicInteger();
        scenario.onActivity(a -> value.set(((GridLayoutManager) grid(a.getWindow().getDecorView()).getLayoutManager()).getSpanCount()));
        return value.get();
    }
    @Test public void oneRightEntersGridAndRowsNeverWrap() throws Exception {
        scenario.onActivity(a -> assertEquals("索引", a.getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(0, position());
        int span = span();
        for (int i = 1; i < span; i++) { key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(i, position()); }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(span - 1, position());
        key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(span * 2 - 1, position());
        for (int i = 0; i < span - 1; i++) key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(span, position());
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        scenario.onActivity(a -> assertEquals("索引", a.getCurrentFocus().getContentDescription()));
        screenshot("remote-grid.png");
    }
    @Test public void offscreenRowsAndReturnFromDetailsKeepPosition() throws Exception {
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int i = 0; i < 12; i++) { key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals((i + 1) * span(), position()); }
        int before = position();
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        screenshot("remote-detail.png");
        key(KeyEvent.KEYCODE_BACK); assertEquals(before, position());
        key(KeyEvent.KEYCODE_DPAD_UP); assertEquals(before - span(), position());
        screenshot("remote-scrolled.png");
    }
    @Test public void firstRowUpGoesToFiltersAndDownReturnsToCards() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(0, position());
        key(KeyEvent.KEYCODE_DPAD_UP); assertEquals(-1, position());
        scenario.onActivity(a -> assertEquals("类型", ((android.widget.TextView) a.getCurrentFocus()).getText().toString()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        scenario.onActivity(a -> assertEquals("语言", ((android.widget.TextView) a.getCurrentFocus()).getText().toString()));
        key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(0, position());
    }
    @Test public void lastRowCannotEscapeIntoOtherControls() throws Exception {
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        int rows = (240 + span() - 1) / span();
        for (int i = 0; i < rows; i++) {
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            android.util.Log.i("AniTVRemoteTest", "down=" + i + " position=" + position());
        }
        int last = position(); screenshot("remote-bottom.png");
        assertTrue("bottom focus=" + last + " span=" + span(), last >= 240 - span());
        key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(last, position());
    }
    @Test public void scrollingViewportCannotPaintOverFixedFilters() throws Exception {
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int i = 0; i < 8; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        scenario.onActivity(a -> {
            RecyclerView list = grid(a.getWindow().getDecorView());
            ViewGroup viewport = (ViewGroup) list.getParent();
            assertTrue(viewport.getClipChildren()); assertTrue(viewport.getClipToPadding());
            RecyclerView.ViewHolder focused = list.findContainingViewHolder(a.getCurrentFocus());
            assertNotNull(focused);
            android.graphics.Rect bounds = new android.graphics.Rect();
            list.getLayoutManager().getDecoratedBoundsWithMargins(focused.itemView, bounds);
            assertTrue("focused card top=" + bounds.top, bounds.top >= list.getPaddingTop() - 1);
            assertTrue("focused card bottom=" + bounds.bottom,
                    bounds.bottom <= list.getHeight() - list.getPaddingBottom() + 1);
        });
        screenshot("remote-fixed-filters.png");
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "evidence");
        if (!dir.isDirectory()) assertTrue(dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
}
