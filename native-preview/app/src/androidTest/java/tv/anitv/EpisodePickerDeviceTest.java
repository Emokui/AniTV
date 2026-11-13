package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EpisodePickerDeviceTest {
    private AlertDialog dialog;
    private RecyclerView grid;
    private void key(int code) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);
        SystemClock.sleep(150); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private int position() {
        AtomicInteger position = new AtomicInteger(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View focus = dialog.getCurrentFocus();
            RecyclerView.ViewHolder holder = focus == null ? null : grid.findContainingViewHolder(focus);
            if (holder != null) position.set(holder.getBindingAdapterPosition());
        }); return position.get();
    }
    @Test public void remoteScrollRestoresSelectionAndKeepsCanonicalNumber() {
        try (ActivityScenario<HomeActivity> home = ActivityScenario.launch(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), HomeActivity.class)
                .putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK))) {
            ArrayList<Episode> episodes = new ArrayList<>();
            for (int i = 0; i < 81; i++) episodes.add(new Episode(i + 49, "剧集标题 " + i));
            AtomicInteger chosen = new AtomicInteger(-1);
            home.onActivity(a -> {
                dialog = EpisodePicker.create(a, "遥控器选集测试", episodes, 40, i -> chosen.set(episodes.get(i).number));
                dialog.show(); grid = dialog.findViewById(android.R.id.content).findViewWithTag("episode-grid");
            });
            SystemClock.sleep(700); assertEquals(40, position());
            key(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(40, position());
            key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(41, position());
            key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(45, position());
            for (int i = 0; i < 12; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            assertEquals(77, position());
            key(KeyEvent.KEYCODE_DPAD_LEFT); assertEquals(76, position());
            key(KeyEvent.KEYCODE_DPAD_DOWN); assertEquals(80, position());
            key(KeyEvent.KEYCODE_DPAD_RIGHT); assertEquals(80, position());
            home.onActivity(a -> {
                View focused = dialog.getCurrentFocus(); Rect visible = new Rect();
                assertTrue(focused.getLocalVisibleRect(visible)); assertEquals(focused.getHeight(), visible.height());
            });
            key(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(129, chosen.get());
            home.onActivity(a -> assertFalse(dialog.isShowing()));
            home.onActivity(a -> { dialog = EpisodePicker.create(a, "返回测试", episodes, 0, i -> fail("Back must not select")); dialog.show(); });
            SystemClock.sleep(600);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            key(KeyEvent.KEYCODE_BACK); home.onActivity(a -> assertFalse(dialog.isShowing()));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (dialog != null) dialog.dismiss(); });
        }
    }
}
