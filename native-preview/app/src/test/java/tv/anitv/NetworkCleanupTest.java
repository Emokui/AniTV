package tv.anitv;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

public class NetworkCleanupTest {
    @Test public void cancellationRunsOffTheCallingThread() throws Exception {
        Thread caller=Thread.currentThread();AtomicReference<Thread> cancelledOn=new AtomicReference<>();CountDownLatch cancelled=new CountDownLatch(1);
        Call call=new OkHttpClient.Builder().eventListener(new EventListener(){
            @Override public void canceled(Call call){cancelledOn.set(Thread.currentThread());cancelled.countDown();}
        }).build().newCall(new Request.Builder().url("https://example.org/").build());
        NetworkCleanup.cancel(call);
        assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertTrue(call.isCanceled());assertNotSame(caller,cancelledOn.get());
    }
}
