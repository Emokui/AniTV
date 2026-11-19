package tv.anitv;

import static org.junit.Assert.*;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class LongPressRefreshTest {
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private Object field(Object o,String name)throws Exception{return RefreshRaceTest.field(o,name);}
    private void hold(HomeActivity home)throws Exception{
        View rail=((List<View>)field(home,"rail")).get(0);rail.requestFocus();Shadows.shadowOf(home).setCurrentFocus(rail);
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));
    }
    private PlaybackApi.Result<List<Episode>> result(AtomicReference<List<Episode>> out){return new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> e){out.set(e);}public void error(String e){throw new AssertionError(e);}};}
    @Test public void lateEpisodeResponseCannotRefillInvalidatedMemoryOrDisk()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();EpisodeCache.clear();AtomicInteger calls=new AtomicInteger();
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain->{
            int n=calls.incrementAndGet();if(n==1)try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}
            String body=n==1?"[\"第1集\"]":"[\"第1集\",\"第2集\"]";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        File directory=new File(app.getCacheDir(),"episode-metadata");directory.mkdirs();File marker=new File(directory,"old-cache");Files.write(marker.toPath(),new byte[]{1});
        try(PlaybackApi api=new PlaybackApi(app);CacheManager manager=new CacheManager(app)){
            AtomicReference<List<Episode>> old=new AtomicReference<>();api.episodes(42,"测试番剧",result(old));await(()->calls.get()==1);
            manager.clearEpisodes();await(()->!marker.exists());release.countDown();await(()->old.get()!=null);assertEquals(0,directory.listFiles().length);
            AtomicReference<List<Episode>> fresh=new AtomicReference<>();api.episodes(42,"测试番剧",result(fresh));await(()->fresh.get()!=null);assertEquals(2,fresh.get().size());assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test public void holdStartsFreshRequestEvenWhenInitialLoadIsStillPending()throws Exception{
        AtomicInteger calls=new AtomicInteger();java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain->{
            int n=calls.incrementAndGet();if(n==1)try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("[{\"id\":99,\"title\":\"新推荐\"}]",MediaType.get("application/json"))).build();
        }).build());
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=controller.get();PosterAdapter adapter=(PosterAdapter)field(home,"adapter");await(()->calls.get()==1);hold(home);
            await(()->adapter.getItemCount()==1);assertEquals(2,calls.get());assertEquals(99,adapter.getItemId(0));
        }finally{release.countDown();}
    }
    @Test public void holdReallyDeletesEpisodeDiskMissesMemoryAndFetchesNewCatalogAndEpisodes()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();EpisodeCache.clear();
        File root=app.getCacheDir(),episodes=new File(root,"episode-metadata"),catalog=new File(root,"catalog");catalog.mkdirs();
        AtomicInteger catalogs=new AtomicInteger(),episodeCalls=new AtomicInteger();
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain->{
            Request request=chain.request();String body;
            if(request.url().encodedPath().equals("/vod/episode")){int n=episodeCalls.incrementAndGet();body=n==1?"[\"第1集\"]":"[\"第1集\",\"第2集\"]";}
            else{catalogs.incrementAndGet();assertEquals("no-cache",request.header("Cache-Control"));body="[{\"id\":99,\"title\":\"新推荐\"}]";}
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        Request request=new Request.Builder().url(CatalogApi.url(HttpUrl.get(new Library(app).endpoint()),0,0,"","","","")).build();
        Files.write(new File(catalog,CatalogRepository.requestKey(request)).toPath(),"[{\"id\":42,\"title\":\"旧推荐\"}]".getBytes(StandardCharsets.UTF_8));
        try(PlaybackApi api=new PlaybackApi(app);ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=controller.get();PosterAdapter adapter=(PosterAdapter)field(home,"adapter");
            await(()->adapter.getItemCount()==1);assertEquals(42,adapter.getItemId(0));assertEquals(0,catalogs.get());
            AtomicReference<List<Episode>> old=new AtomicReference<>();api.episodes(42,"测试番剧",result(old));
            await(()->old.get()!=null && episodes.listFiles()!=null && episodes.listFiles().length>0);assertEquals(1,old.get().size());
            AtomicReference<List<Episode>> memory=new AtomicReference<>();api.episodes(42,"测试番剧",result(memory));await(()->memory.get()!=null);assertEquals(1,episodeCalls.get());
            hold(home);await(()->adapter.getItemId(0)==99 && episodes.listFiles().length==0);assertEquals(1,catalogs.get());
            AtomicReference<List<Episode>> fresh=new AtomicReference<>();api.episodes(42,"测试番剧",result(fresh));await(()->fresh.get()!=null && episodes.listFiles().length>0);
            assertEquals(2,fresh.get().size());assertEquals(2,episodeCalls.get());
            AtomicReference<List<Episode>> again=new AtomicReference<>();api.episodes(42,"测试番剧",result(again));await(()->again.get()!=null);assertEquals(2,episodeCalls.get());assertEquals(1,catalogs.get());
        }
    }
}
