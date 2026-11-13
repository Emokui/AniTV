package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class HistoryDeleteTest {
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private ActivityController<HomeActivity> create()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();String endpoint=new Library(app).endpoint();
        for(int id:new int[]{42,43}){history.remember(new Show(id,"番剧"+id,"https://example.org/"+id+".jpg",""),endpoint,new String[]{"番剧"+id});history.save(Integer.toString(id),7,5000,100000);}
        return Robolectric.buildActivity(HomeActivity.class,new Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true);
    }
    private View enter(HomeActivity home)throws Exception{
        ((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();RecyclerView grid=(RecyclerView)field(home,"grid");await(()->grid.getAdapter().getItemCount()==2);
        View root=home.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);idle();
        View first=grid.findViewHolderForAdapterPosition(0).itemView;first.requestFocus();Shadows.shadowOf(home).setCurrentFocus(first);return first;
    }
    private void down(HomeActivity home){home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));}
    private void up(HomeActivity home){home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));idle();}
    @Test public void holdOpensDeleteWithoutResumingAndKeyReleaseCannotConfirmDeletion()throws Exception{
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();enter(home);PlaybackHistory history=new PlaybackHistory(home);String endpoint=new Library(home).endpoint();
            new Library(home).toggle(new Show(43,"收藏","",""));home.getSharedPreferences("native-source-preferences",0).edit().putString("sentinel","keep").commit();
            down(home);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
            AlertDialog dialog=(AlertDialog)field(home,"activeDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());assertNull(Shadows.shadowOf(home).getNextStartedActivity());
            up(home);assertTrue(dialog.isShowing());assertEquals(2,history.entries(endpoint).size());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertNull(history.find(43,endpoint));assertNotNull(history.find(42,endpoint));assertEquals(0,history.position("43",7));assertEquals(5000,history.position("42",7));
            assertTrue(new Library(home).contains(43));assertEquals("keep",home.getSharedPreferences("native-source-preferences",0).getString("sentinel",""));
        }
    }
    @Test public void shortPressOpensDetailAndCancellingHoldPreservesRecord()throws Exception{
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();enter(home);down(home);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(599));up(home);
            Intent player=Shadows.shadowOf(home).getNextStartedActivity();assertNotNull(player);assertEquals(DetailActivity.class.getName(),player.getComponent().getClassName());assertEquals(43,player.getLongExtra("id",0));
            down(home);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));up(home);
            AlertDialog dialog=(AlertDialog)field(home,"activeDialog");assertNotNull(dialog);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
            assertNotNull(new PlaybackHistory(home).find(43,new Library(home).endpoint()));assertEquals(5000,new PlaybackHistory(home).position("43",7));
            assertNull(Shadows.shadowOf(home).getNextStartedActivity());
        }
    }
    @Test public void navigatingAwayCancelsPendingLongPress()throws Exception{
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();enter(home);down(home);
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));up(home);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));assertNull(field(home,"activeDialog"));assertNull(Shadows.shadowOf(home).getNextStartedActivity());
        }
    }
}
