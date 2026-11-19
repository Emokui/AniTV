package tv.anitv;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class SourcePreferenceTest {
    private SourcePreference preference(String show){return new SourcePreference(RuntimeEnvironment.getApplication(),Library.DEFAULT_ENDPOINT,show);}
    private PlaySource source(String provider,String caption,String path){return new PlaySource("https://cdn.example.org/"+path,provider+" · "+caption,"",Collections.emptyMap(),provider);}
    @Test public void reorderedEpisodesAndReopeningKeepProviderAndQuality() {
        SourcePreference saved=preference("42");saved.remember(source("mh-6","第01集(日语-4K) · 4K","ep1?token=old"));
        java.util.List<PlaySource> next=Arrays.asList(source("mh-6","第02集(日语-1080P) · 1080P","2-hd"),source("other","第02集 · 4K","2-other"),source("mh-6","第02集(日语-4K) · 4K","2-4k?token=new"));
        assertEquals(2,saved.match(next));assertEquals(2,preference("42").match(next));assertEquals(-1,preference("43").match(next));
    }
    @Test public void missingSourceDoesNotOverwritePreferenceAndManualDefaultDoes() {
        SourcePreference saved=preference("42");PlaySource selected=source("chosen","第1集 · 1080P","1");saved.remember(selected);
        PlaySource fallback=source("default","第2集 · 1080P","2");assertEquals(-1,saved.match(Collections.singletonList(fallback)));
        assertEquals(0,saved.match(Collections.singletonList(source("chosen","第3集 · 1080P","3"))));
        saved.remember(fallback);assertEquals(0,saved.match(Collections.singletonList(source("default","第3集 · 1080P","3"))));
    }
    @Test public void serviceAndQualityDifferencesNeverSelectSameRowByIndex() {
        SourcePreference saved=preference("42");saved.remember(source("same","第01集 · 1080P","1"));
        assertEquals(-1,saved.match(Collections.singletonList(source("same","第02集 · 4K","2"))));
        SourcePreference other=new SourcePreference(RuntimeEnvironment.getApplication(),"https://other.example.org","42");
        assertFalse(other.saved());
    }
    @Test public void jsonAndProtobufShareSourceIdentityAndStillMatchLegacyCaption() throws Exception {
        String url = "https://cdn.example.org/b";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        com.google.protobuf.CodedOutputStream out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
        out.writeString(1, url);
        out.writeString(4, " 第二版 1080P ");
        out.writeString(5, "p1 ");
        out.flush();
        PlaySource protobuf = PlaybackCodec.sources(CatalogCodecTest.page(bytes.toByteArray())).get(0);
        PlaySource json = PlaybackCodec.sources(("[{\"url\":\"" + url + "\",\"caption\":\"第二版 1080P\",\"provider\":\"p1\"}]")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(0);
        assertEquals(json.caption, protobuf.caption);
        assertEquals("p1 · 第二版 1080P", protobuf.displayCaption);
        SourceMerge merge = new SourceMerge();
        merge.addAll(Arrays.asList(json, protobuf));
        assertEquals(1, merge.snapshot().size());

        String key = PlaybackHistory.key(0, okhttp3.HttpUrl.get(Library.DEFAULT_ENDPOINT).toString()) + ":42:";
        RuntimeEnvironment.getApplication().getSharedPreferences("native-source-preferences", 0).edit()
                .putString(key + "provider", "p1").putString(key + "caption", "p1第二版1080p")
                .putString(key + "quality", "1080/").putString(key + "host", "cdn.example.org").putString(key + "type", "").apply();
        PlaySource other = source("p1", "第一版 1080P", "a");
        assertEquals(1, preference("42").match(Arrays.asList(other, json)));
        preference("42").remember(json);
        assertEquals(1, preference("42").match(Arrays.asList(other, protobuf)));
    }
    @Test public void separateProviderAndEmbeddedCaptionMatchSymmetricallyAndKeepProviderQuality() {
        PlaySource separateFirst = new PlaySource("https://cdn.example.org/1", "第一版 1080P", "", Collections.emptyMap(), "p1");
        PlaySource separateSecond = new PlaySource("https://cdn.example.org/2", "第二版 1080P", "", Collections.emptyMap(), "p1");
        PlaySource embeddedFirst = new PlaySource("https://cdn.example.org/1", "p1 · 第一版 1080P", "", Collections.emptyMap(), "");
        PlaySource embeddedSecond = new PlaySource("https://cdn.example.org/2", "p1 · 第二版 1080P", "", Collections.emptyMap(), "");

        SourcePreference prefA = preference("101");
        prefA.remember(separateSecond);
        assertEquals(1, prefA.match(Arrays.asList(embeddedFirst, embeddedSecond)));

        SourcePreference prefB = preference("102");
        prefB.remember(embeddedSecond);
        assertEquals(1, prefB.match(Arrays.asList(separateFirst, separateSecond)));

        SourcePreference prefQuality = preference("103");
        prefQuality.remember(new PlaySource("https://cdn.example.org/ep1", "备用 · 第01集", "", Collections.emptyMap(), "4K源"));
        PlaySource wrongQuality = new PlaySource("https://cdn.example.org/ep2-720", "主线 · 第02集 · 720P", "", Collections.emptyMap(), "4K源");
        PlaySource matchingProviderQuality = new PlaySource("https://cdn.example.org/ep2-4k", "主线 · 第02集", "", Collections.emptyMap(), "4K源");
        assertEquals(1, prefQuality.match(Arrays.asList(wrongQuality, matchingProviderQuality)));
        assertEquals(-1, prefQuality.match(Collections.singletonList(wrongQuality)));

        SourcePreference epNumber = preference("104");
        epNumber.remember(new PlaySource("https://cdn.example.org/1080", "第1080集", "", Collections.emptyMap(), "p1"));
        assertEquals(0, epNumber.match(Collections.singletonList(
                new PlaySource("https://cdn.example.org/1081", "p1 · 第1081集", "", Collections.emptyMap(), ""))));
        assertEquals(0, epNumber.match(Collections.singletonList(
                new PlaySource("https://cdn.example.org/1082", "第1082集 · p1", "", Collections.emptyMap(), ""))));
    }
    @Test public void numericAndMultiDotProvidersMatchSymmetricallyWithoutFalseQualityTokens() {
        SourcePreference numericA = preference("201");
        numericA.remember(new PlaySource("https://cdn.example.org/ep1", "第01集 · 1080P", "", Collections.emptyMap(), "21"));
        PlaySource wrongNumeric = new PlaySource("https://cdn.example.org/ep2-99", "99 · 第02集 · 1080P", "", Collections.emptyMap(), "");
        PlaySource rightNumeric = new PlaySource("https://cdn.example.org/ep2-21", "21 · 第02集 · 1080P", "", Collections.emptyMap(), "");
        assertEquals(1, numericA.match(Arrays.asList(wrongNumeric, rightNumeric)));

        SourcePreference numericB = preference("202");
        numericB.remember(new PlaySource("https://cdn.example.org/ep1-21", "21 · 第01集 · 1080P", "", Collections.emptyMap(), ""));
        assertEquals(1, numericB.match(Arrays.asList(wrongNumeric, rightNumeric)));
        assertEquals(1, numericB.match(Arrays.asList(
                new PlaySource("https://cdn.example.org/ep2-99", "第02集 · 1080P", "", Collections.emptyMap(), "99"),
                new PlaySource("https://cdn.example.org/ep2-21", "第02集 · 1080P", "", Collections.emptyMap(), "21"))));

        PlaySource multiSeparateWrong = new PlaySource("https://cdn.example.org/w", "备用 · 第02集 · 1080P HEVC", "", Collections.emptyMap(), "线路A · 其他");
        PlaySource multiSeparateRight = new PlaySource("https://cdn.example.org/r", "备用 · 第02集 · 1080P HEVC", "", Collections.emptyMap(), "线路A · 专线");
        PlaySource multiEmbeddedWrong = new PlaySource("https://cdn.example.org/w", "第02集 · 线路A · 其他 · 备用 · 1080P HEVC", "", Collections.emptyMap(), "");
        PlaySource multiEmbeddedRight = new PlaySource("https://cdn.example.org/r", "第02集 · 线路A · 专线 · 备用 · 1080P HEVC", "", Collections.emptyMap(), "");

        SourcePreference multiToEmbedded = preference("203");
        multiToEmbedded.remember(new PlaySource("https://cdn.example.org/1", "备用 · 第01集 · 1080P HEVC", "", Collections.emptyMap(), "线路A · 专线"));
        assertEquals(1, multiToEmbedded.match(Arrays.asList(multiEmbeddedWrong, multiEmbeddedRight)));
        assertEquals(-1, multiToEmbedded.match(Collections.singletonList(multiEmbeddedWrong)));

        SourcePreference embeddedToMulti = preference("204");
        embeddedToMulti.remember(new PlaySource("https://cdn.example.org/1", "第01集 · 线路A · 专线 · 备用 · 1080P HEVC", "", Collections.emptyMap(), ""));
        assertEquals(1, embeddedToMulti.match(Arrays.asList(multiSeparateWrong, multiSeparateRight)));
        assertEquals(-1, embeddedToMulti.match(Collections.singletonList(multiSeparateWrong)));

        SourcePreference twoK = preference("205");
        twoK.remember(new PlaySource("https://cdn.example.org/2k", "第01集", "", Collections.emptyMap(), "2K专线"));
        assertEquals(1, twoK.match(Arrays.asList(
                new PlaySource("https://cdn.example.org/1080", "第02集 · 1080P", "", Collections.emptyMap(), "2K专线"),
                new PlaySource("https://cdn.example.org/1440", "第02集 · 1440P", "", Collections.emptyMap(), "2K专线"))));

        SourcePreference noFalse4k = preference("206");
        noFalse4k.remember(new PlaySource("https://cdn.example.org/mp4k", "mp4k · 第01集", "", Collections.emptyMap(), "p1"));
        assertEquals(-1, noFalse4k.match(Collections.singletonList(
                new PlaySource("https://cdn.example.org/4k", "4K · 第02集", "", Collections.emptyMap(), "p1"))));
    }
}
