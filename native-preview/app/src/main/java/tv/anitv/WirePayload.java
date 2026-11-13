package tv.anitv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;

/** Current endpoints sometimes JSON-encode a protobuf byte array. */
final class WirePayload {
    private WirePayload() {}
    static byte[] unpack(byte[] bytes) throws IOException {
        if (bytes.length > CatalogCodec.MAX_BYTES) throw new IOException("接口响应过大");
        int start = 0;
        while (start < bytes.length && Character.isWhitespace((char) bytes[start])) start++;
        if (start == bytes.length || bytes[start] != '[') return bytes;
        try {
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            if (array.length() == 0 || !(array.opt(0) instanceof Number)) return bytes;
            byte[] result = new byte[array.length()];
            for (int i = 0; i < result.length; i++) {
                Object value = array.get(i);
                if (!(value instanceof Number)) throw new IOException("二进制响应混入非数字字段");
                double n = ((Number) value).doubleValue();
                if (n < 0 || n > 255 || n != Math.floor(n)) throw new IOException("二进制响应字节无效");
                result[i] = (byte) (int) n;
            }
            return result;
        } catch (JSONException e) { throw new IOException("接口响应 JSON 格式无效", e); }
    }
}
