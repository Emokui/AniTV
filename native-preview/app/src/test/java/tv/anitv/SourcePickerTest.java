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
                    new PlaySource(server.url("/a").toString(),"线路 A 1080P","",Collections.emptyMap()),
                    new PlaySource(server.url("/b").toString(),"线路 B","",Collections.emptyMap())),1,"1920×1080",selected::set);
            dialog.show();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try {
                WindowManager.LayoutParams params=dialog.getWindow().getAttributes();
                assertEquals(Math.round(activity.getResources().getDisplayMetrics().widthPixels*.25f),params.width);
                assertEquals(WindowManager.LayoutParams.MATCH_PARENT,params.height);
                assertEquals(Gravity.RIGHT|Gravity.TOP,params.gravity);
                assertEquals(1,dialog.getListView().getCheckedItemPosition());
                android.view.View first=dialog.getListView().getAdapter().getView(0,null,dialog.getListView());
                android.view.View current=dialog.getListView().getAdapter().getView(1,null,dialog.getListView());
                assertEquals("1080P",((android.widget.TextView)first.findViewWithTag("source-resolution")).getText().toString());
                assertEquals("1920×1080",((android.widget.TextView)current.findViewWithTag("source-resolution")).getText().toString());
                dialog.getListView().performItemClick(null,0,0);
                assertEquals(0,selected.get());assertFalse(dialog.isShowing());
            } finally {dialog.dismiss(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
        }
    }
    private void layout(AlertDialog dialog){
        android.view.View root=dialog.getWindow().getDecorView();
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(dialog.getWindow().getAttributes().width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(540,android.view.View.MeasureSpec.EXACTLY));
        root.layout(0,0,dialog.getWindow().getAttributes().width,540);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    private void key(AlertDialog dialog,int key){
        dialog.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key));
        dialog.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,key));
    }
    @Test @Config(qualifiers="w960dp-h540dp-land-mdpi") public void navigatingLongListNeverWrapsOrChangesPlayingSourceWithoutConfirm()throws Exception{
        try(MockWebServer server=new MockWebServer();ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            java.util.List<PlaySource> sources=new java.util.ArrayList<>();
            for(int i=0;i<40;i++){server.enqueue(new MockResponse().setBody("v"));sources.add(new PlaySource(server.url("/"+i).toString(),"线路 "+i+" 1080P","",Collections.emptyMap()));}
            AtomicInteger selected=new AtomicInteger(-1);AlertDialog dialog=SourcePicker.create(controller.get(),sources,0,selected::set);dialog.show();layout(dialog);
            try{
                int presses=0;
                while(dialog.getListView().getSelectedItemPosition()<39 && presses++<160){
                    int before=dialog.getListView().getSelectedItemPosition();
                    key(dialog,android.view.KeyEvent.KEYCODE_DPAD_DOWN);layout(dialog);
                    int after=dialog.getListView().getSelectedItemPosition();
                    assertTrue("Down must not reset the absolute index or skip sources",after>=before && after<=before+1);
                    assertTrue("Focused rows must not be pinned at the top",after<4 || dialog.getListView().getFirstVisiblePosition()<after);
                    assertEquals(0,dialog.getListView().getCheckedItemPosition());assertEquals(-1,selected.get());
                }
                assertEquals("Repeated down must reach the last source",39,dialog.getListView().getSelectedItemPosition());
                key(dialog,android.view.KeyEvent.KEYCODE_DPAD_DOWN);layout(dialog);assertEquals(39,dialog.getListView().getSelectedItemPosition());
                presses=0;
                while(dialog.getListView().getSelectedItemPosition()>31 && presses++<40){
                    int before=dialog.getListView().getSelectedItemPosition();key(dialog,android.view.KeyEvent.KEYCODE_DPAD_UP);layout(dialog);
                    int after=dialog.getListView().getSelectedItemPosition();assertTrue(after<=before && after>=before-1);
                }
                assertEquals(31,dialog.getListView().getSelectedItemPosition());
                key(dialog,android.view.KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(31,selected.get());assertFalse(dialog.isShowing());
            }finally{dialog.dismiss();}
        }
    }

}
