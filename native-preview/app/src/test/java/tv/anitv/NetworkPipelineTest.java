package tv.anitv;

import static org.junit.Assert.*;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class NetworkPipelineTest {
    @Test public void oldDiskEpisodeDoesNotReceiveAnotherTwelveHoursInMemory()throws Exception{
        EpisodeCache.clear();File dir=new File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");dir.mkdirs();File file=new File(dir,CatalogRepository.requestKey(episodeRequest()));
        Files.write(file.toPath(),"[\"第1集\"]".getBytes(StandardCharsets.UTF_8));assertTrue(file.setLastModified(System.currentTimeMillis()-EpisodeCache.TTL_MS+1000));
        AtomicInteger calls=new AtomicInteger(),errors=new AtomicInteger(),cached=new AtomicInteger();http(chain->{calls.incrementAndGet();return response(chain.request(),"[]",503);});
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> v){cached.incrementAndGet();}public void error(String e){errors.incrementAndGet();}});
            await(()->cached.get()==1);assertEquals(0,calls.get());org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
            // Advance the file age too: Robolectric elapsed time and host wall time are independent.
            assertTrue(file.setLastModified(System.currentTimeMillis()-EpisodeCache.TTL_MS-1000));
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> v){cached.incrementAndGet();}public void error(String e){errors.incrementAndGet();}});
            await(()->errors.get()==1);assertEquals(1,calls.get());assertEquals(1,cached.get());
        }
    }
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private void block(CountDownLatch gate)throws java.io.IOException{try{if(!gate.await(4,TimeUnit.SECONDS))throw new java.io.IOException("test gate timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}}
    private Response response(Request r,String body,int code){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("test").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    private void http(Interceptor interceptor)throws Exception{Field field=AniApp.class.getDeclaredField("http");field.setAccessible(true);field.set(RuntimeEnvironment.getApplication(),new OkHttpClient.Builder().addInterceptor(interceptor).build());}
    private Request episodeRequest(){AccountSession session=new AccountSession(RuntimeEnvironment.getApplication());HttpUrl base=HttpUrl.get(new Library(RuntimeEnvironment.getApplication()).endpoint());HttpUrl url=PlaybackApi.episodesUrl(base,42,"测试番剧");return new Request.Builder().url(url).tag(CatalogRepository.CacheScope.class,new CatalogRepository.CacheScope(session.revision()+":"+session.available(base)+":"+RuntimeEnvironment.getApplication().getSharedPreferences("episode-cache",0).getLong("revision",0))).build();}
    @Test public void twoSlowNetworkRequestsCannotBlockCachedPayloadDecoding()throws Exception{
        File directory=Files.createTempDirectory("cached-decode").toFile();CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(2),decoded=new CountDownLatch(2);
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{entered.countDown();block(release);return response(chain.request(),"[]",200);}).build();
        try(CatalogRepository repo=new CatalogRepository(client,directory)){
            for(String path:new String[]{"a","b"}){
                Request request=new Request.Builder().url("https://example.org/"+path).build();Files.write(new File(directory,CatalogRepository.requestKey(request)).toPath(),"[]".getBytes(StandardCharsets.UTF_8));
                repo.request(request,true,new CatalogRepository.Listener(){public void loaded(byte[] bytes,boolean cached){if(cached)repo.decode(decoded::countDown);}public void failed(String error){}});
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Cached decode must complete while BOTH network calls remain blocked",decoded.await(1,TimeUnit.SECONDS));
        }finally{release.countDown();}
    }
    @Test public void diskEpisodesSurviveLostMemoryWithoutRedundantRefresh()throws Exception{
        EpisodeCache.clear();AtomicInteger calls=new AtomicInteger();CountDownLatch release=new CountDownLatch(1);
        http(chain->{int n=calls.incrementAndGet();if(n>1)block(release);return response(chain.request(),n==1?"[\"第1集\",\"第2集\"]":"[]",n==1?200:503);});
        File directory=new File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");File cachedFile=new File(directory,CatalogRepository.requestKey(episodeRequest()));
        AtomicReference<List<Episode>> first=new AtomicReference<>();
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> values){first.set(values);}public void error(String error){fail(error);}});
            await(()->first.get()!=null && cachedFile.isFile());
        }
        EpisodeCache.clear(); // Memory eviction must not change the persistent disk key.
        AtomicReference<List<Episode>> local=new AtomicReference<>();AtomicInteger errors=new AtomicInteger();
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> values){local.set(values);}public void error(String error){errors.incrementAndGet();}});
            await(()->local.get()!=null);assertEquals(2,local.get().size());assertEquals(0,errors.get());assertEquals(1,release.getCount());
            release.countDown();assertEquals(1,calls.get());assertEquals(2,local.get().size());assertEquals(0,errors.get());
        }finally{release.countDown();}
    }
    @Test public void accountScopePreventsOldDiskEpisodesFromAppearingAfterLogout()throws Exception{
        EpisodeCache.clear();Request old=episodeRequest();File directory=new File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");directory.mkdirs();Files.write(new File(directory,CatalogRepository.requestKey(old)).toPath(),"[\"第1集\"]".getBytes(StandardCharsets.UTF_8));
        new AccountSession(RuntimeEnvironment.getApplication()).clear();assertNotEquals(CatalogRepository.requestKey(old),CatalogRepository.requestKey(episodeRequest()));
        http(chain->response(chain.request(),"[]",503));AtomicInteger cached=new AtomicInteger(),errors=new AtomicInteger();
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> values){cached.incrementAndGet();}public void error(String message){errors.incrementAndGet();}});
            await(()->errors.get()>0);assertEquals(0,cached.get());
        }
    }
    @Test public void freshDiskEpisodesDoNotRefreshOrChangeSelectionInOpenChooser()throws Exception{
        EpisodeCache.clear();Request old=episodeRequest();File directory=new File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");directory.mkdirs();
        Files.write(new File(directory,CatalogRepository.requestKey(old)).toPath(),"[\"第1集\",\"第2集\"]".getBytes(StandardCharsets.UTF_8));
        CountDownLatch release=new CountDownLatch(1);
        http(chain->{Request request=chain.request();String path=request.url().encodedPath(),body="[]";
            if(path.equals("/vod/episode")){block(release);body="[\"第2集\"]";}
            else if(path.equals("/bangumi/detail/42"))body="{\"id\":42,\"title\":\"测试番剧\",\"overview\":\"简介\"}";
            return response(request,body,200);
        });
        android.content.Intent intent=new android.content.Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("id",42L).putExtra("title","测试番剧");
        try(org.robolectric.android.controller.ActivityController<DetailActivity> controller=org.robolectric.Robolectric.buildActivity(DetailActivity.class,intent).setup().visible()){
            DetailActivity detail=controller.get();Field episodes=DetailActivity.class.getDeclaredField("episodes");episodes.setAccessible(true);
            await(()->{try{return ((List<?>)episodes.get(detail)).size()==2;}catch(Exception error){throw new AssertionError(error);}});
            Field play=DetailActivity.class.getDeclaredField("play");play.setAccessible(true);((android.view.View)play.get(detail)).performClick();
            Field chooserField=DetailActivity.class.getDeclaredField("episodeDialog");chooserField.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)chooserField.get(detail);
            release.countDown();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,((List<?>)episodes.get(detail)).size());
            androidx.recyclerview.widget.RecyclerView chooser=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(2,chooser.getAdapter().getItemCount());
            android.view.View root=dialog.getWindow().getDecorView();root.measure(android.view.View.MeasureSpec.makeMeasureSpec(960,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(540,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
            androidx.recyclerview.widget.RecyclerView.ViewHolder second=chooser.findViewHolderForAdapterPosition(1);assertNotNull(second);second.itemView.performClick();
            android.content.Intent player=Shadows.shadowOf(detail).getNextStartedActivity();assertNotNull(player);assertEquals(2,player.getIntExtra("episode",0));
        }finally{release.countDown();}
    }

    @Test public void corruptedFreshCacheFetchesNetworkExactlyOnce()throws Exception{
        EpisodeCache.clear();File directory=new File(RuntimeEnvironment.getApplication().getCacheDir(),"episode-metadata");directory.mkdirs();
        Files.write(new File(directory,CatalogRepository.requestKey(episodeRequest())).toPath(),"not valid metadata".getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls=new AtomicInteger();http(chain->{calls.incrementAndGet();return response(chain.request(),"[\"第1集\"]",200);});
        AtomicReference<List<Episode>> values=new AtomicReference<>();
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            api.episodes(42,"测试番剧",new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> v){values.set(v);}public void error(String e){fail(e);}});
            await(()->values.get()!=null);assertEquals(1,values.get().size());assertEquals(1,calls.get());
        }
    }
}
