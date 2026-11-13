package tv.anitv;

import static org.junit.Assert.*;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28, manifest = Config.NONE)
public class PlaybackCodecTest {
    private static byte[] message(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        writer.write(out); out.flush(); return bytes.toByteArray();
    }
    interface Writer { void write(CodedOutputStream out) throws IOException; }
    @Test public void episodeWireSchemaPreservesNumbersAndSkipsUnknownFields() throws Exception {
        byte[] item = message(out -> { out.writeUInt32(2, 7); out.writeString(8, "旅途"); out.writeString(99, "future"); });
        List<Episode> episodes = PlaybackCodec.episodes(message(out -> out.writeByteArray(1, item)));
        assertEquals(7, episodes.get(0).number); assertEquals("旅途", episodes.get(0).title);
    }
    @Test public void decodesOriginalAniChObfuscatedUrlAndHlsHint() throws Exception {
        String raw = "https://example.org/stream?id=42";
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        String wire = encoded.substring(0, 3) + "!" + encoded.substring(3);
        byte[] item = message(out -> { out.writeString(1, wire); out.writeString(3, "hls"); out.writeString(4, "线路 A"); });
        PlaySource source = PlaybackCodec.sources(message(out -> out.writeByteArray(1, item))).get(0);
        assertEquals(raw, source.url); assertTrue(source.hls()); assertEquals("线路 A", source.caption);
    }
    @Test public void jsonSourcesKeepRequiredReferer() throws Exception {
        PlaySource s = PlaybackCodec.sources("{\"data\":[{\"url\":\"https://example.org/a.mp4\",\"headers\":{\"Referer\":\"https://example.org/\"}}]}".getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals("https://example.org/", s.headers.get("Referer")); assertFalse(s.hls());
    }
    @Test public void invalidSchemeCannotReachPlayer() {
        assertThrows(IOException.class, () -> PlaybackCodec.decodeUrl("file:///data/private"));
        assertThrows(IOException.class, () -> PlaybackCodec.decodeUrl("not-a-playback-url"));
    }
    @Test public void malformedEpisodeCannotBecomeEmptySuccess() {
        assertThrows(IOException.class, () -> PlaybackCodec.episodes("{\"error\":true}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void rangeReadsAreCorrectAndReusedWithinBlock() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            byte[] body = new byte[2048]; for (int i = 0; i < body.length; i++) body[i] = (byte) i;
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2047/2048").setBody(new Buffer().write(body)));
            RangeMediaSource source = new RangeMediaSource(new OkHttpClient(), new PlaySource(server.url("/movie.mp4").toString(), "", "", java.util.Collections.emptyMap()));
            byte[] target = new byte[16]; assertEquals(16, source.readAt(100, target, 0, 16));
            assertEquals(100, target[0] & 255); assertEquals(16, source.readAt(200, target, 0, 16));
            assertEquals(2048, source.getSize()); assertEquals(1, server.getRequestCount());
            assertEquals(-1, source.readAt(2048, target, 0, 16)); source.close();
            assertThrows(IOException.class, () -> source.readAt(0, target, 0, 16));
        }
    }
    @Test public void wrongRangeOffsetIsRejectedInsteadOfCorruptingPlayback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 20-29/30").setBody("1234567890"));
            RangeMediaSource source = new RangeMediaSource(new OkHttpClient(), new PlaySource(server.url("/movie.mp4").toString(), "", "", java.util.Collections.emptyMap()));
            assertThrows(IOException.class, () -> source.readAt(0, new byte[4], 0, 4)); source.close();
        }
    }
}
