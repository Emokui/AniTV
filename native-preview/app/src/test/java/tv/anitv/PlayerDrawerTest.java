package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,qualifiers="w960dp-h540dp-land")
public class PlayerDrawerTest {
    TextView option(AlertDialog dialog,int index){return dialog.getWindow().getDecorView().findViewWithTag("playback-option-"+index);}
    @Test public void fullHeightQuarterDrawerUsesParallelRowsAndTopEpisodeActions(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            AtomicInteger applied=new AtomicInteger(-1),cursor=new AtomicInteger(-1);
            AlertDialog dialog=PlayerDrawer.settings(controller.get(),1.5f,1,6,applied::set,cursor::set);dialog.show();
            View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,240,540);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try{
                WindowManager.LayoutParams window=dialog.getWindow().getAttributes();assertEquals(240,window.width);assertEquals(WindowManager.LayoutParams.MATCH_PARENT,window.height);assertEquals(Gravity.RIGHT|Gravity.TOP,window.gravity);
                for(int i=0;i<9;i++)assertEquals(i==2 || i==5,option(dialog,i).isSelected());assertTrue(option(dialog,6).hasFocus());assertEquals(-1,applied.get());
                assertSame(option(dialog,7).getParent(),option(dialog,8).getParent());assertSame(option(dialog,0).getParent(),option(dialog,1).getParent());assertSame(option(dialog,4).getParent(),option(dialog,6).getParent());
                int[] top=new int[2],speed=new int[2];option(dialog,7).getLocationInWindow(top);option(dialog,0).getLocationInWindow(speed);assertTrue(top[1]<speed[1]);
                option(dialog,7).requestFocus();option(dialog,7).dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertTrue(option(dialog,8).hasFocus());assertEquals(8,cursor.get());
                option(dialog,4).performClick();assertEquals(4,applied.get());assertFalse(dialog.isShowing());
            }finally{dialog.dismiss();}
        }
    }
}
