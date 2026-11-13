package tv.anitv;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import com.google.protobuf.CodedOutputStream;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28, manifest = Config.NONE)
public class CurrentApiTest {
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test public void titleEpisodesSortNumericallyAndKeepSpecialsDistinct() throws Exception {
        List<Episode> episodes = PlaybackCodec.episodes(bytes("[\"SP02\",\"第10集\",\"第2集\",\"第01话\",\"第1集\",\"SP01\"]"));
        assertEquals(5, episodes.size());
        assertEquals(1, episodes.get(0).number); assertEquals("第01话", episodes.get(0).label());
        assertEquals(2, episodes.get(1).number); assertEquals(10, episodes.get(2).number);
        assertTrue(episodes.get(3).number >= 1000000000);
        assertNotEquals(episodes.get(3).number, episodes.get(4).number);
    }
    @Test public void currentVodJsonByteArrayDecodesProviderAndVideo() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(bytes("https://example.org/movie.mp4"));
        String wireUrl = encoded.substring(0, 3) + "A" + encoded.substring(3);
        ByteArrayOutputStream itemBytes = new ByteArrayOutputStream(); CodedOutputStream item = CodedOutputStream.newInstance(itemBytes);
        item.writeString(1, wireUrl); item.writeUInt32(2, 1); item.writeString(4, "第01集"); item.writeString(5, "provider-21"); item.writeString(6, "测试番剧"); item.flush();
        ByteArrayOutputStream rootBytes = new ByteArrayOutputStream(); CodedOutputStream root = CodedOutputStream.newInstance(rootBytes);
        root.writeByteArray(1, itemBytes.toByteArray()); root.flush();
        JSONArray json = new JSONArray(); for (byte b : rootBytes.toByteArray()) json.put(b & 255);
        List<PlaySource> sources = PlaybackCodec.sources(bytes(json.toString()));
        assertEquals(1, sources.size()); assertEquals("https://example.org/movie.mp4", sources.get(0).url);
        assertEquals("provider-21 · 第01集", sources.get(0).caption);
    }
    @Test public void invalidJsonBytesAreRejected() {
        assertThrows(IOException.class, () -> WirePayload.unpack(bytes("[256]")));
        assertThrows(IOException.class, () -> WirePayload.unpack(bytes("[1.5]")));
        assertThrows(IOException.class, () -> WirePayload.unpack(bytes("[10,\"bad\"]")));
    }
    @Test public void publicCatalogRequestIncludesFiltersAndDistinctCacheIdentity() throws Exception {
        Request first = PublicCatalog.search(0, "", "tv", "日本", "2024");
        Request second = PublicCatalog.search(0, "", "movie", "中国", "2023");
        assertNotEquals(CatalogRepository.requestKey(first), CatalogRepository.requestKey(second));
        okio.Buffer body = new okio.Buffer(); first.body().writeTo(body);
        JSONObject json = new JSONObject(body.readUtf8());
        JSONObject filter = json.getJSONObject("filter");
        assertEquals(2, filter.getJSONArray("type").getInt(0));
        assertEquals("TV", filter.getJSONArray("tag").getString(0));
        assertEquals("日本", filter.getJSONArray("tag").getString(1));
        assertEquals(">=2024-01-01", filter.getJSONArray("air_date").getString(0));
        assertEquals("<2025-01-01", filter.getJSONArray("air_date").getString(1));
    }
    @Test public void publicCatalogHasIndependentIdsAndOffsetPagination() throws Exception {
        String item = "{\"id\":10380,\"name_cn\":\"命运石之门\",\"name\":\"STEINS;GATE\",\"images\":{\"common\":\"https://example.org/image.jpg\"},\"date\":\"2011-04-06\",\"platform\":\"TV\"}";
        CatalogCodec.Page page = PublicCatalog.decode(bytes("{\"total\":2,\"offset\":0,\"data\":[" + item + "]}"));
        assertEquals(1, page.next); assertTrue(page.shows.get(0).fromBangumi());
        assertEquals(10380, page.shows.get(0).metadataId());
        PageAccumulator pages = new PageAccumulator(); pages.append(page, true); assertFalse(pages.ended());
        pages.append(PublicCatalog.decode(bytes("{\"total\":2,\"offset\":1,\"data\":[" + item.replace("10380", "10381") + "]}")), true);
        assertTrue(pages.ended()); assertEquals(2, pages.snapshot().size());
    }
    @Test public void currentPlaybackUsesTitleAndExactEpisodeLabel() {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.get(Library.DEFAULT_ENDPOINT);
        assertEquals("/vod/episode", PlaybackApi.episodesUrl(base, 42, "测试 / 番剧").encodedPath());
        assertEquals("测试 / 番剧", PlaybackApi.episodesUrl(base, 42, "测试 / 番剧").queryParameter("title"));
        okhttp3.HttpUrl source = PlaybackApi.sourcesUrl(base, 42, "测试番剧", new Episode(1, "第01话", true));
        assertEquals("/vod/get", source.encodedPath()); assertEquals("第01话", source.queryParameter("episode"));
        assertEquals("/vod/42/1", PlaybackApi.sourcesUrl(okhttp3.HttpUrl.get("https://example.org"), 42, "测试番剧", new Episode(1, "")).encodedPath());
    }

    @Test public void canonicalEpisodeNumbersAreNotRenumberedWithinSeason() throws Exception {
        byte[] episode=java.util.Base64.getDecoder().decode("EDFCBuifpuiZqw==");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        com.google.protobuf.CodedOutputStream output=com.google.protobuf.CodedOutputStream.newInstance(bytes);
        output.writeByteArray(1,episode);output.flush();
        Episode first=PlaybackCodec.episodes(bytes.toByteArray()).get(0);
        assertEquals(49,first.number);assertFalse(first.named);
        okhttp3.HttpUrl base=okhttp3.HttpUrl.get(Library.DEFAULT_ENDPOINT);
        assertEquals("/bangumi/episodes/39528",PlaybackApi.canonicalEpisodesUrl(base,39528).encodedPath());
    }

    @Test public void episodeCoverDurationAndAvailabilitySurviveBothFormats() throws Exception {
        ByteArrayOutputStream itemBytes = new ByteArrayOutputStream();
        CodedOutputStream item = CodedOutputStream.newInstance(itemBytes);
        item.writeBool(1, true); item.writeUInt32(2, 49); item.writeUInt32(4, 1440);
        item.writeString(7, "https://example.org/episode.jpg"); item.writeString(8, "蝗虫"); item.flush();
        ByteArrayOutputStream rootBytes = new ByteArrayOutputStream(); CodedOutputStream root = CodedOutputStream.newInstance(rootBytes);
        root.writeByteArray(1, itemBytes.toByteArray()); root.flush();
        Episode wire = PlaybackCodec.episodes(rootBytes.toByteArray()).get(0);
        Episode json = PlaybackCodec.episodes(bytes("[{\"sort\":49,\"duration\":1440,\"image\":\"https://example.org/episode.jpg\",\"title\":\"蝗虫\",\"status\":true}]")).get(0);
        for (Episode episode : new Episode[]{wire, json}) {
            assertEquals(49, episode.number); assertEquals("https://example.org/episode.jpg", episode.image);
            assertEquals(1440, episode.duration); assertEquals(Boolean.TRUE, episode.available);
        }
        assertNull(PlaybackCodec.episodes(bytes("[\"第1集\"]")).get(0).available);
    }

}
