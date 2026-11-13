package tv.anitv;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class CatalogApiTest {
    private final HttpUrl base=HttpUrl.get(Library.DEFAULT_ENDPOINT);
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test public void originalRoutesUseLanguageFiltersAndLastIdCursor() {
        HttpUrl index=CatalogApi.url(base,1,39528,"ignored","tv","ja","2025");
        assertEquals(base.host(),index.host());assertEquals("/bangumi/list",index.encodedPath());
        assertEquals("39528",index.queryParameter("skip"));assertEquals("ja",index.queryParameter("lang"));
        assertEquals("tv",index.queryParameter("type"));assertEquals("2025",index.queryParameter("year"));assertNull(index.queryParameter("keyword"));
        HttpUrl search=CatalogApi.url(base,3,39419," 药屋 少女 / 第二季 ","tv","ja","2025");
        assertEquals("/bangumi/search",search.encodedPath());assertEquals("药屋 少女 / 第二季",search.queryParameter("keyword"));
        assertEquals("39419",search.queryParameter("skip"));assertNull(search.queryParameter("type"));assertNull(search.queryParameter("lang"));
        assertThrows(IllegalArgumentException.class,()->CatalogApi.url(base,1,Show.BANGUMI_OFFSET+1,"","","",""));
    }
    @Test public void numericJsonProtobufListsKeepPosterAndCanonicalIdsAndStopOnEmpty() throws Exception {
        byte[] raw=CatalogCodecTest.page(CatalogCodecTest.item(39528,"药屋少女的呢喃 第三季"),CatalogCodecTest.item(39419,"学生会也有洞！"));
        JSONArray wrapped=new JSONArray();for(byte value:raw)wrapped.put(value&255);
        CatalogCodec.Page page=CatalogApi.decode(bytes(wrapped.toString()));
        assertEquals(2,page.shows.size());assertEquals(39419,page.next);
        assertFalse(page.shows.get(0).fromBangumi());assertEquals("https://example.org/poster.jpg",page.shows.get(0).image);
        PageAccumulator accumulator=new PageAccumulator();accumulator.append(page,true);assertFalse(accumulator.ended());
        accumulator.append(CatalogApi.decode(bytes("[]")),true);assertTrue(accumulator.ended());assertEquals(2,accumulator.snapshot().size());
    }
    @Test public void serverErrorsAreNotRenderedAsEmptySuccessfulSearch() {
        java.io.IOException error=assertThrows(java.io.IOException.class,()->CatalogApi.decode(bytes("{\"error\":true,\"message\":\"unauthorized\",\"data\":[]}")));
        assertEquals("unauthorized",error.getMessage());
    }
    @Test public void jsonEpisodeCountsAndCursorFollowOriginalLastItemRule() throws Exception {
        CatalogCodec.Page page=CatalogApi.decode(bytes("{\"data\":[{\"id\":39528,\"title\":\"药屋\",\"episodesTotal\":12}],\"next\":1}"));
        assertEquals("全 12 集",page.shows.get(0).subtitle);assertEquals(39528,page.next);
    }
}
