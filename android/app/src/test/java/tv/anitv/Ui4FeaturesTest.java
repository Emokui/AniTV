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
        String a=PlaybackHistory.progressKey("https://a.example",1),b=PlaybackHistory.progressKey("https://b.example",2);
        h.save(a,1,1000,100000);h.save(b,1,2000,100000);long revision=h.revision();
        assertEquals(2,h.removeAll(new HashSet<>(Arrays.asList(1L,2L)),"https://a.example"));assertTrue(h.revision()>revision);assertEquals(0,h.position(a,1));assertEquals(2000,h.position(b,1));assertEquals(1,h.entries("https://b.example").size());
    }
    @Test public void historyMenuSelectsWithoutOpeningDetailAndBackCancels()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(app,new OkHttpClient.Builder().addInterceptor(c->new Response.Builder().request(c.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create("[]",MediaType.get("application/json"))).build()).build());
        PlaybackHistory h=new PlaybackHistory(app);h.clear();String endpoint=new Library(app).endpoint();h.remember(new Show(42,"测试","https://example.org/cover.jpg",""),endpoint,new String[]{"测试"});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));assertEquals(true,field(home,"batchSelecting"));home.clicked(h.entries(endpoint).get(0).show);
            assertEquals(1,((Set<?>)field(home,"selectedIds")).size());assertNull(Shadows.shadowOf(home).getNextStartedActivity());home.onBackPressed();assertEquals(false,field(home,"batchSelecting"));assertEquals(1,h.entries(endpoint).size());
            h.save("42",1,5000,100000);home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));home.clicked(h.entries(endpoint).get(0).show);
            ((View)field(home,"deleteSelected")).performClick();AlertDialog confirm=(AlertDialog)field(home,"activeDialog");confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(h.entries(endpoint).isEmpty());assertFalse(h.hasProgress("42"));assertEquals(false,field(home,"batchSelecting"));
        }
    }
    @Test public void favoriteMenuRemovesOnlySelectedFavorites() throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        Library library=new Library(app);
        Show first=new Show(42,"作品 A","cover",""), second=new Show(43,"作品 B","cover","");
        library.toggle(first); library.toggle(second);
        PlaybackHistory history=new PlaybackHistory(app);
        history.remember(first,library.endpoint(),new String[]{first.title});
        history.save("42",2,5000,60000);
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();
            ((List<View>)field(home,"rail")).get(HomeActivity.FAVORITES).performClick();
            layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            ((android.widget.LinearLayout)field(home,"batchActions")).getChildAt(0).performClick();
            home.clicked(second);
            assertNull(Shadows.shadowOf(home).getNextStartedActivity());
            ((View)field(home,"deleteSelected")).performClick();
            ((AlertDialog)field(home,"activeDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(library.contains(42)); assertTrue(library.contains(43));
            assertEquals(5000,history.position("42",2)); assertNotNull(history.find(42,library.endpoint()));
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
    void await(java.util.function.BooleanSupplier ready)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;
        while(!ready.getAsBoolean() && System.nanoTime()<end){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertTrue(ready.getAsBoolean());
    }
    @Test public void menuFromSidebarFocusMovesIntoGridAndAllowsConfirmAndActionNavigation()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        PlaybackHistory history=new PlaybackHistory(app);history.clear();
        String endpoint=new Library(app).endpoint();
        history.remember(new Show(42,"历史 A","https://example.org/42.jpg",""),endpoint,new String[]{"历史 A"});
        Library library=new Library(app);
        library.prefs.edit().remove("favorites").commit();
        library.toggle(new Show(88,"收藏 A","https://example.org/88.jpg",""));
        for(int section:new int[]{HomeActivity.HISTORY,HomeActivity.FAVORITES}){
            try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class,new android.content.Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
                HomeActivity home=c.get();
                RecyclerView grid=(RecyclerView)field(home,"grid");
                List<View> rail=(List<View>)field(home,"rail");
                rail.get(section).performClick();
                await(()->grid.getAdapter().getItemCount()==1);
                layout(home.getWindow().getDecorView());
                rail.get(HomeActivity.SEARCH).requestFocus();
                assertTrue(rail.get(HomeActivity.SEARCH).hasFocus());
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU));
                layout(home.getWindow().getDecorView());
                assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
                assertEquals(true,field(home,"batchSelecting"));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));
                assertEquals(true,field(home,"batchSelecting"));
                assertEquals(1,((Set<?>)field(home,"selectedIds")).size());
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));
                android.widget.LinearLayout batchActions=(android.widget.LinearLayout)field(home,"batchActions");
                assertTrue(batchActions.getChildAt(0).hasFocus());
                assertFalse(rail.get(HomeActivity.SEARCH).hasFocus());
                assertFalse(rail.get(HomeActivity.SETTINGS).hasFocus());
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT));
                assertEquals(false,field(home,"batchSelecting"));
                assertTrue(rail.get(section).hasFocus());
            }
        }
    }
    @Test public void safeModeHydratingAdultEntryPrunesItFromBatchSelection()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        new Library(app).prefs.edit().putBoolean(ContentVisibility.PREFERENCE,true).commit();
        PlaybackHistory history=new PlaybackHistory(app);history.clear();
        String endpoint=new Library(app).endpoint();
        history.remember(new Show(43,"普通番剧","https://example.org/43.jpg",""),endpoint,new String[]{"普通番剧"});
        history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[0]);
        history.save("42",3,25000,100000);history.save("43",1,10000,100000);
        java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            if(chain.request().url().encodedPath().equals("/bangumi/detail/42")){
                try{gate.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{\"id\":42,\"title\":\"成人番剧\",\"image\":\"https://example.org/42.jpg\",\"nsfw\":true}",MediaType.get("application/json"))).build();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("[]",MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class,new android.content.Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=c.get();
            RecyclerView grid=(RecyclerView)field(home,"grid");
            ((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();
            await(()->grid.getAdapter().getItemCount()==2);
            layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            ((android.widget.LinearLayout)field(home,"batchActions")).getChildAt(0).performClick();
            Set<?> selected=(Set<?>)field(home,"selectedIds");
            assertTrue(selected.contains(42L));assertTrue(selected.contains(43L));
            gate.countDown();
            long deadline=System.nanoTime()+5_000_000_000L;
            while(selected.contains(42L) && System.nanoTime()<deadline){
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
                Thread.sleep(10);
            }
            assertFalse(selected.contains(42L));assertTrue(selected.contains(43L));
            assertEquals("删除(1)",((android.widget.TextView)field(home,"deleteSelected")).getText().toString());
            ((View)field(home,"deleteSelected")).performClick();
            ((AlertDialog)field(home,"activeDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(history.find(43,endpoint));
            assertNotNull(history.find(42,endpoint));
            assertEquals(25000,history.position("42",3));
        }finally{gate.countDown();}

        // Boundary: when the ONLY visible entry hydrates as r18=true during batch selection,
        // batch selection ends cleanly, selectedIds is empty, empty state appears, and rail gets focus.
        history.clear();
        history.remember(new Show(50,"历史番剧 #50","",""),endpoint,new String[0]);
        history.save("50",1,12000,100000);
        java.util.concurrent.CountDownLatch gateSingle=new java.util.concurrent.CountDownLatch(1);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            if(chain.request().url().encodedPath().equals("/bangumi/detail/50")){
                try{gateSingle.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{\"id\":50,\"title\":\"单条成人番剧\",\"nsfw\":true}",MediaType.get("application/json"))).build();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("[]",MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class,new android.content.Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=c.get();
            RecyclerView grid=(RecyclerView)field(home,"grid");
            List<View> rail=(List<View>)field(home,"rail");
            rail.get(HomeActivity.HISTORY).performClick();
            await(()->grid.getAdapter().getItemCount()==1);
            layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            home.clicked(history.entries(endpoint).get(0).show);
            assertEquals(1,((Set<?>)field(home,"selectedIds")).size());
            gateSingle.countDown();
            await(()->{try{return !(Boolean)field(home,"batchSelecting") && grid.getAdapter().getItemCount()==0;}catch(Exception e){throw new AssertionError(e);}});
            assertTrue(((Set<?>)field(home,"selectedIds")).isEmpty());
            assertEquals(View.VISIBLE,((View)field(home,"empty")).getVisibility());
            assertTrue(rail.get(HomeActivity.HISTORY).hasFocus());
        }finally{gateSingle.countDown();}
    }
    @Test public void offscreenBatchMenuLeftRestoresGridFocusabilityAndFocusedBatchBarRecoversToRailOnEmpty()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        new Library(app).prefs.edit().putBoolean(ContentVisibility.PREFERENCE,true).commit();
        PlaybackHistory history=new PlaybackHistory(app);history.clear();
        String endpoint=new Library(app).endpoint();
        for(int i=1;i<=30;i++){
            history.remember(new Show(i,"番剧 "+i,"https://example.org/"+i+".jpg",""),endpoint,new String[]{"番剧 "+i});
            history.save(Integer.toString(i),1,5000,100000);
        }
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class,new android.content.Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=c.get();
            RecyclerView grid=(RecyclerView)field(home,"grid");
            List<View> rail=(List<View>)field(home,"rail");
            rail.get(HomeActivity.HISTORY).performClick();
            await(()->grid.getAdapter().getItemCount()==30);
            layout(home.getWindow().getDecorView());
            int span=((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount();
            int offscreenColZero=span*4;
            Field focusedPos=HomeActivity.class.getDeclaredField("focusedPosition");focusedPos.setAccessible(true);
            focusedPos.setInt(home,offscreenColZero);
            rail.get(HomeActivity.HISTORY).requestFocus();
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU));
            assertEquals(true,field(home,"batchSelecting"));
            // Immediately press Left before postOnAnimation settles offscreen card focus.
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(false,field(home,"batchSelecting"));
            assertTrue(rail.get(HomeActivity.HISTORY).hasFocus());
            assertEquals(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS,grid.getDescendantFocusability());
        }

        // Focus on batchActions when the sole visible entry hydrates as r18=true must return focus to rail.
        history.clear();
        history.remember(new Show(60,"历史番剧 #60","",""),endpoint,new String[0]);
        history.save("60",1,12000,100000);
        java.util.concurrent.CountDownLatch gateBar=new java.util.concurrent.CountDownLatch(1);
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            if(chain.request().url().encodedPath().equals("/bangumi/detail/60")){
                try{gateBar.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{\"id\":60,\"title\":\"成人番剧60\",\"nsfw\":true}",MediaType.get("application/json"))).build();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("[]",MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class,new android.content.Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=c.get();
            RecyclerView grid=(RecyclerView)field(home,"grid");
            List<View> rail=(List<View>)field(home,"rail");
            rail.get(HomeActivity.HISTORY).performClick();
            await(()->grid.getAdapter().getItemCount()==1);
            layout(home.getWindow().getDecorView());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));
            android.widget.LinearLayout batchActions=(android.widget.LinearLayout)field(home,"batchActions");
            assertTrue(batchActions.hasFocus());
            gateBar.countDown();
            await(()->{try{return !(Boolean)field(home,"batchSelecting") && grid.getAdapter().getItemCount()==0;}catch(Exception e){throw new AssertionError(e);}});
            assertEquals(View.VISIBLE,((View)field(home,"empty")).getVisibility());
            assertTrue(rail.get(HomeActivity.HISTORY).hasFocus());
        }finally{gateBar.countDown();}
    }
}
