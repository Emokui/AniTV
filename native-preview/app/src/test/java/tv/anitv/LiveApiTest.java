package tv.anitv;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Opt-in network test: ./build-native.sh testDebugUnitTest -PliveApi=true --tests tv.anitv.LiveApiTest */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28, manifest=Config.NONE)
public class LiveApiTest {
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build();
    private byte[] fetch(Request request) throws Exception {
        try (Response response = http.newCall(request.newBuilder()
                .header("User-Agent", "Emokui/AniTV/2.0.0-preview.5 (https://github.com/Emokui/AniTV)").build()).execute()) {
            return CatalogRepository.body(response);
        }
    }
    @Test public void filteredPagesSearchDetailEpisodesSourcesAndMp4Ranges() throws Exception {
        Assume.assumeTrue("Explicit live API test only", Boolean.getBoolean("anitv.live"));
        CatalogCodec.Page first = PublicCatalog.decode(fetch(PublicCatalog.search(0, "", "tv", "日本", "2024")));
        assertFalse(first.shows.isEmpty()); assertTrue(first.next > 0);
        for (Show show : first.shows) assertTrue(show.subtitle.startsWith("2024-"));
        CatalogCodec.Page second = PublicCatalog.decode(fetch(PublicCatalog.search(first.next, "", "tv", "日本", "2024")));
        assertFalse(second.shows.isEmpty());
        Set<Long> ids = new HashSet<>(); for (Show show : first.shows) ids.add(show.id);
        for (Show show : second.shows) assertFalse(ids.contains(show.id));
        CatalogCodec.Page search = PublicCatalog.decode(fetch(PublicCatalog.search(0, "间谍过家家", "", "", "")));
        assertFalse(search.shows.isEmpty()); Show show = search.shows.get(0); assertTrue(show.fromBangumi());
        JSONObject detail = new JSONObject(new String(fetch(new Request.Builder().url(PublicCatalog.detail(show.metadataId())).build()), StandardCharsets.UTF_8));
        assertEquals(show.metadataId(), detail.getLong("id")); assertTrue(detail.has("summary"));
        HttpUrl base = HttpUrl.get(Library.DEFAULT_ENDPOINT);
        List<Episode> episodes = PlaybackCodec.episodes(fetch(new Request.Builder().url(PlaybackApi.episodesUrl(base, show.id, show.title)).build()));
        assertFalse(episodes.isEmpty()); Episode episode = episodes.stream().filter(e -> e.number == 1).findFirst().orElseThrow(AssertionError::new);
        List<PlaySource> sources = PlaybackCodec.sources(fetch(new Request.Builder().url(PlaybackApi.sourcesUrl(base, show.id, show.title, episode)).build()));
        assertFalse(sources.isEmpty());
        try (RangeMediaSource source = new RangeMediaSource(http, sources.get(0))) {
            byte[] head = new byte[32]; assertEquals(32, source.readAt(0, head, 0, head.length));
            assertEquals("ftyp", new String(head, 4, 4, StandardCharsets.US_ASCII));
            long size = source.getSize(); assertTrue(size > 1000000);
            byte[] tail = new byte[1024]; assertEquals(1024, source.readAt(size - 1024, tail, 0, tail.length));
            System.out.println("LIVE_OK first=" + first.shows.size() + " second=" + second.shows.size() + " search=" + search.shows.size() +
                    " episodes=" + episodes.size() + " sources=" + sources.size() + " media_bytes=" + size + " tail_seek=ok");
        }
    }
}
