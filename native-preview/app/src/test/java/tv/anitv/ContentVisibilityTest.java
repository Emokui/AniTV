package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class ContentVisibilityTest {
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void await(BooleanSupplier done)throws Exception{
        long end=System.nanoTime()+5_000_000_000L;
        while(!done.getAsBoolean() && System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertTrue(done.getAsBoolean());
    }
    private PosterAdapter adapter(HomeActivity h){try{return (PosterAdapter)field(h,"adapter");}catch(Exception e){throw new AssertionError(e);}}
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test public void defaultIsHiddenAndExplicitLabelsDoNotConfuseOrdinaryNumbers(){
        Library library=new Library(RuntimeEnvironment.getApplication());assertFalse(library.showR18());
        for(String label:new String[]{"R18","R-18","r18+","18+","NSFW","Hentai","18禁","成人向","里番"})assertTrue(label,ContentVisibility.labeled(label));
        for(String label:new String[]{"第18集","2018/TV/动画","18岁","R15","高校18班","青春/恋爱/喜剧"})assertFalse(label,ContentVisibility.labeled(label));
        List<Show> shows=Arrays.asList(new Show(1,"普通番剧","","2018/TV"),new Show(2,"成人条目","","R18"));
        assertEquals(1,library.visible(shows).size());library.prefs.edit().putBoolean(ContentVisibility.PREFERENCE,false).commit();
        assertEquals(2,new Library(RuntimeEnvironment.getApplication()).visible(shows).size());assertEquals(2,shows.size());
    }
    @Test public void jsonFlagsAndTagsSurviveCatalogAndRelatedDecoding()throws Exception{
        for(String flag:new String[]{"\"nsfw\":true","\"r18\":true","\"tags\":[{\"name\":\"R18\"}]","\"genre\":\"成人向\""}){
            String json="[{\"id\":2,\"title\":\"条目\","+flag+"}]";
            assertTrue(CatalogApi.decode(bytes(json)).shows.get(0).r18);
            assertTrue(RelatedCodec.decode(bytes(json),1).get(0).r18);
        }
    }
    @Test public void serverVisibilityHasIndependentCacheKeysAndPreservesSearchFilters(){
        HttpUrl base=HttpUrl.get(Library.DEFAULT_ENDPOINT);
        for(int section:new int[]{0,1,3}){
            HttpUrl hidden=CatalogApi.url(base,section,20,"搜索","tv","ja","2026",false);
            HttpUrl shown=CatalogApi.url(base,section,20,"搜索","tv","ja","2026",true);
            assertEquals("false",hidden.queryParameter("nsfw"));assertNull(shown.queryParameter("nsfw"));
            assertNull(hidden.queryParameter("safeMode"));assertNull(shown.queryParameter("safeMode"));
            assertEquals("20",shown.queryParameter("skip"));assertEquals(hidden.encodedPath(),shown.encodedPath());
            assertNotEquals(CatalogRepository.requestKey(new Request.Builder().url(hidden).build()),CatalogRepository.requestKey(new Request.Builder().url(shown).build()));
        }
    }
    @Test public void filteringLeavesRawCursorAndWindowBoundariesIntact()throws Exception{
        CatalogCodec.Page page=CatalogApi.decode(bytes("[{\"id\":30,\"title\":\"普通\"},{\"id\":20,\"title\":\"隐藏\",\"nsfw\":true}]"));
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);pages.append(page,true);
        assertEquals(1,ContentVisibility.filter(pages.snapshot(),false).size());assertEquals(20,pages.cursor());assertFalse(pages.ended());
        assertEquals(2,pages.save().values.size());
    }
    @Test public void historyProgressAndFavoritesRetainFlagWithoutDeletingRecords(){
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Library library=new Library(app);PlaybackHistory history=new PlaybackHistory(app);
        Show adult=new Show(2,"条目","","",true);library.toggle(adult);history.remember(adult,library.endpoint(),new String[]{"条目"});history.save("2",3,10000,60000);
        assertTrue(library.favorites().get(0).r18);assertTrue(history.shows(library.endpoint()).get(0).r18);
        assertTrue(library.visible(history.shows(library.endpoint())).isEmpty());assertTrue(library.visible(library.favorites()).isEmpty());
        library.prefs.edit().putBoolean(ContentVisibility.PREFERENCE,false).commit();
        assertEquals(1,library.visible(history.shows(library.endpoint())).size());assertEquals(10000,history.position("2",3));
    }
    @Test public void detailMetadataRetainsExplicitNsfwForHistory()throws Exception{
        PlaybackHistory.Entry source=new PlaybackHistory.Entry(new Show(2,"旧名","",""),Library.DEFAULT_ENDPOINT,new String[]{"旧名"});
        PlaybackHistory.Entry entry=HistoryMetadata.decode(bytes("{\"id\":2,\"title\":\"条目\",\"nsfw\":true}"),source);
        assertTrue(entry.show.r18);
    }
    private void chooseVisibility(HomeActivity home,boolean show)throws Exception{
        ((List<View>)field(home,"rail")).get(HomeActivity.SETTINGS).performClick();
        AlertDialog settings=(AlertDialog)field(home,"activeDialog");assertEquals(7,settings.getListView().getCount());
        if(new Library(home).showR18()!=show)settings.getListView().performItemClick(null,5,5);
    }
    private AtomicInteger installCatalog(AniApp app,String body)throws Exception{
        AtomicInteger calls=new AtomicInteger();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{calls.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}).build());return calls;
    }
    @Test public void settingUsesDistinctServerQueryAndRefreshesWhenModeChanges()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();AtomicInteger calls=installCatalog(app,"[{\"id\":3,\"title\":\"普通\"},{\"id\":2,\"title\":\"隐藏\",\"nsfw\":true}]");
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();await(()->adapter(home).getItemCount()==1);int before=calls.get();
            chooseVisibility(home,true);await(()->adapter(home).getItemCount()==2);assertEquals(before+1,calls.get());
            await(()->{try{return new java.io.File(app.getCacheDir(),"catalog").listFiles().length>=2;}catch(Exception e){return false;}});
            chooseVisibility(home,false);await(()->adapter(home).getItemCount()==1);assertEquals(before+2,calls.get());
            chooseVisibility(home,true);await(()->adapter(home).getItemCount()==2);
        }
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            await(()->adapter(c.get()).getItemCount()==2);assertTrue(new Library(app).showR18());
        }
    }
    @Test public void fullyHiddenIndexPageStillOffersNextPageWithoutAutomaticScanning()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();AtomicInteger calls=installCatalog(app,"[{\"id\":3,\"title\":\"隐藏\",\"nsfw\":true}]");
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=c.get();await(()->calls.get()==1);((List<View>)field(home,"rail")).get(1).performClick();
            await(()->{try{return !(boolean)field(home,"loading") && ((PageAccumulator)field(home,"pages")).cursor()==3;}catch(Exception e){throw new AssertionError(e);}});
            assertEquals(0,adapter(home).getItemCount());assertEquals(View.VISIBLE,((View)field(home,"retryMore")).getVisibility());
            int before=calls.get();chooseVisibility(home,true);await(()->adapter(home).getItemCount()==1);assertEquals(before+1,calls.get());
            assertEquals(View.GONE,((View)field(home,"retryMore")).getVisibility());
        }
    }
    private void layout(HomeActivity home){
        View root=home.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void press(HomeActivity home,int key){
        home.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key));
        home.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,key));
    }
    @Test public void emptyPageRemoteCanReachNextPageAndReturnBeforeConfirmingLoad()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();AtomicInteger calls=new AtomicInteger();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            calls.incrementAndGet();boolean next="3".equals(chain.request().url().queryParameter("skip"));
            String body=next?"{\"data\":[{\"id\":2,\"title\":\"普通番剧\"}],\"next\":-1}":"[{\"id\":3,\"title\":\"隐藏\",\"r18\":true}]";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)){
            HomeActivity home=c.get();
            home.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->Shadows.shadowOf(home).setCurrentFocus(newFocus));
            List<View> rail=(List<View>)field(home,"rail");rail.get(1).performClick();
            await(()->{try{return !(boolean)field(home,"loading") && !(boolean)field(home,"listApplying") && ((PageAccumulator)field(home,"pages")).cursor()==3;}catch(Exception e){throw new AssertionError(e);}});
            layout(home);rail.get(1).requestFocus();Shadows.shadowOf(home).setCurrentFocus(rail.get(1));
            press(home,android.view.KeyEvent.KEYCODE_DPAD_RIGHT);press(home,android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            View emptyAction=((List<View>)field(home,"emptyActions")).get(0),next=(View)field(home,"retryMore");
            assertTrue(emptyAction.hasFocus());int before=calls.get();
            press(home,android.view.KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(next.hasFocus());
            press(home,android.view.KeyEvent.KEYCODE_DPAD_UP);assertTrue(emptyAction.hasFocus());assertEquals(before,calls.get());
            press(home,android.view.KeyEvent.KEYCODE_DPAD_DOWN);press(home,android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            await(()->adapter(home).getItemCount()==1);assertEquals(2,adapter(home).getItemId(0));assertEquals(before+1,calls.get());
        }
    }
    @Test public void restoringFilteredWindowKeepsZeroLeadingSlotsAndNextPageActionWithoutNetwork()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();AtomicInteger calls=installCatalog(app,"[]");
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);
        for(int n=0;n<4;n++)pages.append(new CatalogCodec.Page(Arrays.asList(new Show(10-n,"隐藏","","",true)),10-n),true);
        assertEquals(1,pages.offset());assertFalse(pages.ended());
        Library library=new Library(app);android.os.Bundle state=new android.os.Bundle();state.putInt("section",1);
        state.putSerializable("catalog-window",pages.save());state.putString("catalog-endpoint",library.endpoint());
        state.putLong("catalog-account",new AccountSession(app).revision());state.putLong("catalog-at",System.currentTimeMillis());
        state.putString("catalog-query",CatalogRepository.requestKey(new Request.Builder().url(CatalogApi.url(HttpUrl.get(library.endpoint()),1,0,"","","","",false)).build()));
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup(state).visible()){
            HomeActivity home=c.get();layout(home);assertEquals(0,adapter(home).getItemCount());assertEquals(0,field(home,"leadingSlots"));
            assertEquals(View.VISIBLE,((View)field(home,"retryMore")).getVisibility());assertEquals(7,((PageAccumulator)field(home,"pages")).cursor());assertEquals(0,calls.get());
        }
    }
    @Test public void adultTagArraysAndNamedObjectsAreParsedWithoutBroadTitleMatching()throws Exception{
        for(String value:new String[]{"\"成人\"","[\"成人\"]","[\"普通\",\" 成人 \"]","[{\"name\":\"成人\"}]","[{\"name\":\"R18\"}]"}){
            JSONObject item=new JSONObject("{\"tags\":"+value+"}");assertTrue(value,ContentVisibility.marked(item));
        }
        for(String value:new String[]{"[\"成人式\"]","[{\"name\":\"成年人的恋爱\"}]","[]","null","42"})assertFalse(value,ContentVisibility.marked(new JSONObject("{\"tags\":"+value+"}")));
        assertFalse(new Show(1,"成人式的故事","","普通动画").r18);
    }

    @Test public void defaultSafetyDoesNotReuseUi4CacheWithIncorrectModeParameter()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Library library=new Library(app);
        library.prefs.edit().putBoolean("show-r18",true).commit();assertTrue(library.safeMode());
        HttpUrl old=CatalogApi.url(HttpUrl.get(library.endpoint()),0,0,"","","","",false).newBuilder().removeAllQueryParameters("nsfw").addQueryParameter("safeMode","true").build();
        java.io.File dir=new java.io.File(app.getCacheDir(),"catalog");dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir,CatalogRepository.requestKey(new Request.Builder().url(old).build())).toPath(),bytes("[{\"id\":99,\"title\":\"旧缓存未标记条目\"}]"));
        AtomicInteger calls=new AtomicInteger();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{assertEquals("false",chain.request().url().queryParameter("nsfw"));assertNull(chain.request().url().queryParameter("safeMode"));calls.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("[{\"id\":42,\"title\":\"安全模式结果\"}]",MediaType.get("application/json"))).build();}).build());
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            await(()->adapter(c.get()).getItemCount()==1);assertEquals(42,adapter(c.get()).getItemId(0));assertEquals(1,calls.get());
        }
    }

}
