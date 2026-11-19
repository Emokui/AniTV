package tv.anitv;
import static org.junit.Assert.*;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class SourceCollectionTest {
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    @Test public void brokenSourceDoesNotDiscardJsonOrProtobufSiblings()throws Exception{
        List<PlaySource> json=PlaybackCodec.sources("[{\"url\":\"https://a.example/1\"},{\"url\":\"\"},null,{\"url\":\"https://b.example/2\"}]".getBytes(StandardCharsets.UTF_8));assertEquals(2,json.size());
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();com.google.protobuf.CodedOutputStream out=com.google.protobuf.CodedOutputStream.newInstance(bytes);
        for(String url:new String[]{"https://a.example/1","","https://b.example/2"}){java.io.ByteArrayOutputStream item=new java.io.ByteArrayOutputStream();com.google.protobuf.CodedOutputStream fields=com.google.protobuf.CodedOutputStream.newInstance(item);fields.writeString(1,url);fields.flush();out.writeByteArray(1,item.toByteArray());}out.flush();
        assertEquals(2,PlaybackCodec.sources(bytes.toByteArray()).size());
    }
    @Test public void guestSourcesUseTwoConcurrentRequestsAndKeepMetadataOrder()throws Exception{
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);calls.incrementAndGet();entered.countDown();
            try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}finally{active.decrementAndGet();}
            String title=chain.request().url().queryParameter("title");return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("[{\"url\":\"https://media.example/"+title+"\",\"caption\":\""+title+"\"}]",MediaType.get("application/json"))).build();}).build());
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            AtomicReference<List<PlaySource>> result=new AtomicReference<>();api.sources(42,new String[]{"A","B","C"},new Episode(1,"第1集",true),new PlaybackApi.Result<List<PlaySource>>(){public void success(List<PlaySource> values){result.set(values);}public void error(String e){throw new AssertionError(e);}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(2,calls.get());release.countDown();await(()->result.get()!=null);
            assertEquals(3,calls.get());assertEquals(2,peak.get());assertEquals("A",result.get().get(0).caption);assertEquals("B",result.get().get(1).caption);assertEquals("C",result.get().get(2).caption);
        }finally{release.countDown();}
    }
    @Test public void cancelStopsBothGuestSourceRequests()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1),cancelled=new CountDownLatch(2);AtomicInteger callbacks=new AtomicInteger();
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().eventListener(new EventListener(){public void canceled(Call call){cancelled.countDown();}}).addInterceptor(chain->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new java.io.IOException(e);}return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create("[]",MediaType.get("application/json"))).build();}).build());
        try(PlaybackApi api=new PlaybackApi(RuntimeEnvironment.getApplication())){
            PlaybackApi.Task task=api.sources(42,new String[]{"A","B","C"},new Episode(1,"第1集",true),new PlaybackApi.Result<List<PlaySource>>(){public void success(List<PlaySource> v){callbacks.incrementAndGet();}public void error(String e){callbacks.incrementAndGet();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));task.cancel();assertTrue(cancelled.await(2,TimeUnit.SECONDS));release.countDown();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,callbacks.get());
        }finally{release.countDown();}
    }
    @Test public void slowAliasErrorsCannotReplaceLoginRequired() throws Exception {
        CountDownLatch thirdAlias = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            String title = request.url().queryParameter("title");
            if (!"A".equals(title)) {
                if ("C".equals(title)) thirdAlias.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                throw new java.net.SocketTimeoutException("请求超时");
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{\"error\":\"401\",\"message\":\"请先登录\"}", MediaType.get("application/json"))).build();
        }).build());
        try (PlaybackApi api = new PlaybackApi(RuntimeEnvironment.getApplication())) {
            AtomicReference<String> result = new AtomicReference<>();
            api.sources(42, new String[]{"A", "B", "C"}, new Episode(1, "第一集", true), new PlaybackApi.Result<List<PlaySource>>() {
                public void success(List<PlaySource> values) { fail("应显示登录提示"); }
                public void error(String error) { result.set(error); }
            });
            await(() -> thirdAlias.getCount() == 0);
            release.countDown();
            await(() -> result.get() != null);
            assertEquals(AccountSession.LOGIN_REQUIRED, result.get());
        } finally {
            release.countDown();
        }
    }
    @Test public void fullSourceRequestPreservesSharedLoginMessage() throws Exception {
        android.content.Context app = RuntimeEnvironment.getApplication();
        HttpUrl base = HttpUrl.get(new Library(app).endpoint());
        app.getSharedPreferences("api-account", 0).edit().putString("origin", base.scheme() + "://" + base.host() + ":" + base.port())
                .putString("iv", "test").putString("token", "test").apply();
        RefreshRaceTest.setHttp(new OkHttpClient.Builder().addInterceptor(chain -> {
            assertEquals("/vod/42/1", chain.request().url().encodedPath());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                    .body(ResponseBody.create("", null)).build();
        }).build());
        try (PlaybackApi api = new PlaybackApi(app)) {
            AtomicReference<String> result = new AtomicReference<>();
            api.sources(42, new String[]{"A"}, new Episode(1, "第一集", true), new PlaybackApi.Result<List<PlaySource>>() {
                public void success(List<PlaySource> values) { fail("应显示登录提示"); }
                public void error(String error) { result.set(error); }
            });
            await(() -> result.get() != null);
            assertEquals(AccountSession.LOGIN_REQUIRED, result.get());
        } finally {
            new AccountSession(app).clear();
        }
    }
}
