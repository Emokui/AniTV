package tv.anitv;
import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.app.Activity;
import android.view.View;
import android.view.KeyEvent;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;
import java.lang.reflect.*;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class Ui4FeaturesTest {
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    void layout(View root){root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));}
    @Test public void catalogRequestsOmitRemovedResourceFilter(){
        HttpUrl base=HttpUrl.get(Library.DEFAULT_ENDPOINT);
        for(int section:new int[]{0,1,3}){
            HttpUrl url=CatalogApi.url(base,section,70,"词","tv","ja","2026",false);
            assertNull(url.queryParameter("res"));assertNull(url.queryParameter("valid"));assertEquals("false",url.queryParameter("nsfw"));assertEquals("70",url.queryParameter("skip"));
        }
    }
    @Test public void bulkHistoryDeletionKeepsOtherEndpointProgressAndInvalidatesPendingEnrichment(){
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory h=new PlaybackHistory(app);h.clear();
        h.remember(new Show(1,"一","cover",""),"https://a.example",new String[]{"一"});h.remember(new Show(2,"二","cover",""),"https://a.example",new String[]{"二"});h.remember(new Show(2,"二","cover",""),"https://b.example",new String[]{"二"});
        h.save("1",1,1000,100000);h.save("2",1,2000,100000);long revision=h.revision();
        assertEquals(2,h.removeAll(new HashSet<>(Arrays.asList(1L,2L)),"https://a.example"));assertTrue(h.revision()>revision);assertEquals(0,h.position("1",1));assertEquals(2000,h.position("2",1));assertEquals(1,h.entries("https://b.example").size());
    }
    @Test public void historyMenuSelectsWithoutOpeningDetailAndBackCancels()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(app,new OkHttpClient.Builder().addInterceptor(c->new Response.Builder().request(c.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create("[]",MediaType.get("application/json"))).build()).build());
        PlaybackHistory h=new PlaybackHistory(app);h.clear();String endpoint=new Library(app).endpoint();h.remember(new Show(42,"测试","https://example.org/cover.jpg",""),endpoint,new String[]{"测试"});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));assertEquals(true,field(home,"historySelection"));home.clicked(h.entries(endpoint).get(0).show);
            assertEquals(1,((Set<?>)field(home,"selectedHistory")).size());assertNull(Shadows.shadowOf(home).getNextStartedActivity());home.onBackPressed();assertEquals(false,field(home,"historySelection"));assertEquals(1,h.entries(endpoint).size());
            h.save("42",1,5000,100000);home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));home.clicked(h.entries(endpoint).get(0).show);
            ((View)field(home,"deleteSelected")).performClick();AlertDialog confirm=(AlertDialog)field(home,"activeDialog");confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(h.entries(endpoint).isEmpty());assertFalse(h.hasProgress("42"));assertEquals(false,field(home,"historySelection"));
        }
    }
    @Test public void oneEpisodeHasNoLargeEmptyViewportAndAlwaysKeepsCover(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            for(boolean images:new boolean[]{false,true}){
                List<Episode> episodes=Collections.singletonList(new Episode(1,"第 1 集",false,images?"https://example.org/cover.jpg":"",0,null));
                AlertDialog dialog=EpisodePicker.create(c.get(),"单集","1",episodes,0,i->{});dialog.show();layout(dialog.getWindow().getDecorView());RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                assertTrue(grid.getHeight()<Ui.dp(c.get(),260));assertEquals(4,((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount());dialog.dismiss();
            }
        }
    }
}
