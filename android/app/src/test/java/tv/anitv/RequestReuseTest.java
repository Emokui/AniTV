package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class RequestReuseTest {
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private void http(Interceptor interceptor)throws Exception{Field f=AniApp.class.getDeclaredField("http");f.setAccessible(true);f.set(RuntimeEnvironment.getApplication(),new OkHttpClient.Builder().addInterceptor(interceptor).build());}
    private Response response(Request request,String body){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    private void waitFor(CountDownLatch gate)throws java.io.IOException{try{if(!gate.await(4,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException error){throw new java.io.IOException(error);}}
    @Test public void repeatedEpisodeClicksShareOneRequestAndPlayerCanReuseItsResult()throws Exception{
        EpisodeCache.clear();AtomicInteger calls=new AtomicInteger();CountDownLatch release=new CountDownLatch(1);
        http(chain->{String path=chain.request().url().encodedPath();String body="[]";
            if(path.equals("/vod/episode")){calls.incrementAndGet();waitFor(release);body="[\"第1集\",\"第2集\"]";}
            else if(path.equals("/bangumi/detail/42"))body="{\"id\":42,\"title\":\"测试番剧\",\"overview\":\"简介\"}";
            return response(chain.request(),body);
        });
        try(ActivityController<DetailActivity> controller=Robolectric.buildActivity(DetailActivity.class,new Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("id",42L).putExtra("title","测试番剧")).setup()){
            DetailActivity detail=controller.get();await(()->calls.get()==1);
            for(int i=0;i<10;i++)((View)field(detail,"play")).performClick();assertEquals(1,calls.get());assertEquals(1,field(detail,"episodeGeneration"));
            release.countDown();await(()->{try{return field(detail,"episodeDialog")!=null;}catch(Exception e){throw new AssertionError(e);}});
            assertTrue(((AlertDialog)field(detail,"episodeDialog")).isShowing());
            try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){AtomicReference<List<Episode>> values=new AtomicReference<>();api.episodes(42,"测试番剧",result(values));await(()->values.get()!=null);assertEquals(2,values.get().size());assertEquals(1,calls.get());}
        }finally{release.countDown();}
    }
    private PlaybackApi.Result<List<Episode>> result(AtomicReference<List<Episode>> values){return new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> result){values.set(result);}public void error(String error){fail(error);}};}
    @Test public void repeatedRefreshDoesNotCancelAndRestartPendingRequest()throws Exception{
        AtomicInteger calls=new AtomicInteger();CountDownLatch release=new CountDownLatch(1);
        http(chain->{calls.incrementAndGet();waitFor(release);return response(chain.request(),"[{\"id\":42,\"title\":\"番剧\"}]");});
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup()){
            HomeActivity home=controller.get();await(()->calls.get()==1);int before=(Integer)field(home,"generation");
            for(int i=0;i<10;i++)((List<View>)field(home,"rail")).get(0).performClick();
            assertEquals(before,field(home,"generation"));assertEquals(1,calls.get());release.countDown();
            await(()->{try{return !(Boolean)field(home,"loading");}catch(Exception error){throw new AssertionError(error);}});
        }finally{release.countDown();}
    }
    @Test public void episodeCacheExpiresSeparatesAccountChangesAndHonorsCancellation()throws Exception{
        EpisodeCache.clear();AtomicInteger calls=new AtomicInteger();http(chain->{calls.incrementAndGet();return response(chain.request(),"[\"第1集\"]");});
        for(int round=0;round<4;round++){
            if(round==2)new AccountSession(RuntimeEnvironment.getApplication()).clear();
            if(round==3){
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(EpisodeCache.TTL_MS));
                java.io.File dir=new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");
                java.io.File[] files=dir.listFiles();if(files!=null)for(java.io.File file:files)file.setLastModified(System.currentTimeMillis()-EpisodeCache.TTL_MS-1000);
            }
            try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){AtomicReference<List<Episode>> values=new AtomicReference<>();api.episodes(42,"测试番剧",result(values));await(()->values.get()!=null);assertEquals(round<2?1:round,calls.get());}
        }
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){AtomicReference<List<Episode>> values=new AtomicReference<>();PlaybackApi.Task task=api.episodes(42,"测试番剧",result(values));task.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(values.get());assertEquals(3,calls.get());}
        long epoch=EpisodeCache.epoch();EpisodeCache.clear();EpisodeCache.put("old",java.util.Arrays.asList(new Episode(1,"")),epoch);assertNull(EpisodeCache.get("old"));
    }

    @Test public void catalogTabCacheIsScopedByAccountRevisionAndAvailability() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        HttpUrl base = HttpUrl.get(new Library(app).endpoint());
        app.getSharedPreferences("api-account", 0).edit()
                .putString("origin", base.scheme() + "://" + base.host() + ":" + base.port())
                .putString("iv", "iv-placeholder")
                .putString("token", "token-placeholder")
                .putLong("revision", 1)
                .commit();
        AtomicInteger listCalls = new AtomicInteger();
        http(chain -> {
            Request r = chain.request();
            boolean authed = new AccountSession(app).available(base);
            if (r.url().encodedPath().equals("/bangumi/list")) listCalls.incrementAndGet();
            if (!authed) return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                    .body(ResponseBody.create("", MediaType.get("application/json"))).build();
            String title = r.url().encodedPath().equals("/bangumi/list") ? "私有索引番剧" : "私有推荐番剧";
            return response(r, "[{\"id\":42,\"title\":\"" + title + "\"}]");
        });
        try (ActivityController<HomeActivity> controller = Robolectric.buildActivity(HomeActivity.class).setup().visible()) {
            HomeActivity home = controller.get();
            PosterAdapter adapter = (PosterAdapter) field(home, "adapter");
            await(() -> adapter.getItemCount() == 1);
            ((List<View>) field(home, "rail")).get(1).performClick();
            await(() -> adapter.getItemCount() == 1 && "私有索引番剧".equals(adapter.getCurrentList().get(0).title));
            assertEquals(1, listCalls.get());
            java.io.File catalogDir = new java.io.File(app.getCacheDir(), "catalog");
            await(() -> {
                java.io.File[] files = catalogDir.listFiles(f -> f.isFile() && !f.getName().endsWith(".tmp"));
                return files != null && files.length >= 2;
            });

            // Switch to History, log out, then switch back to Index: must not display logged-in disk cache.
            ((List<View>) field(home, "rail")).get(HomeActivity.HISTORY).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            new AccountSession(app).clear();
            controller.pause().resume();
            ((List<View>) field(home, "rail")).get(1).performClick();
            await(() -> {
                try { return listCalls.get() == 2 && AccountSession.LOGIN_REQUIRED.equals(field(home, "requestFailure")); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            assertEquals(0, adapter.getItemCount());
        } finally {
            app.getSharedPreferences("api-account", 0).edit().clear().commit();
        }
    }

    @Test public void detailAndRelatedDiskCachesAreScopedByAccountSession() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        HttpUrl base = HttpUrl.get(new Library(app).endpoint());
        app.getSharedPreferences("api-account", 0).edit()
                .putString("origin", base.scheme() + "://" + base.host() + ":" + base.port())
                .putString("iv", "iv-placeholder")
                .putString("token", "token-placeholder")
                .putLong("revision", 1)
                .commit();
        http(chain -> {
            Request r = chain.request();
            boolean authed = new AccountSession(app).available(base);
            if (!authed) return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                    .body(ResponseBody.create("", MediaType.get("application/json"))).build();
            if (r.url().encodedPath().equals("/bangumi/detail/42")) {
                return response(r, "{\"id\":42,\"title\":\"私有番剧\",\"overview\":\"仅登录可见简介\"}");
            }
            if (r.url().encodedPath().equals("/bangumi/related/42")) {
                return response(r, "[{\"id\":43,\"title\":\"私有番剧 第二季\",\"type\":\"续作\"}]");
            }
            return response(r, "[]");
        });
        try {
            Intent intent = new Intent(app, DetailActivity.class).putExtra("id", 42L).putExtra("title", "私有番剧");
            try (ActivityController<DetailActivity> first = Robolectric.buildActivity(DetailActivity.class, intent).setup().visible()) {
                DetailActivity detail = first.get();
                await(() -> {
                    try {
                        android.widget.TextView overview = (android.widget.TextView) field(detail, "overview");
                        View relatedBlock = (View) field(detail, "relatedBlock");
                        return "仅登录可见简介".contentEquals(overview.getText()) && relatedBlock.getVisibility() == View.VISIBLE;
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                java.io.File detailsDir = new java.io.File(app.getCacheDir(), "details");
                await(() -> {
                    java.io.File[] files = detailsDir.listFiles(f -> f.isFile() && !f.getName().endsWith(".tmp"));
                    return files != null && files.length >= 2;
                });
            }

            new AccountSession(app).clear();
            try (ActivityController<DetailActivity> second = Robolectric.buildActivity(DetailActivity.class, intent).setup().visible()) {
                DetailActivity detail = second.get();
                await(() -> {
                    try { return AccountSession.LOGIN_REQUIRED.equals(field(detail, "detailFailure")); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                android.widget.TextView overview = (android.widget.TextView) field(detail, "overview");
                View relatedBlock = (View) field(detail, "relatedBlock");
                assertNotEquals("仅登录可见简介", overview.getText().toString());
                assertEquals(View.GONE, relatedBlock.getVisibility());
            }
        } finally {
            app.getSharedPreferences("api-account", 0).edit().clear().commit();
        }
    }
}
