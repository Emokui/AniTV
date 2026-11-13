package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.WindowManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, manifest=Config.NONE)
public class SourcePickerTest {
    @Test public void drawerUsesRightQuarterAndSelectionKeepsOriginalIndex() throws Exception {
        try (MockWebServer server = new MockWebServer(); ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            server.enqueue(new MockResponse().setBody("v"));server.enqueue(new MockResponse().setBody("v"));
            Activity activity=controller.get(); AtomicInteger selected=new AtomicInteger(-1);
            AlertDialog dialog=SourcePicker.create(activity,Arrays.asList(
                    new PlaySource(server.url("/a").toString(),"线路 A","",Collections.emptyMap()),
                    new PlaySource(server.url("/b").toString(),"线路 B","",Collections.emptyMap())),1,selected::set);
            dialog.show();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try {
                WindowManager.LayoutParams params=dialog.getWindow().getAttributes();
                assertEquals(Math.round(activity.getResources().getDisplayMetrics().widthPixels*.25f),params.width);
                assertEquals(WindowManager.LayoutParams.MATCH_PARENT,params.height);
                assertEquals(Gravity.RIGHT|Gravity.TOP,params.gravity);
                assertEquals(1,dialog.getListView().getCheckedItemPosition());
                dialog.getListView().performItemClick(null,0,0);
                assertEquals(0,selected.get());assertFalse(dialog.isShowing());
            } finally {dialog.dismiss(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
        }
    }
}
