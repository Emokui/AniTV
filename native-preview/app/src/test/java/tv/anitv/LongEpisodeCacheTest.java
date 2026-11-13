package tv.anitv;
import static org.junit.Assert.*;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class LongEpisodeCacheTest {
    private void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!done.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(done.getAsBoolean());}
    private PlaybackApi.Result<List<Episode>> result(AtomicReference<List<Episode>> value){return new PlaybackApi.Result<List<Episode>>(){public void success(List<Episode> v){value.set(v);}public void error(String e){fail(e);}};}
    @Test public void fifteenHundredEpisodesAndCoversReuseFreshDiskWithZeroAdditionalRequests()throws Exception{
        EpisodeCache.clear();AtomicInteger calls=new AtomicInteger();StringBuilder body=new StringBuilder("[");
        for(int i=1;i<=1500;i++){if(i>1)body.append(',');body.append("{\"sort\":").append(i).append(",\"title\":\"episode\",\"image\":\"https://images.example/").append(i).append(".jpg\"}");}body.append(']');
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{calls.incrementAndGet();return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body.toString(),MediaType.get("application/json"))).build();}).build());
        AtomicReference<List<Episode>> first=new AtomicReference<>();
        try(PlaybackApi api=new PlaybackApi(app)){
            api.episodes(42,"长篇",result(first));await(()->first.get()!=null);
            File dir=new File(app.getCacheDir(),"episode-metadata");await(()->dir.listFiles()!=null && dir.listFiles().length>0);
        }
        EpisodeCache.clear();AtomicReference<List<Episode>> disk=new AtomicReference<>();
        try(PlaybackApi api=new PlaybackApi(app)){
            api.episodes(42,"长篇",result(disk));await(()->disk.get()!=null);
            assertEquals(1500,disk.get().size());assertEquals(1500,disk.get().get(1499).number);
            assertEquals("https://images.example/1500.jpg",disk.get().get(1499).image);assertEquals(1,calls.get());
        }
    }
}
