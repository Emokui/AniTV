package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class OriginalCatalogModesTest {
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!done.getAsBoolean() && System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(done.getAsBoolean());}
    @Test public void exactOriginalFilterMatrixPreservesOtherQueryFields(){
        HttpUrl base=HttpUrl.get(Library.DEFAULT_ENDPOINT);
        for(int section:new int[]{0,1,3})for(boolean adult:new boolean[]{false,true}){
            HttpUrl url=CatalogApi.url(base,section,88," 片名 ","tv","ja","2025",adult);
            Set<String> expected=new HashSet<>(Collections.singleton("skip"));
            if(!adult)expected.add("nsfw");
            if(section==1)expected.addAll(Arrays.asList("type","lang","year"));if(section==3)expected.add("keyword");
            assertEquals(expected,url.queryParameterNames());assertEquals(adult?null:"false",url.queryParameter("nsfw"));assertNull(url.queryParameter("res"));assertEquals("88",url.queryParameter("skip"));
        }
        assertEquals("/bangumi/related/42",CatalogApi.related(base,42).encodedPath());assertTrue(CatalogApi.related(base,42).queryParameterNames().isEmpty());
    }
    @Test public void upgradeRemovesOldResourcePreferenceAndKeepsSafetyChoice(){
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("native-library",0);
        prefs.edit().putBoolean("valid-only",true).putBoolean(ContentVisibility.PREFERENCE,false).commit();
        Library library=new Library(RuntimeEnvironment.getApplication());assertFalse(library.prefs.contains("valid-only"));assertFalse(library.safeMode());
    }
    private void setting(HomeActivity home,int index)throws Exception{
        ((List<View>)field(home,"rail")).get(HomeActivity.SETTINGS).performClick();
        ((AlertDialog)field(home,"activeDialog")).getListView().performItemClick(null,index,index);
    }
    @Test public void realHomeTogglesRequestServerFilteringForUnmarkedTitlesAndRefreshes()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();AtomicInteger lists=new AtomicInteger();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            Request r=chain.request();HttpUrl u=r.url();String body="[]";
            assertNull(u.queryParameter("safeMode"));assertNull(u.queryParameter("valid"));
            if(u.encodedPath().equals("/bangumi/list")){
                lists.incrementAndGet();
                // Actual list metadata need not contain any rating/resource flags.
                body="[{\"id\":30,\"title\":\"普通番剧\"}";
                if(u.queryParameter("nsfw")==null)body+=",{\"id\":20,\"title\":\"樱春女学院的男优\"}";
                assertNull(u.queryParameter("res"));body+=",{\"id\":10,\"title\":\"暂无片源的番剧\",\"episode\":0,\"status\":\"\"}";
                body+="]";
            }
            return new Response.Builder().request(r).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();((List<View>)field(home,"rail")).get(1).performClick();PosterAdapter adapter=(PosterAdapter)field(home,"adapter");
            await(()->adapter.getItemCount()==2);int before=lists.get();
            setting(home,5);await(()->adapter.getItemCount()==3);assertEquals(before+1,lists.get());
            setting(home,5);await(()->adapter.getItemCount()==2);assertEquals(before+2,lists.get());assertEquals(30,adapter.getItemId(0));
        }
    }
}
