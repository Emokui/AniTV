package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class PlayerSourceMemoryTest {
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(condition.getAsBoolean());}
    private int count(PlayerActivity player){try{return ((List<?>)field(player,"sources")).size();}catch(Exception e){throw new AssertionError(e);}}
    private String source(String provider,int episode){return "{\"url\":\"http://127.0.0.1:1/"+provider+episode+".mp4\",\"caption\":\""+provider+" · 第"+episode+"集 · 1080P\",\"provider\":\""+provider+"\"}";}
    private ActivityController<PlayerActivity> launch(int episode){return Robolectric.buildActivity(PlayerActivity.class,new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("showId",42L).putExtra("title","线路记忆测试").putExtra("episode",episode)).create().start().resume();}
    @Test public void changingEpisodeAndReopeningRestoresChosenProviderAfterReordering()throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            Request request=chain.request();String body;
            if(request.url().encodedPath().equals("/vod/episode"))body="[\"第1集\",\"第2集\"]";
            else if("第2集".equals(request.url().queryParameter("episode")))body="["+source("default",2)+","+source("third",2)+","+source("chosen",2)+"]";
            else body="["+source("default",1)+","+source("chosen",1)+"]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<PlayerActivity> controller=launch(1)) {
            PlayerActivity player=controller.get();await(()->count(player)==2);
            player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog=(AlertDialog)field(player,"dialog");dialog.getListView().performItemClick(null,1,1);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,field(player,"sourceIndex"));
            await(()->{try{return ((List<?>)field(player,"episodes")).size()==2;}catch(Exception e){throw new AssertionError(e);}});
            player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT));await(()->count(player)==3);
            assertEquals(2,field(player,"sourceIndex"));assertEquals(2,field(player,"number"));
        }
        try(ActivityController<PlayerActivity> controller=launch(2)) {
            PlayerActivity player=controller.get();await(()->count(player)==3);assertEquals(2,field(player,"sourceIndex"));
        }
    }
}
