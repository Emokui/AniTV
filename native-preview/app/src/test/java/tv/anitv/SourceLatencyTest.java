package tv.anitv;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

public class SourceLatencyTest {
    @Test public void measuresFirstByteWithPlaybackHeadersAndSmallRange() throws Exception {
        try (MockWebServer server = new MockWebServer(); SourceLatency latency = new SourceLatency()) {
            server.enqueue(new MockResponse().setResponseCode(206).setBody("v").setBodyDelay(150,TimeUnit.MILLISECONDS));
            PlaySource source = new PlaySource(server.url("/video.mp4").toString(), "线路", "", Map.of("Referer","https://example.org/","_","private-session"));
            SourceLatency.Result result = latency.measure(source);
            assertTrue(result.label,result.success()); assertTrue("Includes body first-byte wait",result.millis >= 100);
            RecordedRequest request = server.takeRequest(1,TimeUnit.SECONDS); assertNotNull(request);
            assertEquals("GET",request.getMethod()); assertEquals("bytes=0-0",request.getHeader("Range"));
            assertEquals("https://example.org/",request.getHeader("Referer")); assertNull(request.getHeader("_"));
        }
    }
    @Test public void httpFailureAndEmptyBodyAreNotFastSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer(); SourceLatency latency = new SourceLatency()) {
            PlaySource source = new PlaySource(server.url("/video").toString(),"线路","",Collections.emptyMap());
            server.enqueue(new MockResponse().setResponseCode(403));
            assertEquals("HTTP 403",latency.measure(source).label);
            server.enqueue(new MockResponse().setBody(""));
            assertEquals("空响应",latency.measure(source).label);
        }
    }
    @Test public void closingCancelsActiveProbeAndSuppressesCompletion() throws Exception {
        try (MockWebServer server = new MockWebServer(); SourceLatency latency = new SourceLatency()) {
            server.enqueue(new MockResponse().setBody("video").setBodyDelay(2,TimeUnit.SECONDS));
            PlaySource source = new PlaySource(server.url("/video").toString(),"线路","",Collections.emptyMap());
            CountDownLatch started = new CountDownLatch(1); AtomicInteger completions = new AtomicInteger();
            latency.start(Collections.singletonList(source),(index,result)->{
                if(result.label.equals("测速中…"))started.countDown(); else completions.incrementAndGet();
            });
            assertTrue(started.await(1,TimeUnit.SECONDS)); assertNotNull(server.takeRequest(1,TimeUnit.SECONDS));
            latency.close(); assertEquals(0,completions.get());
        }
    }
    @Test public void closingDefersSocketCleanupAndRepeatedCloseIsSafe() {
        java.util.ArrayList<Runnable> backgroundTasks = new java.util.ArrayList<>();
        SourceLatency latency = new SourceLatency(backgroundTasks::add);
        latency.close(); latency.close();
        assertEquals("Socket cleanup must be scheduled once instead of run on the dialog thread",1,backgroundTasks.size());
        backgroundTasks.get(0).run();
    }

    @Test public void invalidHeadersFinishProbeWithoutEscapingWorker()throws Exception{
        try(SourceLatency latency=new SourceLatency()){
            PlaySource source=new PlaySource("https://example.org/video.mp4","线路","",Map.of("Bad\nName","value"));
            assertEquals("线路参数无效",latency.measure(source).label);
            CountDownLatch done=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<String> label=new java.util.concurrent.atomic.AtomicReference<>();
            latency.start(Collections.singletonList(source),(index,result)->{if(!result.label.equals("测速中…")){label.set(result.label);done.countDown();}});
            assertTrue(done.await(1,TimeUnit.SECONDS));assertEquals("线路参数无效",label.get());
        }
    }

}
