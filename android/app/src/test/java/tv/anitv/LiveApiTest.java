package tv.anitv;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
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
    private String sessionHeader() throws IOException {
        String token = System.getProperty("anitv.token", "").trim();
        if (token.isEmpty()) return "";
        return AccountCodec.csv(AccountCodec.fields(token, Long.toHexString(System.currentTimeMillis() + 60000)));
    }
    private byte[] fetch(Request request) throws Exception {
        Request.Builder builder = request.newBuilder()
                .header("User-Agent", "Emokui/AniTV/" + BuildConfig.VERSION_NAME + " (https://github.com/Emokui/AniTV)")
                .header("Accept", "application/x-protobuf, application/json");
        String auth = sessionHeader();
        if (!auth.isEmpty()) builder.header("_", auth);
        try (Response response = http.newCall(builder.build()).execute()) {
            return CatalogRepository.body(response);
        }
    }
    private CatalogCodec.Page fetchCatalogOrVerifyAuthGate(HttpUrl url, boolean hasToken) throws Exception {
        try {
            CatalogCodec.Page page = CatalogApi.decode(fetch(new Request.Builder().url(url).build()));
            assertFalse(page.shows.isEmpty());
            for (Show item : page.shows) assertFalse(item.fromBangumi());
            return page;
        } catch (IOException error) {
            if (hasToken) throw error;
            assertTrue("Unexpected guest catalog error: " + error.getMessage(), AccountSession.requiresLogin(error.getMessage()));
            assertEquals(AccountSession.LOGIN_REQUIRED, AccountSession.userFacingError(error.getMessage()));
            return null;
        }
    }
    @Test public void filteredPagesSearchDetailEpisodesSourcesAndMediaConnection() throws Exception {
        Assume.assumeTrue("Explicit live API test only", Boolean.getBoolean("anitv.live"));
        boolean hasToken = !System.getProperty("anitv.token", "").trim().isEmpty();
        HttpUrl base = HttpUrl.get(Library.DEFAULT_ENDPOINT);
        CatalogCodec.Page recommend = CatalogApi.decode(fetch(new Request.Builder().url(CatalogApi.url(base, 0, 0, "", "", "", "", false)).build()));
        assertFalse(recommend.shows.isEmpty());
        for (Show item : recommend.shows) assertFalse(item.fromBangumi());

        CatalogCodec.Page first = fetchCatalogOrVerifyAuthGate(CatalogApi.url(base, 1, 0, "", "tv", "ja", "2024", false), hasToken);
        if (first != null && first.next > 0) {
            CatalogCodec.Page second = CatalogApi.decode(fetch(new Request.Builder().url(CatalogApi.url(base, 1, first.next, "", "tv", "ja", "2024", false)).build()));
            Set<Long> ids = new HashSet<>();
            for (Show item : first.shows) ids.add(item.id);
            for (Show item : second.shows) assertFalse(ids.contains(item.id));
        }

        CatalogCodec.Page search = fetchCatalogOrVerifyAuthGate(CatalogApi.url(base, 3, 0, "间谍过家家", "", "", "", false), hasToken);
        List<Show> candidates = search != null ? search.shows : recommend.shows;

        boolean verifiedPlayback = false;
        int limit = Math.min(5, candidates.size());
        for (int idx = 0; idx < limit && !verifiedPlayback; idx++) {
            Show show = candidates.get(idx);
            assertFalse(show.fromBangumi());
            HttpUrl detailUrl = base.newBuilder().addPathSegment("bangumi").addPathSegment("detail").addPathSegment(Long.toString(show.id)).build();
            PlaybackHistory.Entry detail = HistoryMetadata.decode(fetch(new Request.Builder().url(detailUrl).build()),
                    new PlaybackHistory.Entry(show, base.toString(), new String[]{show.title}));
            assertEquals(show.id, detail.show.id);
            assertFalse(PlaybackHistory.missingTitle(detail.show));

            try {
                List<Show> related = RelatedCodec.decode(fetch(new Request.Builder().url(CatalogApi.related(base, show.id)).build()), show.id);
                assertNotNull(related);
            } catch (IOException error) {
                if (hasToken) throw error;
                assertTrue("Unexpected guest related error: " + error.getMessage(), AccountSession.requiresLogin(error.getMessage()));
            }

            List<Episode> episodes = PlaybackCodec.episodes(fetch(new Request.Builder().url(PlaybackApi.episodesUrl(base, show.id, detail.show.title)).build()));
            if (episodes.isEmpty()) continue;
            Episode episode = episodes.stream().filter(e -> e.number == 1).findFirst().orElse(episodes.get(0));
            SourceMerge merged = new SourceMerge();
            for (String query : MetadataTitles.queries(detail.titles)) {
                try {
                    merged.addAll(PlaybackCodec.sources(fetch(new Request.Builder().url(PlaybackApi.sourcesUrl(base, show.id, query, episode)).build())));
                    if (!merged.snapshot().isEmpty()) break;
                } catch (IOException ignored) { }
            }
            List<PlaySource> sources = merged.snapshot();
            for (PlaySource source : sources) {
                androidx.media3.datasource.HttpDataSource media = NativePlayback.httpDataSource(source).createDataSource();
                try {
                    media.open(new androidx.media3.datasource.DataSpec.Builder().setUri(source.url).setLength(32).build());
                    byte[] head = new byte[32];
                    if (media.read(head, 0, head.length) > 0) {
                        verifiedPlayback = true;
                        System.out.println("LIVE_OK recommend=" + recommend.shows.size()
                                + " list=" + (first == null ? "auth_gated" : first.shows.size())
                                + " search=" + (search == null ? "auth_gated" : search.shows.size())
                                + " show=" + show.id
                                + " episodes=" + episodes.size() + " sources=" + sources.size() + " media_connection=ok");
                        break;
                    }
                } catch (IOException ignored) {
                } finally {
                    media.close();
                }
            }
        }
        assertTrue("Expected at least one recommended/searched show to return playable episodes, sources, and readable media bytes", verifiedPlayback);
    }
}
