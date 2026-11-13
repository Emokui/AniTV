package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.List;
import okhttp3.HttpUrl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class LoginStatusTest {
    private View text(View view,String value) {
        if(view instanceof TextView && value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View result=text(((ViewGroup)view).getChildAt(i),value);if(result!=null)return result;
        }return null;
    }
    @Test public void savedSessionAppearsInSettingsAndLogoutUpdatesAccountScreen() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();HttpUrl base=HttpUrl.get(new Library(context).endpoint());
        context.getSharedPreferences("api-account",0).edit().putString("origin",base.scheme()+"://"+base.host()+":"+base.port())
                .putString("token","local-test-placeholder").commit();
        try {
            try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(context,HomeActivity.class).putExtra("demo",true)).setup()) {
                HomeActivity home=controller.get();Field rail=HomeActivity.class.getDeclaredField("rail");rail.setAccessible(true);
                ((List<View>)rail.get(home)).get(HomeActivity.SETTINGS).performClick();
                Field active=HomeActivity.class.getDeclaredField("activeDialog");active.setAccessible(true);AlertDialog settings=(AlertDialog)active.get(home);
                assertEquals("已登录 · 账号管理",settings.getListView().getAdapter().getItem(3));settings.dismiss();
            }
            try(ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class).setup()) {
                assertEquals(0,controller.get().getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE);
                View root=controller.get().getWindow().getDecorView();assertNotNull(text(root,"当前状态：已登录"));
                View logout=text(root,"退出登录");assertNotNull(logout);logout.performClick();
                assertFalse(new AccountSession(context).available(base));assertNotNull(text(root,"当前状态：未登录"));
                assertEquals(View.GONE,logout.getVisibility());
            }
        } finally {new AccountSession(context).clear();}
    }
}
