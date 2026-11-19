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
    @Test public void nullTokenCannotBecomeLoginSuccess() {
        byte[] response = "{\"body\":{\"token\":null}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> AccountCodec.token(response));
    }
    @Test public void leadingWhitespaceAndErrorMessagesAreHandled() throws Exception {
        assertEquals("valid-session", AccountCodec.token(" \n{\"body\":{\"token\":\"valid-session\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.io.IOException rejected = assertThrows(java.io.IOException.class,
                () -> AccountCodec.token("{\"error\":\"unauthorized\",\"message\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("unauthorized", rejected.getMessage());
        java.io.IOException empty = assertThrows(java.io.IOException.class,
                () -> AccountCodec.token("{\"error\":true,\"message\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("登录失败", empty.getMessage());
    }
    @Test public void malformedLoginJsonAndTruncatedBinaryUseLocalMessages() throws Exception {
        for (String malformed : new String[]{"\n{bad", "{bad"}) {
            java.io.IOException failure = assertThrows(java.io.IOException.class,
                    () -> AccountCodec.token(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(failure.getCause() instanceof org.json.JSONException);
            assertEquals("接口响应格式不匹配", WirePayload.readError(failure));
        }
        java.io.IOException binary = assertThrows(java.io.IOException.class,
                () -> AccountCodec.token("[26,47,10]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(binary instanceof com.google.protobuf.InvalidProtocolBufferException);
        assertEquals("接口响应格式不匹配", WirePayload.readError(binary));
        assertEquals("接口读取失败", WirePayload.readError(new java.io.IOException("")));
    }
    @Test public void numericAndString401MatchHttp401() throws Exception {
        for (String code : new String[]{"401", "\"401\""}) {
            for (String message : new String[]{"", "请先登录"}) {
                String error = WirePayload.error(new org.json.JSONObject("{\"error\":" + code + ",\"message\":\"" + message + "\"}"), "接口错误");
                assertEquals(AccountSession.LOGIN_REQUIRED, AccountSession.userFacingError(error));
            }
        }
        assertEquals(AccountSession.LOGIN_REQUIRED, AccountSession.userFacingError("HTTP 401：请求失败"));
        assertTrue(AccountSession.requiresLogin(AccountSession.LOGIN_REQUIRED));
    }
    @Test public void corruptedSessionClearsOnlyOnItsOriginAndAllowsGuestRequest() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("api-account", android.content.Context.MODE_PRIVATE);
        prefs.edit().clear().putString("origin", "https://api.example.org:443").putString("iv", "bad")
                .putString("token", "bad").putLong("revision", 7).commit();
        AccountSession session = new AccountSession(context);
        okhttp3.HttpUrl base = okhttp3.HttpUrl.get("https://api.example.org/bangumi/list");
        assertTrue(session.available(base));
        assertEquals("", session.header(okhttp3.HttpUrl.get("https://video.example.org/stream")));
        assertEquals(7, session.revision());
        assertEquals("", session.header(base));
        assertEquals(8, session.revision());
        assertFalse(session.available(base));
        assertEquals("", session.header(base));
        assertEquals(8, session.revision());
    }
}
