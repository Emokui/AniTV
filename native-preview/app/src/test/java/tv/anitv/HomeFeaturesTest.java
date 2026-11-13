package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class HomeFeaturesTest {
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void invoke(Object value,String name)throws Exception{Method m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(value);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private void layout(HomeActivity home){View root=home.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);idle();}
    private PosterAdapter adapter(HomeActivity home){try{return (PosterAdapter)field(home,"adapter");}catch(Exception e){throw new AssertionError(e);}}
    @Test public void historyAboveFavoritesAndConfirmOpensDetailWithCoverAndKeepsProgress()throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();
        history.remember(new Show(42,"第一季","https://example.org/p.jpg","正篇"),new Library(app).endpoint(),new String[]{"第一季","season1"});history.save("42",7,90000,1200000);
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();List<View> rail=(List<View>)field(home,"rail");
            String[] labels={"推荐","索引","历史","收藏","搜索","设置"};assertEquals(labels.length,rail.size());
            for(int i=0;i<labels.length;i++)assertEquals(labels[i],rail.get(i).getContentDescription());
            rail.get(HomeActivity.HISTORY).performClick();await(()->adapter(home).getItemCount()==1);
            assertTrue(rail.get(HomeActivity.HISTORY).hasFocus());layout(home);
            RecyclerView grid=(RecyclerView)field(home,"grid");PosterAdapter.Holder holder=adapter(home).onCreateViewHolder(grid,0);adapter(home).onBindViewHolder(holder,0);holder.itemView.performClick();
            Intent player=Shadows.shadowOf(home).getNextStartedActivity();assertNotNull(player);
            assertEquals(DetailActivity.class.getName(),player.getComponent().getClassName());assertEquals(42,player.getLongExtra("id",0));
            assertEquals("https://example.org/p.jpg",player.getStringExtra("image"));assertEquals(7,history.lastEpisode("42",1));assertEquals(90000,history.position("42",7));
        }
    }
    @Test public void cacheMenuUsesConfirmationAndCancellationKeepsHistory()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();history.save("42",2,5000,100000);
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();List<View> rail=(List<View>)field(home,"rail");rail.get(HomeActivity.SETTINGS).performClick();
            AlertDialog settings=(AlertDialog)field(home,"activeDialog");settings.getListView().performItemClick(null,4,4);
            await(()->{try{AlertDialog dialog=(AlertDialog)field(home,"activeDialog");return dialog!=null&&dialog.getListView()!=null&&dialog.getListView().getCount()==5;}catch(Exception e){throw new AssertionError(e);}});
            AlertDialog cache=(AlertDialog)field(home,"activeDialog");cache.getListView().performItemClick(null,CacheManager.HISTORY,CacheManager.HISTORY);idle();
            AlertDialog confirm=(AlertDialog)field(home,"activeDialog");assertNotNull(confirm.getButton(AlertDialog.BUTTON_POSITIVE));
            confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertEquals(5000,history.position("42",2));
        }
    }
    @Test public void indexWindowDropsOldPagesAndUpReusesCachedEarlierCursor()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();List<Long> cursors=new CopyOnWriteArrayList<>();
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{Request request=chain.request();String body="[]";
            if(request.url().encodedPath().equals("/bangumi/list")){
                long cursor=Long.parseLong(request.url().queryParameter("skip"));cursors.add(cursor);
                int n=cursor==0?0:(int)((10000-cursor+1)/40);org.json.JSONArray rows=new org.json.JSONArray();
                try{for(Show show:CatalogWindowTest.page(n).shows)rows.put(new org.json.JSONObject().put("id",show.id).put("title",show.title));}catch(org.json.JSONException error){throw new java.io.IOException(error);}body=rows.toString();
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=controller.get();((List<View>)field(home,"rail")).get(1).performClick();await(()->adapter(home).getItemCount()==40);
            for(int i=1;i<6;i++){final int page=i;invoke(home,"more");await(()->{try{return !(Boolean)field(home,"loading")&&!(Boolean)field(home,"listApplying")&&((PageAccumulator)field(home,"pages")).cursor()==CatalogWindowTest.page(page).next;}catch(Exception e){throw new AssertionError(e);}});}
            await(()->adapter(home).getItemCount()==120&&adapter(home).getItemId(0)==CatalogWindowTest.page(3).shows.get(0).id);
            layout(home);RecyclerView grid=(RecyclerView)field(home,"grid");grid.scrollToPosition(0);layout(home);
            View first=grid.findViewHolderForAdapterPosition(0).itemView;first.requestFocus();Shadows.shadowOf(home).setCurrentFocus(first);
            PageAccumulator pages=(PageAccumulator)field(home,"pages");
            HttpUrl previous=CatalogApi.url(HttpUrl.get(new Library(app).endpoint()),1,pages.previousCursor(),"","","","");
            java.io.File cached=new java.io.File(app.getCacheDir(),"catalog/"+CatalogRepository.requestKey(new Request.Builder().url(previous).build()));
            await(cached::isFile);int calls=cursors.size();
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
            await(()->adapter(home).getCurrentList().stream().filter(show->show.id>0).findFirst().get().id==CatalogWindowTest.page(2).shows.get(0).id);
            assertEquals(120,adapter(home).getCurrentList().stream().filter(show->show.id>0).count());assertEquals("Fresh previous pages must not issue another request",calls,cursors.size());
            assertFalse(home.isFinishing());
        }
    }
    @Test public void clearingDownloadableCacheDoesNotOpenConfirmationOrProgressWindow()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        java.io.File directory=new java.io.File(app.getCacheDir(),"catalog");directory.mkdirs();java.io.File marker=new java.io.File(directory,"cached-entry");java.nio.file.Files.write(marker.toPath(),new byte[]{1});
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();((List<View>)field(home,"rail")).get(HomeActivity.SETTINGS).performClick();
            ((AlertDialog)field(home,"activeDialog")).getListView().performItemClick(null,4,4);
            await(()->{try{AlertDialog d=(AlertDialog)field(home,"activeDialog");return d!=null && d.getListView()!=null && d.getListView().getCount()==5;}catch(Exception error){throw new AssertionError(error);}});
            AlertDialog cache=(AlertDialog)field(home,"activeDialog");cache.getListView().performItemClick(null,CacheManager.METADATA,CacheManager.METADATA);idle();
            assertNotNull(field(home,"activeDialog"));assertEquals(7,((AlertDialog)field(home,"activeDialog")).getListView().getCount());await(()->!marker.exists());
        }
    }

    @Test public void rightFromAnotherSidebarFocusEntersDisplayedGridWithoutSelectingHoveredSection()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=controller.get();List<View> rail=(List<View>)field(home,"rail");rail.get(0).performClick();
            await(()->adapter(home).getItemCount()>0);layout(home);int generation=(Integer)field(home,"generation");
            rail.get(HomeActivity.SEARCH).requestFocus();Shadows.shadowOf(home).setCurrentFocus(rail.get(HomeActivity.SEARCH));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
            for(int i=0;i<5;i++){layout(home);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));}
            assertEquals(0,field(home,"section"));assertEquals(generation,field(home,"generation"));assertNull(field(home,"activeDialog"));
            assertTrue(((RecyclerView)field(home,"grid")).hasFocus());
        }
    }

    @Test public void topRowUpKeepsCardFocusAndBackStillReturnsToSidebar()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class).putExtra("demo",true)).setup().visible().windowFocusChanged(true)){
            HomeActivity home=controller.get();List<View> rail=(List<View>)field(home,"rail");rail.get(0).performClick();
            await(()->adapter(home).getItemCount()>0);layout(home);RecyclerView grid=(RecyclerView)field(home,"grid");
            int count=Math.min(((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount(),adapter(home).getItemCount());
            for(int i=0;i<count;i++){
                layout(home);View card=grid.findViewHolderForAdapterPosition(i).itemView;card.requestFocus();Shadows.shadowOf(home).setCurrentFocus(card);
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP));layout(home);
                assertTrue("Up at the top must keep the same card",card.hasFocus());assertFalse(rail.get(0).hasFocus());
            }
            home.onBackPressed();assertTrue(rail.get(0).hasFocus());assertFalse(home.isFinishing());
        }
    }

}
