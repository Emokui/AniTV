package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class SettingsNavigationTest {
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    AlertDialog dialog(HomeActivity home)throws Exception{return (AlertDialog)field(home,"activeDialog");}
    ActivityController<HomeActivity> home(){return Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible();}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void open(HomeActivity home)throws Exception{((List<View>)field(home,"rail")).get(HomeActivity.SETTINGS).performClick();idle();}
    void choose(HomeActivity home,int position)throws Exception{dialog(home).getListView().performItemClick(null,position,position);idle();}
    void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!done.getAsBoolean() && System.nanoTime()<end){idle();Thread.sleep(10);}assertTrue(done.getAsBoolean());}
    void assertMain(HomeActivity home,int selected)throws Exception{AlertDialog menu=dialog(home);assertNotNull(menu);assertTrue(menu.isShowing());assertEquals(7,menu.getListView().getCount());assertEquals(selected,menu.getListView().getSelectedItemPosition());}
    @Test public void safetyAndAutoNextToggleInMainMenuWithoutSubmenus()throws Exception{
        try(ActivityController<HomeActivity> c=home()){
            HomeActivity a=c.get();open(a);AlertDialog sameMenu=dialog(a);assertTrue(new Library(a).safeMode());choose(a,5);assertSame(sameMenu,dialog(a));assertMain(a,5);assertFalse(new Library(a).safeMode());assertEquals("安全模式：关",dialog(a).getListView().getAdapter().getItem(5).toString());
            choose(a,5);assertSame(sameMenu,dialog(a));assertMain(a,5);assertTrue(new Library(a).safeMode());
            choose(a,2);assertSame(sameMenu,dialog(a));assertMain(a,2);assertTrue(new Library(a).prefs.getBoolean("auto-next",false));
            a.onBackPressed();idle();assertNull(dialog(a));assertFalse(a.isFinishing());
        }
    }
    @Test public void settingsChildrenReturnToMainAndSearchDoesNot()throws Exception{
        try(ActivityController<HomeActivity> c=home()){
            HomeActivity a=c.get();open(a);
            for(int position:new int[]{0,1,6}){choose(a,position);AlertDialog child=dialog(a);assertNull(child.getListView());a.onBackPressed();idle();assertMain(a,position);assertFalse(child.isShowing());}
            a.onBackPressed();idle();((List<View>)field(a,"rail")).get(HomeActivity.SEARCH).performClick();idle();a.onBackPressed();idle();assertNull(dialog(a));
        }
    }
    @Test public void cacheConfirmationReturnsOneLevelThenBackReturnsToSettings()throws Exception{
        try(ActivityController<HomeActivity> c=home()){
            HomeActivity a=c.get();open(a);choose(a,4);
            await(()->{try{return dialog(a)!=null && dialog(a).getListView()!=null && dialog(a).getListView().getCount()==5;}catch(Exception e){throw new AssertionError(e);}});
            choose(a,CacheManager.HISTORY);AlertDialog confirm=dialog(a);assertNotNull(confirm.getButton(AlertDialog.BUTTON_NEGATIVE));confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
            await(()->{try{return dialog(a)!=null && dialog(a).getListView()!=null && dialog(a).getListView().getCount()==5;}catch(Exception e){throw new AssertionError(e);}});
            a.onBackPressed();idle();assertMain(a,4);
        }
    }
    @Test public void returningFromAccountRestoresSettingsAndItsCursor()throws Exception{
        try(ActivityController<HomeActivity> c=home()){
            HomeActivity a=c.get();open(a);choose(a,3);Intent login=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(LoginActivity.class.getName(),login.getComponent().getClassName());
            c.pause().stop().restart().start().resume().visible();idle();assertMain(a,3);assertEquals(false,field(a,"returnToSettings"));
        }
    }
    android.widget.EditText edit(View view){if(view instanceof android.widget.EditText)return (android.widget.EditText)view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.widget.EditText found=edit(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    @Test public void linkPlaybackAndSavedEndpointReturnToSettings()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(app,new okhttp3.OkHttpClient.Builder().addInterceptor(chain->new okhttp3.Response.Builder().request(chain.request()).code(200).message("OK").protocol(okhttp3.Protocol.HTTP_1_1).body(okhttp3.ResponseBody.create("[]",okhttp3.MediaType.get("application/json"))).build()).build());
        try(ActivityController<HomeActivity> c=home()){
            HomeActivity a=c.get();open(a);choose(a,1);edit(dialog(a).getWindow().getDecorView()).setText("https://example.org/video.mp4");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            Intent player=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(PlayerActivity.class.getName(),player.getComponent().getClassName());c.pause().stop().restart().start().resume().visible();idle();assertMain(a,1);
            choose(a,0);edit(dialog(a).getWindow().getDecorView()).setText("https://api.example.org");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertMain(c.get(),0);assertEquals("https://api.example.org/",new Library(c.get()).endpoint());
        }
    }
}
