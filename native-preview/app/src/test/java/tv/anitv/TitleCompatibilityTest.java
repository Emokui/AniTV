package tv.anitv;

import static org.junit.Assert.*;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, application=AniApp.class)
public class TitleCompatibilityTest {
    @Test public void metadataAliasesKeepSeasonAndOriginalNames() throws Exception {
        assertArrayEquals(new String[]{"药屋少女的呢喃 第三季", "薬屋のひとりごと 第3期"}, MetadataTitles.read(
                new JSONObject("{\"titles\":[\"薬屋のひとりごと 第3期\",\"薬屋のひとりごと 第3期\"]}"), "药屋少女的呢喃 第三季"));
        assertArrayEquals(new String[]{"中文第二季","原名2","别名第二季"}, MetadataTitles.read(new JSONObject(
                "{\"name\":\"原名2\",\"infobox\":[{\"key\":\"别名\",\"value\":[{\"v\":\"别名第二季\"}]}]}"), "中文第二季"));
    }
    @Test public void parallelAliasesRetainPreferredNameForNextEpisode() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        new Library(app).prefs.edit().putString("endpoint",Library.DEFAULT_ENDPOINT).commit();
        List<String> queried = new CopyOnWriteArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request=chain.request();String title=request.url().queryParameter("title");queried.add(title);
            boolean original=title.equals("原名第3期");
            String body=original ? "[{\"url\":\"https://example.org/video.mp4\",\"caption\":\"同季线路\"}]" : "{\"message\":\"Assignment to constant variable.\"}";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(original?200:500).message("test")
                    .header("Content-Type","application/json").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build();
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(app,client);
        try(PlaybackApi api=new PlaybackApi(app)) {
            List<List<PlaySource>> results=new ArrayList<>();List<String> errors=new ArrayList<>();
            PlaybackApi.Result<List<PlaySource>> result=new PlaybackApi.Result<List<PlaySource>>() {
                public void success(List<PlaySource> value){results.add(value);}public void error(String message){errors.add(message);}
            };
            api.sources(42,new String[]{"中文第三季","原名第3期"},new Episode(1,"第1集",true),result);
            await(results,1);assertTrue(errors.isEmpty());assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("中文第三季","原名第3期")),new java.util.HashSet<>(queried));
            Field preferred=PlaybackApi.class.getDeclaredField("preferredTitles");preferred.setAccessible(true);
            assertEquals("原名第3期",((java.util.Map<?,?>)preferred.get(api)).get(42L));
            api.sources(42,new String[]{"中文第三季","原名第3期"},new Episode(2,"第2集",true),result);
            await(results,2);assertEquals(4,queried.size());
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("中文第三季","原名第3期")),new java.util.HashSet<>(queried.subList(2,4)));
        }
    }
    @Test public void exactDuplicateOnlyMergesAndQualityVariantsRemain() {
        SourceMerge merge=new SourceMerge();
        PlaySource hd=new PlaySource("https://example.org/a.mp4","site · 1080P","",java.util.Collections.emptyMap());
        PlaySource uhd=new PlaySource("https://example.org/a.mp4","site · 4K","",java.util.Collections.emptyMap());
        merge.addAll(java.util.Arrays.asList(hd,uhd,hd));assertEquals(2,merge.snapshot().size());
    }
    @Test public void queriesIncludeAllAliasesAndPreserveSeasonMarkers() {
        java.util.List<String> queries=java.util.Arrays.asList(MetadataTitles.queries(new String[]{"药屋少女的呢喃 第三季","学生会也有洞！"}));
        assertTrue(queries.contains("药屋少女的呢喃第三季"));assertTrue(queries.contains("学生会也有洞"));
        assertFalse(queries.contains("药屋少女的呢喃"));
    }
    private void await(List<?> results,int count) throws Exception {
        long end=System.nanoTime()+5_000_000_000L;
        while(results.size()<count && System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertEquals(count,results.size());
    }
}
