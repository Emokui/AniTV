package tv.anitv;
import static org.junit.Assert.*;
import com.google.protobuf.CodedInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class AccountCodecTest {
    @Test public void loginFieldsAndSessionEnvelopeMatchPublishedWire() throws Exception {
        CodedInputStream in=CodedInputStream.newInstance(AccountCodec.fields("test-user","test-password"));
        assertEquals(10,in.readTag());assertEquals("test-user",in.readString());assertEquals(18,in.readTag());assertEquals("test-password",in.readString());assertTrue(in.isAtEnd());
        assertEquals("0,128,255",AccountCodec.csv(new byte[]{0,(byte)128,(byte)255}));
    }
    @Test public void errorResponseCannotBecomeLoginSuccess() {
        assertThrows(java.io.IOException.class,()->AccountCodec.token("{\"error\":true,\"message\":\"登录失败\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
