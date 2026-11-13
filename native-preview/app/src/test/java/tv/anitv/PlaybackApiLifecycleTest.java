package tv.anitv;

import static org.junit.Assert.*;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class PlaybackApiLifecycleTest {
    @Test public void decodeFinishingAfterCloseCannotDeliverIntoClosedPage() throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("[]",MediaType.get("application/json"))).build()).build());
        PlaybackApi api=new PlaybackApi(app);
        CountDownLatch parsing=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger callbacks=new AtomicInteger();
        PlaybackApi.Parser<String> parser=bytes->{
            parsing.countDown();
            boolean waiting=true;
            while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}
            return "decoded";
        };
        Method request=PlaybackApi.class.getDeclaredMethod("request",HttpUrl.class,PlaybackApi.Parser.class,PlaybackApi.Result.class);
        request.setAccessible(true);
        request.invoke(api,HttpUrl.get("https://example.org/episodes"),parser,new PlaybackApi.Result<String>() {
            public void success(String value){callbacks.incrementAndGet();}
            public void error(String message){callbacks.incrementAndGet();}
        });
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(parsing.getCount()>0 && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertEquals(0,parsing.getCount());api.close();
            // An executor barrier proves the parser has returned and posted its callback.
            Field repository=PlaybackApi.class.getDeclaredField("repository");repository.setAccessible(true);
            Field worker=CatalogRepository.class.getDeclaredField("worker");worker.setAccessible(true);
            java.util.concurrent.ExecutorService executor=(java.util.concurrent.ExecutorService)worker.get(repository.get(api));
            release.countDown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,callbacks.get());
        } finally {release.countDown();api.close();}
    }
}
