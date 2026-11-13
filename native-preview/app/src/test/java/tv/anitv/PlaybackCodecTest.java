package tv.anitv;

import static org.junit.Assert.*;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
}
