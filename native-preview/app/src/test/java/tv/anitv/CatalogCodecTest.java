package tv.anitv;

import static org.junit.Assert.*;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28, manifest = Config.NONE)
public class CatalogCodecTest {
    static byte[] item(int id, String title) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        out.writeUInt32(1, id); out.writeString(2, title); out.writeUInt32(3, 3); out.writeUInt32(4, 12);
        out.writeString(7, "https://example.org/poster.jpg"); out.writeString(99, "future-field"); out.flush(); return bytes.toByteArray();
    }
    static byte[] page(byte[]... items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        for (byte[] item : items) out.writeByteArray(1, item);
        out.flush(); return bytes.toByteArray();
    }
    @Test public void decodesUpstreamProtobufAndSkipsFutureFields() throws Exception {
        CatalogCodec.Page page = CatalogCodec.decode(page(item(420, "夏日来信")));
        assertEquals(420, page.shows.get(0).id); assertEquals("夏日来信", page.shows.get(0).title);
        assertEquals("更新至 3 / 12 集", page.shows.get(0).subtitle);
        assertEquals("https://example.org/poster.jpg", page.shows.get(0).image);
    }
    @Test public void deduplicatesRepeatedIdsFromBackend() throws Exception {
        assertEquals(1, CatalogCodec.decode(page(item(42, "A"), item(42, "A"))).shows.size());
    }
    @Test public void acceptsJsonEnvelopeAndCursor() throws Exception {
        CatalogCodec.Page page = CatalogCodec.decode("{\"data\":[{\"id\":7,\"title\":\"海边\",\"episode\":2}],\"next\":18}".getBytes(StandardCharsets.UTF_8));
        assertEquals(18, page.next); assertEquals("海边", page.shows.get(0).title);
    }
    @Test public void currentRecommendationsFlattenSectionsAndDeduplicateCards() throws Exception {
        String json = "{\"carousel\":[],\"data\":[{\"title\":\"热门\",\"list\":[{\"id\":1,\"title\":\"A\"}]},{\"title\":\"推荐\",\"list\":[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]}]}";
        CatalogCodec.Page page = CatalogCodec.decode(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, page.shows.size()); assertEquals(1, page.shows.get(0).id); assertEquals(2, page.shows.get(1).id);
    }
    @Test public void rejectsServiceErrorJsonInsteadOfShowingEmptySuccess() {
        assertThrows(IOException.class, () -> CatalogCodec.decode("{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void rejectsTruncatedBinaryPayload() throws Exception {
        byte[] data = page(item(1, "A"));
        assertThrows(IOException.class, () -> CatalogCodec.decode(Arrays.copyOf(data, data.length - 1)));
    }
    @Test public void incompatibleBinarySchemaCannotBecomeEmptySuccess() throws Exception {
        assertThrows(IOException.class, () -> CatalogCodec.decode(page(new byte[]{8, 1})));
    }
    @Test public void repeatedPageStopsAndKeepsExistingEntries() {
        PageAccumulator pages = new PageAccumulator();
        Show a = new Show(10, "A", "", ""), b = new Show(9, "B", "", "");
        CatalogCodec.Page page = new CatalogCodec.Page(Arrays.asList(a, b), 0);
        pages.append(page, true); assertEquals(9, pages.cursor()); assertFalse(pages.ended());
        pages.append(page, true); assertTrue(pages.ended()); assertEquals(2, pages.snapshot().size());
    }
    @Test public void overlappingPagesDoNotReorderExistingItems() {
        PageAccumulator pages = new PageAccumulator();
        Show a = new Show(10, "A", "", ""), b = new Show(9, "B", "", ""), c = new Show(8, "C", "", "");
        pages.append(new CatalogCodec.Page(Arrays.asList(a, b), 0), true);
        pages.append(new CatalogCodec.Page(Arrays.asList(b, c), 0), true);
        assertEquals(Arrays.asList(a, b, c), pages.snapshot()); assertEquals(8, pages.cursor()); assertFalse(pages.ended());
        pages.reset(); assertEquals(0, pages.cursor()); assertFalse(pages.ended()); assertTrue(pages.snapshot().isEmpty());
    }
}
