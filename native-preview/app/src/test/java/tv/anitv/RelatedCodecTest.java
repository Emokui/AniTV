package tv.anitv;

import static org.junit.Assert.*;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class RelatedCodecTest {
    private byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test public void originalRelatedSchemaUsesImageFieldEightAndRelationTypeTen() throws Exception {
        ByteArrayOutputStream itemBytes=new ByteArrayOutputStream();CodedOutputStream item=CodedOutputStream.newInstance(itemBytes);
        item.writeUInt32(1,43);item.writeString(2,"测试番剧 第二季");item.writeString(3,"第二季");item.writeUInt32(4,12);item.writeUInt32(5,12);
        item.writeDouble(7,123456.0);item.writeString(8,"https://example.org/season2.jpg");item.writeString(9,"其他简介");item.writeString(10,"续作");item.writeString(99,"future");item.flush();
        ByteArrayOutputStream rootBytes=new ByteArrayOutputStream();CodedOutputStream root=CodedOutputStream.newInstance(rootBytes);root.writeByteArray(1,itemBytes.toByteArray());root.flush();
        JSONArray wire=new JSONArray();for(byte value:rootBytes.toByteArray())wire.put(value&255);
        List<Show> shows=RelatedCodec.decode(bytes(wire.toString()),42);
        assertEquals(1,shows.size());assertEquals(43,shows.get(0).id);assertEquals("测试番剧 第二季",shows.get(0).title);
        assertEquals("https://example.org/season2.jpg",shows.get(0).image);assertEquals("续作 · 第二季",shows.get(0).subtitle);
    }
    @Test public void removesCurrentEntryDuplicatesAndInvalidItemsWithoutReordering() throws Exception {
        List<Show> shows=RelatedCodec.decode(bytes("{\"data\":[{\"id\":42,\"title\":\"当前\"},{\"id\":43,\"title\":\"第二季\",\"type\":\"续作\"},{\"id\":44,\"title\":\"剧场版\"},{\"id\":43,\"title\":\"重复\"},{\"id\":0,\"title\":\"无效\"},{\"id\":45}]}"),42);
        assertEquals(2,shows.size());assertEquals(43,shows.get(0).id);assertEquals(44,shows.get(1).id);
        assertTrue(RelatedCodec.decode(bytes("[]"),42).isEmpty());
        assertThrows(java.io.IOException.class,()->RelatedCodec.decode(bytes("{\"error\":true}"),42));
    }
}
