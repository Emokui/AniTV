package tv.anitv;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
public class RefreshRaceTest {
    static class QueueExecutor extends AbstractExecutorService {
        final java.util.LinkedList<Runnable> tasks=new java.util.LinkedList<>();boolean stopped;
        public void execute(Runnable task){if(stopped)throw new RejectedExecutionException();tasks.add(task);}
        public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;List<Runnable> pending=new java.util.ArrayList<>(tasks);tasks.clear();return pending;}
        public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}public boolean awaitTermination(long n,TimeUnit unit){return stopped;}
        void first(){tasks.removeFirst().run();}void last(){tasks.removeLast().run();}
    }
    static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static void setHttp(OkHttpClient client)throws Exception{Field f=AniApp.class.getDeclaredField("http");f.setAccessible(true);f.set(RuntimeEnvironment.getApplication(),client);}
    static QueueExecutor queue(CatalogRepository repo)throws Exception{Field f=CatalogRepository.class.getDeclaredField("worker");f.setAccessible(true);((ExecutorService)f.get(repo)).shutdownNow();QueueExecutor queue=new QueueExecutor();f.set(repo,queue);return queue;}
    static Response response(Request r,String body,int status){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("test").header("Content-Type","application/json").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    static String page(long id){return "{\"data\":[{\"id\":"+id+",\"title\":\"番剧"+id+"\"}]}";}
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    static boolean loading(HomeActivity home){try{return (Boolean)field(home,"loading");}catch(Exception e){throw new AssertionError(e);}}
    static PosterAdapter adapter(HomeActivity home){try{return (PosterAdapter)field(home,"adapter");}catch(Exception e){throw new AssertionError(e);}}
    private void cache(String value)throws Exception {
        cache(value,3,false);
    }
    private void cache(String value,int section,boolean expired)throws Exception {
        File directory=new File(RuntimeEnvironment.getApplication().getCacheDir(),"catalog");directory.mkdirs();
        Request request=new Request.Builder().url(CatalogApi.url(HttpUrl.get(new Library(RuntimeEnvironment.getApplication()).endpoint()),section,0,"","","","")).build();
        File file=new File(directory,CatalogRepository.requestKey(request));Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8));
        if(expired)assertTrue(file.setLastModified(System.currentTimeMillis()-13L*60*60*1000));
    }
    @Test public void recommendationUsesFreshCacheWithoutBackgroundRequest()throws Exception{
        cache(page(42),0,false);AtomicInteger calls=new AtomicInteger();setHttp(new OkHttpClient.Builder().addInterceptor(c->{calls.incrementAndGet();return response(c.request(),page(99),200);}).build());
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();await(()->adapter(home).getItemCount()==1&&!loading(home));assertEquals(42,adapter(home).getItemId(0));
            CatalogRepository.Job job=(CatalogRepository.Job)field(home,"job");((Future<?>)field(job,"cacheTask")).get(2,TimeUnit.SECONDS);assertNull(field(job,"task"));assertEquals(0,calls.get());
        }
    }
    @Test public void expiredRecommendationCacheFetchesNetwork()throws Exception{
        cache(page(42),0,true);AtomicInteger calls=new AtomicInteger();setHttp(new OkHttpClient.Builder().addInterceptor(c->{calls.incrementAndGet();return response(c.request(),page(99),200);}).build());
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();await(()->adapter(home).getItemCount()==1&&!loading(home));assertEquals(99,adapter(home).getItemId(0));assertEquals(1,calls.get());
        }
    }
    @Test public void invalidRecommendationCacheDoesNotLeaveLoadingStuck()throws Exception{
        cache("broken",0,false);AtomicInteger calls=new AtomicInteger();setHttp(new OkHttpClient.Builder().addInterceptor(c->{calls.incrementAndGet();return response(c.request(),page(99),200);}).build());
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();await(()->adapter(home).getItemCount()==1&&!loading(home));assertEquals(99,adapter(home).getItemId(0));assertEquals(1,calls.get());
        }
    }
    private ActivityController<HomeActivity> create(){return Robolectric.buildActivity(HomeActivity.class,new Intent(RuntimeEnvironment.getApplication(),HomeActivity.class)).create();}
    private ActivityController<HomeActivity> createSearch(){android.os.Bundle state=new android.os.Bundle();state.putInt("section",HomeActivity.SEARCH);return Robolectric.buildActivity(HomeActivity.class).create(state);}
    private QueueExecutor delayParsing(HomeActivity home)throws Exception {
        CatalogRepository.Job job=(CatalogRepository.Job)field(home,"job");long until=System.nanoTime()+2_000_000_000L;while(field(job,"task")==null && System.nanoTime()<until)Thread.sleep(2);Future<?> future=(Future<?>)field(job,"task");future.get(2,TimeUnit.SECONDS);Future<?> disk=(Future<?>)field(job,"cacheTask");if(disk!=null)disk.get(2,TimeUnit.SECONDS);
        QueueExecutor queue=queue((CatalogRepository)field(home,"repository"));idle();assertEquals(2,queue.tasks.size());return queue;
    }
    @Test public void lateValidCacheSurvivesInvalidNetworkResponse()throws Exception {
        cache(page(42));setHttp(new OkHttpClient.Builder().addInterceptor(c->response(c.request(),"{\"data\":\"bad\"}",200)).build());
        try(ActivityController<HomeActivity> controller=createSearch()){
            HomeActivity home=controller.get();QueueExecutor queued=delayParsing(home);
            queued.last();idle();assertFalse(loading(home));queued.first();idle();
            await(()->adapter(home).getItemCount()==1);
            assertEquals(1,adapter(home).getItemCount());assertEquals(42,adapter(home).getItemId(0));
            assertTrue(((TextView)field(home,"footer")).getText().toString().contains("缓存"));
        }
    }
    @Test public void lateCacheCannotOverwriteFreshNetworkData()throws Exception {
        cache(page(42));setHttp(new OkHttpClient.Builder().addInterceptor(c->response(c.request(),page(99),200)).build());
        try(ActivityController<HomeActivity> controller=createSearch()){
            HomeActivity home=controller.get();QueueExecutor queued=delayParsing(home);
            queued.last();idle();await(()->adapter(home).getItemCount()==1);assertEquals(99,adapter(home).getItemId(0));
            queued.first();idle();assertEquals(99,((PageAccumulator)field(home,"pages")).snapshot().get(0).id);assertEquals(99,adapter(home).getItemId(0));
        }
    }
    @Test public void manualRefreshKeepsRowsOnFailureAndBypassesCache()throws Exception {
        AtomicInteger calls=new AtomicInteger();java.util.concurrent.atomic.AtomicReference<Request> last=new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch release=new CountDownLatch(1);
        setHttp(new OkHttpClient.Builder().addInterceptor(c->{last.set(c.request());int n=calls.incrementAndGet();if(n>1)try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}return response(c.request(),n==1?page(42):"{\"message\":\"unavailable\"}",n==1?200:503);}).build());
        try(ActivityController<HomeActivity> controller=create()){
            HomeActivity home=controller.get();await(()->adapter(home).getItemCount()==1&&!loading(home));
            java.lang.reflect.Method refresh=HomeActivity.class.getDeclaredMethod("reload",boolean.class);refresh.setAccessible(true);refresh.invoke(home,true);assertEquals(42,adapter(home).getItemId(0));
            await(()->calls.get()==2);assertEquals("no-cache",last.get().header("Cache-Control"));
            release.countDown();await(()->!loading(home));assertEquals(42,adapter(home).getItemId(0));assertEquals(View.GONE,((View)field(home,"empty")).getVisibility());
        }finally{release.countDown();}
    }
    @Test public void returningAfterAccountChangeRetriesFailedDirectory()throws Exception {
        AtomicInteger calls=new AtomicInteger();setHttp(new OkHttpClient.Builder().addInterceptor(c->{int n=calls.incrementAndGet();return response(c.request(),n==1?"{\"message\":\"unauthorized\"}":page(99),n==1?500:200);}).build());
        try(ActivityController<HomeActivity> controller=create().start().resume()){
            HomeActivity home=controller.get();await(()->calls.get()==1&&!loading(home));controller.pause();
            new AccountSession(home).clear();controller.resume();await(()->adapter(home).getItemCount()==1&&!loading(home));assertEquals(2,calls.get());assertEquals(99,adapter(home).getItemId(0));
        }
    }
    @Test public void closingRepositorySuppressesAlreadyQueuedCallbacksAndLateSaves()throws Exception {
        File directory=Files.createTempDirectory("repo-close").toFile();AtomicInteger callbacks=new AtomicInteger();
        CatalogRepository repo=new CatalogRepository(new OkHttpClient.Builder().addInterceptor(c->response(c.request(),page(42),200)).build(),directory);
        CatalogRepository.Job job=repo.get(HttpUrl.get("https://example.org/list"),false,new CatalogRepository.Listener(){public void loaded(byte[] b,boolean c){callbacks.incrementAndGet();}public void failed(String e){callbacks.incrementAndGet();}});
        ((Future<?>)field(job,"task")).get(2,TimeUnit.SECONDS);repo.close();idle();assertEquals(0,callbacks.get());
        repo.save(HttpUrl.get("https://example.org/list"),new byte[]{1});repo.decode(()->fail("closed"));
    }
    @Test public void outOfOrderCacheWritesKeepNewestResponse()throws Exception {
        File directory=Files.createTempDirectory("repo-write").toFile();Request request=new Request.Builder().url("https://example.org/list").build();
        try(CatalogRepository repo=new CatalogRepository(new OkHttpClient(),directory)){
            QueueExecutor queued=queue(repo);repo.save(request,new byte[]{1});repo.save(request,new byte[]{2});queued.last();queued.first();
            assertArrayEquals(new byte[]{2},Files.readAllBytes(new File(directory,CatalogRepository.requestKey(request)).toPath()));
        }
    }
    @Test public void clearingEpisodesDoesNotDropQueuedCatalogWrite()throws Exception{
        File root=Files.createTempDirectory("separate-cache-epochs").toFile();File directory=new File(root,"catalog");Request request=new Request.Builder().url("https://example.org/list").build();
        try(CatalogRepository repo=new CatalogRepository(new OkHttpClient(),directory)){
            QueueExecutor queued=queue(repo);repo.save(request,new byte[]{2});CatalogRepository.clearEpisodeDisk(root);queued.first();
            assertArrayEquals(new byte[]{2},Files.readAllBytes(new File(directory,CatalogRepository.requestKey(request)).toPath()));
            repo.save(request,new byte[]{3});CatalogRepository.clearCaches(root);queued.first();assertFalse(new File(directory,CatalogRepository.requestKey(request)).exists());
        }
    }
}
