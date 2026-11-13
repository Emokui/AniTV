package tv.anitv;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class DetailRelatedTest {
    @Test public void relatedNavigationReleasesHiddenPageAndRebuildsItOnReturn()throws Exception{
        installApi("[{\"id\":43,\"title\":\"下一季\"}]");
        try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();RecyclerView grid=(RecyclerView)field(activity,"relatedGrid");await(()->grid.getAdapter().getItemCount()==1);layout(activity.getWindow().getDecorView());
            grid.findViewHolderForAdapterPosition(0).itemView.performClick();controller.pause().stop();
            assertNull(field(activity,"relatedGrid"));assertTrue(((java.util.List<?>)field(activity,"episodes")).isEmpty());assertEquals(true,field(activity,"contentReleased"));
            controller.restart().start().resume().visible();assertNotNull(field(activity,"relatedGrid"));assertEquals(false,field(activity,"contentReleased"));
            await(()->{try{return ((RecyclerView)field(activity,"relatedGrid")).getAdapter().getItemCount()==1;}catch(Exception e){throw new AssertionError(e);}});
        }
    }
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void installApi(String related)throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            Request request=chain.request();String path=request.url().encodedPath();String body;
            if(path.equals("/bangumi/detail/42"))body="{\"id\":42,\"title\":\"当前番剧\",\"overview\":\"介绍内容\"}";
            else if(path.equals("/bangumi/related/42"))body=related;
            else if(path.equals("/vod/episode"))body="[\"第1集\"]";
            else body="[]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    private ActivityController<DetailActivity> detail(){return Robolectric.buildActivity(DetailActivity.class,new Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("id",42L).putExtra("title","当前番剧")).setup().visible().windowFocusChanged(true);}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(condition.getAsBoolean());}
    private void layout(View root){root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void relatedCardOpensCorrectSeasonAndRemoteReturnsToPlay()throws Exception {
        installApi("{\"data\":[{\"id\":42,\"title\":\"当前\"},{\"id\":43,\"title\":\"下一季\",\"type\":\"续作\"},{\"id\":44,\"title\":\"剧场版\",\"type\":\"剧场版\"}]}");
        try(ActivityController<DetailActivity> controller=detail()) {
            DetailActivity activity=controller.get();RecyclerView grid=(RecyclerView)field(activity,"relatedGrid");
            await(()->grid.getAdapter().getItemCount()==2);layout(activity.getWindow().getDecorView());
            assertEquals(View.VISIBLE,((View)field(activity,"relatedBlock")).getVisibility());
            View play=(View)field(activity,"play");Shadows.shadowOf(activity).setCurrentFocus(play);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(activity.getWindow().getDecorView());
            RecyclerView.ViewHolder first=grid.findViewHolderForAdapterPosition(0);assertNotNull(first);assertTrue(first.itemView.hasFocus());
            Shadows.shadowOf(activity).setCurrentFocus(first.itemView);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));assertTrue(play.hasFocus());
            first.itemView.performClick();Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);assertEquals(DetailActivity.class.getName(),intent.getComponent().getClassName());assertEquals(43,intent.getLongExtra("id",0));assertEquals("下一季",intent.getStringExtra("title"));
        }
    }
    @Test public void emptyRelatedResponseDoesNotReserveSpace()throws Exception {
        installApi("[]");try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();await(()->{try{return (Integer)field(activity,"relatedRevision")>0;}catch(Exception e){throw new AssertionError(e);}});
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,((View)field(activity,"relatedBlock")).getVisibility());
            assertEquals(0,((RecyclerView)field(activity,"relatedGrid")).getAdapter().getItemCount());
        }
    }
}
