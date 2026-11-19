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
        assertThrows(IOException.class, () -> PlaybackCodec.episodes("{\"data\":{\"error\":true,\"message\":\"剧集不可用\"}}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void protobufPlaySourceWithBracketLengthAndClosingBracketDecodesAsBinary() throws Exception {
        String raw = "https://a.org/v/ep01.mp4";
        assertEquals(24, raw.length());
        String encoded = Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        String wireUrl = encoded.substring(0, 3) + "A" + encoded.substring(3);
        assertEquals(33, wireUrl.length());

        int providerLength = 91 - (2 + wireUrl.length() + 2);
        String provider = "Provider_1080P_" + "x".repeat(providerLength - 16) + "]";
        byte[] bracketItem = message(out -> {
            out.writeString(1, wireUrl);
            out.writeString(5, provider);
        });
        assertEquals(91, bracketItem.length);
        byte[] bracketWire = message(out -> out.writeByteArray(1, bracketItem));
        for (byte b : bracketWire) {
            assertTrue("must have no <0x20 non-whitespace control byte",
                    b < 0 || b >= 0x20 || b == ' ' || b == '\t' || b == '\r' || b == '\n');
        }
        // Confirm Android's lenient JSONTokener would have accepted this bare protobuf frame as a JSONArray.
        assertTrue(new org.json.JSONTokener(new String(bracketWire, StandardCharsets.UTF_8)).nextValue() instanceof org.json.JSONArray);
        List<PlaySource> decoded = PlaybackCodec.sources(bracketWire);
        assertEquals(1, decoded.size());
        assertEquals(raw, decoded.get(0).url);
        assertEquals(provider, decoded.get(0).provider);
        assertEquals("1080P", decoded.get(0).resolutionLabel());

        int braceProviderLength = 123 - (2 + wireUrl.length() + 2);
        String braceProvider = "Provider_4K_" + "x".repeat(braceProviderLength - 13) + "}";
        byte[] braceItem = message(out -> {
            out.writeString(1, wireUrl);
            out.writeString(5, braceProvider);
        });
        assertEquals(123, braceItem.length);
        List<PlaySource> braceDecoded = PlaybackCodec.sources(message(out -> out.writeByteArray(1, braceItem)));
        assertEquals(1, braceDecoded.size());
        assertEquals(raw, braceDecoded.get(0).url);
        assertEquals("4K", braceDecoded.get(0).resolutionLabel());
    }
    @Test public void malformedSourcePayloadsAndAllBrokenSourceListsThrowInsteadOfReturningEmpty() throws Exception {
        assertTrue(PlaybackCodec.sources("[]".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(PlaybackCodec.sources("{\"data\":[]}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(PlaybackCodec.sources(new byte[0]).isEmpty());
        for (String invalidJson : new String[]{
                "[bad]", "[\"bad\"]", "[123]", "[null]", "[{\"url\":\"\"}]",
                "[{\"url\":\"not-a-url\"},null]", "{\"data\":{\"error\":true,\"message\":\"源不可用\"}}"
        }) {
            assertThrows(invalidJson, IOException.class, () -> PlaybackCodec.sources(invalidJson.getBytes(StandardCharsets.UTF_8)));
        }
        byte[] validItem = message(out -> out.writeString(1, "https://example.org/ok.mp4"));
        byte[] corruptItem = new byte[]{10, 5, 'h', 't'}; // truncated string inside item
        assertThrows(IOException.class, () -> PlaybackCodec.sources(message(out -> {
            out.writeByteArray(1, validItem);
            out.writeByteArray(1, corruptItem);
        })));
        byte[] invalidUrlItem = message(out -> out.writeString(1, "not-a-playback-url"));
        assertThrows(IOException.class, () -> PlaybackCodec.sources(message(out -> out.writeByteArray(1, invalidUrlItem))));
    }
    @Test public void fallbackSourceCaptionsStaySequentialAcrossJsonAndProtobufWhenBrokenSiblingsSkip() throws Exception {
        List<PlaySource> json = PlaybackCodec.sources(
                "[{\"url\":\"\"},null,{\"url\":\"https://a.example/1\"},{\"url\":\"bad\"},{\"url\":\"https://b.example/2\"}]"
                        .getBytes(StandardCharsets.UTF_8));
        byte[] proto = message(out -> {
            for (String url : new String[]{"", "https://a.example/1", "bad", "https://b.example/2"}) {
                out.writeByteArray(1, message(item -> item.writeString(1, url)));
            }
        });
        List<PlaySource> binary = PlaybackCodec.sources(proto);
        assertEquals(2, json.size());
        assertEquals(2, binary.size());
        assertEquals("线路 1", json.get(0).caption);
        assertEquals("线路 2", json.get(1).caption);
        assertEquals(json.get(0).caption, binary.get(0).caption);
        assertEquals(json.get(1).caption, binary.get(1).caption);
    }
    @Test public void polyglotProtobufPlaySourceThatIsAlsoStrictRfc8259JsonDecodesAsProtobuf() throws Exception {
        String url = "https://cdn.example.org/ep001.m3u8";
        assertEquals(34, url.length());
        String caption = ", \"1080P_A";
        assertEquals(10, caption.length());
        String provider = "p".repeat(39) + "\"]";
        assertEquals(41, provider.length());
        byte[] item = message(out -> {
            out.writeString(1, url);
            out.writeString(4, caption);
            out.writeString(5, provider);
        });
        assertEquals(91, item.length);
        byte[] wire = message(out -> out.writeByteArray(1, item));
        // Raw bytes are simultaneously strict RFC 8259 JSON: \n["https://...", "1080P_A*)ppp..."]
        assertTrue(WirePayload.read(wire).json instanceof org.json.JSONArray);
        List<PlaySource> decoded = PlaybackCodec.sources(wire);
        assertEquals(1, decoded.size());
        assertEquals(url, decoded.get(0).url);
        assertEquals(provider, decoded.get(0).provider);
        assertEquals("1080P", decoded.get(0).resolutionLabel());

        assertThrows(IOException.class, () -> PlaybackCodec.sources("\n[\"bad\"]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> PlaybackCodec.sources("\n{\"error\":true,\"message\":\"源不可用\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
