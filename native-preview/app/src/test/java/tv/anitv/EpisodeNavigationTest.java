package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=AniApp.class, qualifiers="w960dp-h540dp-land")
public class EpisodeNavigationTest {
    @Test public void singleEpisodeSegmentUsesOneNumberAndDialogInheritsImmersiveFlags(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            Activity activity=controller.get();Ui.fullscreen(activity);
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=51;i++)episodes.add(new Episode(i,"番剧"));
            AlertDialog dialog=EpisodePicker.create(activity,"单集分段",episodes,50,index->{});dialog.show();layout(dialog);
            try{
                android.widget.TextView tab=dialog.getWindow().getDecorView().findViewWithTag("episode-page-1");assertEquals("51",tab.getText().toString());
                assertEquals(activity.getWindow().getDecorView().getSystemUiVisibility(),dialog.getWindow().getDecorView().getSystemUiVisibility());
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(1,grid.getAdapter().getItemCount());assertEquals(51,grid.getAdapter().getItemId(0));assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
            }finally{dialog.dismiss();}
        }
    }
    @Test public void thousandEpisodesOpenAtHistoryAndLatestKeepsAbsoluteSelection() {
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=1003;i++)episodes.add(new Episode(i,"番剧"));
            int[] selected={-1};AlertDialog dialog=EpisodePicker.create(controller.get(),"长篇",episodes,602,index->selected[0]=index);
            dialog.show();for(int i=0;i<3;i++)layout(dialog);
            try {
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                assertEquals(50,grid.getAdapter().getItemCount());assertEquals(601,grid.getAdapter().getItemId(0));
                assertTrue(grid.findViewHolderForAdapterPosition(2).itemView.hasFocus());
                grid.findViewHolderForAdapterPosition(2).itemView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                View latest=dialog.getWindow().getDecorView().findViewWithTag("episode-latest");assertTrue(latest.hasFocus());
                latest.performClick();for(int i=0;i<4;i++)layout(dialog);
                assertEquals(3,grid.getAdapter().getItemCount());assertEquals(1001,grid.getAdapter().getItemId(0));
                View last=grid.findViewHolderForAdapterPosition(2).itemView;assertTrue(last.hasFocus());last.performClick();
                assertEquals(1002,selected[0]);assertFalse(dialog.isShowing());
            } finally {dialog.dismiss();}
        }
    }
    @Test public void segmentRequiresConfirmAndPageChangeDoesNotPlayAnotherEpisode() {
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=120;i++)episodes.add(new Episode(i,"番剧"));
            int[] selected={-1};AlertDialog dialog=EpisodePicker.create(controller.get(),"分段",episodes,0,index->selected[0]=index);
            dialog.show();layout(dialog);
            try {
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                View second=dialog.getWindow().getDecorView().findViewWithTag("episode-page-1");second.requestFocus();
                assertEquals(1,grid.getAdapter().getItemId(0));assertEquals(-1,selected[0]);
                second.performClick();for(int i=0;i<4;i++)layout(dialog);
                assertEquals(51,grid.getAdapter().getItemId(0));assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
                assertEquals(-1,selected[0]);grid.findViewHolderForAdapterPosition(0).itemView.performClick();assertEquals(50,selected[0]);
            } finally {dialog.dismiss();}
        }
    }
    private void layout(AlertDialog dialog) {
        View root=dialog.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));
        root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void downFromRightColumnReachesIncompleteLastRowAndUpReturns() {
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=6;i++)episodes.add(new Episode(i,"第"+i+"集"));
            AlertDialog dialog=EpisodePicker.create(controller.get(),"导航测试",episodes,3,index->{});
            dialog.show();layout(dialog);
            try {
                RecyclerView grid=dialog.findViewById(android.R.id.content).findViewWithTag("episode-grid");
                View fourth=grid.findViewHolderForAdapterPosition(3).itemView;
                fourth.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(dialog);
                View last=grid.findViewHolderForAdapterPosition(5).itemView;assertTrue(last.hasFocus());
                last.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));layout(dialog);
                assertTrue(grid.findViewHolderForAdapterPosition(1).itemView.hasFocus());
            } finally {dialog.dismiss();}
        }
    }
}
