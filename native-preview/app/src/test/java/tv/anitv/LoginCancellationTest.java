package tv.anitv;
import static org.junit.Assert.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,instrumentedPackages={"tv.anitv"},shadows={LoginCancellationTest.Session.class,LoginCancellationTest.Codec.class})
public class LoginCancellationTest {
    static CountDownLatch parsed,release;static AtomicInteger saves;static boolean loggedIn;
    @Implements(value=AccountSession.class,isInAndroidSdk=false) public static class Session{
        @Implementation protected boolean available(HttpUrl url){return loggedIn;}
        @Implementation protected void save(HttpUrl url,String token){saves.incrementAndGet();}
        @Implementation protected void clear(){loggedIn=false;}
    }
    @Implements(value=AccountCodec.class,isInAndroidSdk=false) public static class Codec{
        @Implementation protected static String token(byte[] body){parsed.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return "test-token";}
    }
    @Before public void setup()throws Exception{
        loggedIn=false;parsed=new CountDownLatch(1);release=new CountDownLatch(1);saves=new AtomicInteger();
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(c->new Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("{}",MediaType.get("application/json"))).build()).build());
    }
    private TextView button(View root,String text){if(root instanceof TextView && text.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=button(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void submit(LoginActivity activity)throws Exception{
        ViewGroup root=(ViewGroup)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);int index=0;
        for(int i=0;i<root.getChildCount();i++)if(root.getChildAt(i) instanceof EditText)((EditText)root.getChildAt(i)).setText(index++==0?"test-user":"test-secret");
        button(root,loggedIn?"重新登录":"登录").performClick();assertTrue(parsed.await(2,TimeUnit.SECONDS));
    }
    @Test public void responseParsedAfterLeavingCannotSaveSession()throws Exception{
        try(ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class).setup()){
            LoginActivity activity=controller.get();submit(activity);controller.pause().stop().destroy();release.countDown();
            ExecutorService worker=(ExecutorService)RefreshRaceTest.field(activity,"worker");assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));assertEquals(0,saves.get());
        }finally{release.countDown();}
    }
    @Test public void logoutWhileReloginPendingRejectsTheOlderResult()throws Exception{
        loggedIn=true;
        try(ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class).setup()){
            LoginActivity activity=controller.get();submit(activity);button(activity.getWindow().getDecorView(),"退出登录").performClick();release.countDown();
            ((ExecutorService)RefreshRaceTest.field(activity,"worker")).submit(()->{}).get(2,TimeUnit.SECONDS);assertEquals(0,saves.get());assertFalse(loggedIn);
        }finally{release.countDown();}
    }
    @Test public void activeLoginStillSavesItsResult()throws Exception{
        try(ActivityController<LoginActivity> controller=Robolectric.buildActivity(LoginActivity.class).setup()){
            LoginActivity activity=controller.get();submit(activity);release.countDown();((ExecutorService)RefreshRaceTest.field(activity,"worker")).submit(()->{}).get(2,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,saves.get());assertTrue(activity.isFinishing());
        }finally{release.countDown();}
    }
}
