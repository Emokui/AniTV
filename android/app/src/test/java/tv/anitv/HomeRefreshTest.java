package tv.anitv;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class HomeRefreshTest {
    private Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private boolean hasRefresh(View view) {
        if(view instanceof TextView && "刷新".contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasRefresh(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    @Test public void currentRailRefreshKeepsFiltersAndFocusAndNonIndexHidesFilterSpace() throws Exception {
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(
                RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)) {
            HomeActivity home=controller.get();
            View root=home.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));root.layout(0,0,1920,1080);
            List<View> rail=(List<View>)field(home,"rail");
            assertFalse(hasRefresh(root));assertEquals(View.VISIBLE,((View)field(home,"filters")).getVisibility());
            Field year=HomeActivity.class.getDeclaredField("year");year.setAccessible(true);year.set(home,"2024");
            int before=(Integer)field(home,"generation");rail.get(1).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(before,field(home,"generation"));assertEquals("2024",field(home,"year"));assertTrue(rail.get(1).hasFocus());
            assertTrue("Rail should accept focus",rail.get(0).requestFocus());
            // ShadowActivity keeps its current focus separately from the view tree.
            Shadows.shadowOf(home).setCurrentFocus(rail.get(0));
            before=(Integer)field(home,"generation");
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals("Right on another rail entry must not change section",1,field(home,"section"));
            assertEquals(before,field(home,"generation"));assertTrue(((View)field(home,"typeButton")).hasFocus());
            rail.get(0).performClick();assertEquals(View.GONE,((View)field(home,"filters")).getVisibility());
            rail.get(0).requestFocus();Shadows.shadowOf(home).setCurrentFocus(rail.get(0));before=(Integer)field(home,"generation");
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals("Right enters content without refreshing",before,field(home,"generation"));
        }
    }
    @Test public void refreshRequiresTwoSecondsAndRunsOnlyOnceUntilRelease()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();View index=((List<View>)field(home,"rail")).get(1);index.requestFocus();Shadows.shadowOf(home).setCurrentFocus(index);
            long epoch=EpisodeCache.epoch();int before=(Integer)field(home,"generation");
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1999));assertEquals(before,field(home,"generation"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));assertEquals(before+1,field(home,"generation"));assertTrue(EpisodeCache.epoch()>epoch);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));assertEquals(before+1,field(home,"generation"));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));assertEquals(before+1,field(home,"generation"));
        }
    }
    @Test public void leavingDuringHoldCancelsRefreshAndShortPressStillSwitches()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();View recommend=((List<View>)field(home,"rail")).get(0);recommend.requestFocus();Shadows.shadowOf(home).setCurrentFocus(recommend);
            int before=(Integer)field(home,"generation");
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));controller.pause();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));assertEquals(before,field(home,"generation"));controller.resume();
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));
            assertEquals(0,field(home,"section"));assertEquals(before+1,field(home,"generation"));
        }
    }
    @Test public void settingsAndSearchUseStyledDialogsWithoutSwitchingUnderlyingPage() throws Exception {
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(
                RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible()) {
            HomeActivity home=controller.get();List<View> rail=(List<View>)field(home,"rail");
            rail.get(HomeActivity.SETTINGS).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog settings=(android.app.AlertDialog)field(home,"activeDialog");
            assertNotNull(settings);assertTrue(settings.isShowing());assertEquals(7,settings.getListView().getCount());
            for(int i=0;i<settings.getListView().getCount();i++) {
                String label=settings.getListView().getAdapter().getItem(i).toString();
                assertFalse(label.contains("演示"));assertFalse(label.contains("原版"));
            }
            assertEquals(Math.round(home.getResources().getDisplayMetrics().widthPixels*.25f),settings.getWindow().getAttributes().width);
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));
            assertEquals(1,field(home,"section"));assertSame(settings,field(home,"activeDialog"));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(settings.isShowing());assertFalse(home.isFinishing());
            rail.get(HomeActivity.SEARCH).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog search=(android.app.AlertDialog)field(home,"activeDialog");assertTrue(search.isShowing());
            android.widget.EditText input=edit(search.getWindow().getDecorView());assertNotNull(input);assertEquals("",input.getHint().toString());assertEquals("",input.getText().toString());input.setText("测试");
            search.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(HomeActivity.SEARCH,field(home,"section"));assertEquals("测试",field(home,"keyword"));
            int before=(Integer)field(home,"generation");rail.get(HomeActivity.SEARCH).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog second=(android.app.AlertDialog)field(home,"activeDialog");
            assertNotNull(second);assertTrue(second.isShowing());assertNotSame(search,second);
            assertEquals(before,field(home,"generation"));assertEquals("",edit(second.getWindow().getDecorView()).getText().toString());
            edit(second.getWindow().getDecorView()).setText("取消的输入");second.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("测试",field(home,"keyword"));assertEquals(before,field(home,"generation"));
            rail.get(HomeActivity.SEARCH).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog third=(android.app.AlertDialog)field(home,"activeDialog");assertEquals("",edit(third.getWindow().getDecorView()).getText().toString());
            edit(third.getWindow().getDecorView()).setText("新番");third.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("新番",field(home,"keyword"));assertEquals(HomeActivity.SEARCH,field(home,"section"));
        }
    }
    private android.widget.EditText edit(View view) {
        if(view instanceof android.widget.EditText)return (android.widget.EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            android.widget.EditText result=edit(((ViewGroup)view).getChildAt(i));if(result!=null)return result;
        }
        return null;
    }

    @Test public void emptyFavoritesStayOnRailAndFooterIsCompactAtRight() throws Exception {
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(
                RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible()) {
            HomeActivity home=controller.get();new Library(home).prefs.edit().remove("favorites").commit();
            List<View> rail=(List<View>)field(home,"rail");rail.get(HomeActivity.FAVORITES).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(HomeActivity.FAVORITES,field(home,"section"));assertTrue(rail.get(HomeActivity.FAVORITES).hasFocus());assertEquals(false,field(home,"needsFocus"));
            for(View action:(List<View>)field(home,"emptyActions"))assertEquals(View.GONE,action.getVisibility());
            Shadows.shadowOf(home).setCurrentFocus(rail.get(HomeActivity.FAVORITES));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            assertTrue(rail.get(HomeActivity.FAVORITES).hasFocus());assertEquals(false,field(home,"needsFocus"));
            TextView footer=(TextView)field(home,"footer");
            assertEquals(android.view.Gravity.END,footer.getGravity()&android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            assertEquals(Ui.dp(home,18),footer.getLayoutParams().height);
        }
    }

}
