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
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(50,grid.getAdapter().getItemCount());assertEquals(1,grid.getAdapter().getItemId(0));
                tab.performClick();layout(dialog);assertEquals(1,grid.getAdapter().getItemCount());assertEquals(51,grid.getAdapter().getItemId(0));
                View order=dialog.getWindow().getDecorView().findViewWithTag("episode-order");order.requestFocus();
                order.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(dialog);
                assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
            }finally{dialog.dismiss();}
        }
    }
    @Test public void thousandEpisodesOpenAtSortStartAndDescendingKeepsAbsoluteSelection() {
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=1003;i++)episodes.add(new Episode(i,"番剧"));
            int[] selected={-1};AlertDialog dialog=EpisodePicker.create(controller.get(),"长篇",episodes,602,index->selected[0]=index);
            dialog.show();for(int i=0;i<3;i++)layout(dialog);
            try {
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                assertEquals(50,grid.getAdapter().getItemCount());assertEquals(1,grid.getAdapter().getItemId(0));
                View initialOrder=dialog.getWindow().getDecorView().findViewWithTag("episode-order");assertTrue(initialOrder.hasFocus());
                initialOrder.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(dialog);
                assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
                grid.findViewHolderForAdapterPosition(0).itemView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                assertTrue(dialog.getWindow().getDecorView().findViewWithTag("episode-page-0").hasFocus());
                View latest=dialog.getWindow().getDecorView().findViewWithTag("episode-order");latest.requestFocus();
                latest.performClick();for(int i=0;i<4;i++)layout(dialog);
                assertEquals(50,grid.getAdapter().getItemCount());assertEquals(1003,grid.getAdapter().getItemId(0));
                assertTrue(latest.hasFocus());latest.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(dialog);
                View last=grid.findViewHolderForAdapterPosition(0).itemView;assertTrue(last.hasFocus());last.performClick();
                assertEquals(1002,selected[0]);assertFalse(dialog.isShowing());
            } finally {dialog.dismiss();}
        }
    }
    @Test public void descendingConanStartsAtLatestButPlayerKeepsCurrentSegment(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            Activity activity=controller.get();List<Episode> episodes=new ArrayList<>();for(int i=1;i<=1103;i++)episodes.add(new Episode(i,"第"+i+"集"));
            new Library(activity).prefs.edit().putBoolean("episode-order:"+new Library(activity).endpoint()+"|conan",true).commit();
            for(boolean current:new boolean[]{false,true}){
                AlertDialog dialog=EpisodePicker.create(activity,"名侦探柯南","conan",episodes,0,current,i->{});dialog.show();for(int i=0;i<3;i++)layout(dialog);
                RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(current?3:1103,grid.getAdapter().getItemId(0));
                if(current)assertTrue(grid.findViewHolderForAdapterPosition(2).itemView.hasFocus());else{assertTrue(dialog.getWindow().getDecorView().findViewWithTag("episode-order").hasFocus());assertTrue(dialog.getWindow().getDecorView().findViewWithTag("episode-page-0").isSelected());}
                dialog.dismiss();
            }
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
    @Test public void reverseCanToggleBackAndRemembersOrderWithoutChangingEpisodeIdentity(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=1003;i++)episodes.add(new Episode(i,"番剧"));
            int[] selected={-1};AlertDialog dialog=EpisodePicker.create(controller.get(),"长篇",episodes,602,index->selected[0]=index);dialog.show();layout(dialog);
            View order=dialog.getWindow().getDecorView().findViewWithTag("episode-order");RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
            order.performClick();for(int i=0;i<3;i++)layout(dialog);
            assertEquals(1003,grid.getAdapter().getItemId(0));assertEquals(-1,selected[0]);
            order.performClick();for(int i=0;i<3;i++)layout(dialog);assertEquals(1,grid.getAdapter().getItemId(0));
            order.performClick();layout(dialog);dialog.dismiss();
            AlertDialog reopened=EpisodePicker.create(controller.get(),"长篇",episodes,602,index->selected[0]=index);reopened.show();for(int i=0;i<3;i++)layout(reopened);
            try{
                RecyclerView restored=reopened.getWindow().getDecorView().findViewWithTag("episode-grid");
                assertEquals(1003,restored.getAdapter().getItemId(0));
                View reopenedOrder=reopened.getWindow().getDecorView().findViewWithTag("episode-order");assertTrue(reopenedOrder.hasFocus());
                reopenedOrder.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(reopened);
                assertTrue(restored.findViewHolderForAdapterPosition(0).itemView.hasFocus());
                restored.findViewHolderForAdapterPosition(0).itemView.performClick();assertEquals(1002,selected[0]);
            }finally{reopened.dismiss();}
        }
    }
    @Test public void orderIsPerShowAndSharedBetweenDetailAndPlayerUsingItsId(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            Activity activity=controller.get();List<Episode> episodes=new ArrayList<>();for(int i=1;i<=60;i++)episodes.add(new Episode(i,"番剧"));
            // The old global preference must not make a new show inherit another show's order.
            new Library(activity).prefs.edit().putBoolean("episode-descending",true).apply();
            AlertDialog first=EpisodePicker.create(activity,"番剧A","42",episodes,0,index->{});first.show();layout(first);
            android.widget.TextView firstOrder=first.getWindow().getDecorView().findViewWithTag("episode-order");assertTrue(firstOrder.hasFocus());assertEquals("正序 ↑",firstOrder.getText().toString());
            firstOrder.performClick();layout(first);assertTrue(firstOrder.hasFocus());first.dismiss();
            AlertDialog other=EpisodePicker.create(activity,"番剧B","43",episodes,0,index->{});other.show();layout(other);
            assertEquals("正序 ↑",((android.widget.TextView)other.getWindow().getDecorView().findViewWithTag("episode-order")).getText().toString());other.dismiss();
            AlertDialog same=EpisodePicker.create(activity,"番剧A的其他标题","42",episodes,0,index->{});same.show();layout(same);
            try{View order=same.getWindow().getDecorView().findViewWithTag("episode-order");assertTrue(order.hasFocus());assertEquals("倒序 ↓",((android.widget.TextView)order).getText().toString());}finally{same.dismiss();}
        }
    }
    @Test public void episodeCountBoundariesControlOrderButtonAndSegmentSize(){
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()){
            for(int count:new int[]{1,12,13,23,24,48,49,50,51}){
                List<Episode> episodes=new ArrayList<>();for(int i=1;i<=count;i++)episodes.add(new Episode(i,"番剧"));
                int[] selected={-1};String key="boundary:"+count;
                new Library(controller.get()).prefs.edit().putBoolean("episode-order:"+new Library(controller.get()).endpoint()+"|"+key,count<=12).apply();
                AlertDialog dialog=EpisodePicker.create(controller.get(),"分段测试",key,episodes,0,index->selected[0]=index);dialog.show();layout(dialog);
                try{
                    View order=dialog.getWindow().getDecorView().findViewWithTag("episode-order");
                    RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                    int pageSize=count<=48?12:50;
                    assertEquals(Math.min(count,pageSize),grid.getAdapter().getItemCount());assertEquals(1,grid.getAdapter().getItemId(0));
                    if(count<=12){assertNull(order);assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());}
                    else{
                        assertNotNull(order);assertTrue(order.hasFocus());
                        if(count>pageSize){
                            android.widget.TextView first=dialog.getWindow().getDecorView().findViewWithTag("episode-page-0");assertEquals("1–"+pageSize,first.getText().toString());
                            View second=dialog.getWindow().getDecorView().findViewWithTag("episode-page-1");assertNotNull(second);second.performClick();for(int i=0;i<3;i++)layout(dialog);
                            assertEquals(Math.min(pageSize,count-pageSize),grid.getAdapter().getItemCount());assertEquals(pageSize+1,grid.getAdapter().getItemId(0));
                        }
                        order.requestFocus();order.performClick();for(int i=0;i<3;i++)layout(dialog);
                        assertTrue(order.hasFocus());assertEquals(count,grid.getAdapter().getItemId(0));assertEquals(-1,selected[0]);
                        if(count>pageSize){android.widget.TextView first=dialog.getWindow().getDecorView().findViewWithTag("episode-page-0");assertEquals(count+"–"+(count-pageSize+1),first.getText().toString());}
                    }
                }finally{dialog.dismiss();}
            }
        }
    }
    private void layout(AlertDialog dialog) {
        View root=dialog.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));
        root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void downFromRightColumnReachesIncompleteLastRowAndUpReturns() {
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=6;i++)episodes.add(new Episode(i,"第"+i+"集",false,"https://example.org/ep.jpg",0,null));
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
