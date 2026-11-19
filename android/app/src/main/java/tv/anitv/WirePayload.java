package tv.anitv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Current endpoints sometimes JSON-encode a protobuf byte array. */
final class WirePayload {
    final Object json;
    final byte[] bytes;
    private WirePayload(Object json, byte[] bytes) { this.json = json; this.bytes = bytes; }
    static String text(Object value, String fallback) {
        return value == null || value == JSONObject.NULL ? fallback : value.toString();
    }
    private static boolean whitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
    static Boolean flag(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == 1) return true;
            if (number == 0) return false;
        } else if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return null;
    }
    static String error(JSONObject response, String fallback) {
        Object value = response.opt("error");
        Boolean flag = flag(value);
        String reason = value instanceof String ? ((String) value).trim() : "";
        boolean numericCode = value instanceof Number && flag == null && ((Number) value).doubleValue() != 0;
        if (!numericCode && !Boolean.TRUE.equals(flag) && (flag != null || reason.isEmpty())) return null;
        String message = text(response.opt("message"), "").replaceAll("[\\p{Cntrl}]", " ").trim();
        if ("401".equals(reason) || numericCode && ((Number) value).doubleValue() == 401) {
            return message.isEmpty() ? "错误码 401" : "错误码 401 · " + message.substring(0, Math.min(160, message.length()));
        }
        if (!message.isEmpty()) return message.substring(0, Math.min(160, message.length()));
        if (numericCode) return fallback + "（错误码 " + value + "）";
        return flag == null ? reason.substring(0, Math.min(160, reason.length())) : fallback;
    }
    static String readError(IOException error) {
        if (error instanceof InvalidProtocolBufferException) {
            android.util.Log.w("AniTVNet", "Protobuf 响应无效", error);
            return "接口响应格式不匹配";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "接口读取失败" : message;
    }
    static byte[] unpack(byte[] bytes) throws IOException {
        return read(bytes).bytes;
    }
    static JSONObject object(byte[] bytes) throws IOException, JSONException {
        WirePayload payload = read(bytes);
        if (payload.json instanceof JSONObject) return (JSONObject) payload.json;
        if (payload.bytes != bytes) {
            int innerStart = skipWhitespace(payload.bytes, 0);
            Object inner = parseStrictJson(payload.bytes, innerStart);
            if (inner instanceof JSONObject) return (JSONObject) inner;
        }
        throw new IOException("接口响应不是 JSON 对象");
    }
    static WirePayload read(byte[] bytes) throws IOException {
        if (bytes.length > CatalogCodec.MAX_BYTES) throw new IOException("接口响应过大");
        int start = skipWhitespace(bytes, 0);
        if (bytes.length == 0) return new WirePayload(null, bytes);
        if (start == bytes.length) throw new IOException("接口响应格式不匹配");
        if (bytes[start] != '[' && bytes[start] != '{') return new WirePayload(null, bytes);
        // A protobuf list can start with 0x0a followed by a length that looks like '[' or '{'.
        // Bytes outside JSON's permitted control characters identify these binary frames cheaply.
        if (start > 0) {
            for (int i = start + 1; i < bytes.length; i++) {
                if (bytes[i] >= 0 && bytes[i] < 0x20 && !whitespace(bytes[i])) return binaryListOrError(bytes, null);
            }
        }
        Object root;
        try {
            root = parseStrictJson(bytes, start);
        } catch (JSONException e) {
            if (start > 0) return binaryListOrError(bytes, e);
            throw new IOException("接口响应格式不匹配", e);
        }
        if (!(root instanceof JSONArray) || !(((JSONArray) root).opt(0) instanceof Number)) {
            return new WirePayload(root, bytes);
        }
        JSONArray array = (JSONArray) root;
        try {
            byte[] result = new byte[array.length()];
            for (int i = 0; i < result.length; i++) {
                Object value = array.get(i);
                if (!(value instanceof Number)) throw new IOException("二进制响应混入非数字字段");
                double n = ((Number) value).doubleValue();
                if (n < 0 || n > 255 || n != Math.floor(n)) throw new IOException("二进制响应字节无效");
                result[i] = (byte) (int) n;
            }
            // The envelope explicitly identified binary. Never sniff the decoded bytes again.
            return new WirePayload(null, result);
        } catch (JSONException e) { throw new IOException("接口响应 JSON 格式无效", e); }
    }
    private static int skipWhitespace(byte[] bytes, int pos) {
        while (pos < bytes.length && whitespace(bytes[pos])) pos++;
        return pos;
    }
    private static Object parseStrictJson(byte[] bytes, int start) throws JSONException {
        if (start >= bytes.length || (bytes[start] != '[' && bytes[start] != '{') || !validJson(bytes, start)) {
            throw new JSONException("JSON 语法无效");
        }
        JSONTokener reader = new JSONTokener(new String(bytes, StandardCharsets.UTF_8));
        Object root = reader.nextValue();
        if (reader.nextClean() != 0) throw new JSONException("JSON 响应有多余数据");
        return root;
    }
    private static boolean validJson(byte[] bytes, int pos) {
        byte[] stack = new byte[512];
        int depth = 0;
        final int VALUE = 0, KEY = 1, AFTER = 2;
        int state = VALUE;
        while (true) {
            pos = skipWhitespace(bytes, pos);
            if (state == VALUE) {
                if (pos >= bytes.length) return false;
                byte current = bytes[pos];
                if (current == '{') {
                    if (depth >= stack.length) return false;
                    stack[depth++] = '{';
                    pos = skipWhitespace(bytes, pos + 1);
                    if (pos < bytes.length && bytes[pos] == '}') { depth--; pos++; state = AFTER; }
                    else state = KEY;
                } else if (current == '[') {
                    if (depth >= stack.length) return false;
                    stack[depth++] = '[';
                    pos = skipWhitespace(bytes, pos + 1);
                    if (pos < bytes.length && bytes[pos] == ']') { depth--; pos++; state = AFTER; }
                    else state = VALUE;
                } else if (current == '"') {
                    pos = skipString(bytes, pos + 1);
                    if (pos < 0) return false;
                    state = AFTER;
                } else if (current == 't') {
                    if (!matchLiteral(bytes, pos, "true")) return false;
                    pos += 4; state = AFTER;
                } else if (current == 'f') {
                    if (!matchLiteral(bytes, pos, "false")) return false;
                    pos += 5; state = AFTER;
                } else if (current == 'n') {
                    if (!matchLiteral(bytes, pos, "null")) return false;
                    pos += 4; state = AFTER;
                } else if (current == '-' || (current >= '0' && current <= '9')) {
                    pos = skipNumber(bytes, pos);
                    if (pos < 0) return false;
                    state = AFTER;
                } else return false;
            } else if (state == KEY) {
                if (pos >= bytes.length || bytes[pos] != '"') return false;
                pos = skipString(bytes, pos + 1);
                if (pos < 0) return false;
                pos = skipWhitespace(bytes, pos);
                if (pos >= bytes.length || bytes[pos] != ':') return false;
                pos++; state = VALUE;
            } else {
                if (depth == 0) return pos == bytes.length;
                if (pos >= bytes.length) return false;
                byte container = stack[depth - 1];
                byte current = bytes[pos];
                if (container == '[') {
                    if (current == ',') { pos++; state = VALUE; }
                    else if (current == ']') { depth--; pos++; }
                    else return false;
                } else {
                    if (current == ',') { pos++; state = KEY; }
                    else if (current == '}') { depth--; pos++; }
                    else return false;
                }
            }
        }
    }
    private static boolean matchLiteral(byte[] bytes, int pos, String literal) {
        if (pos + literal.length() > bytes.length) return false;
        for (int i = 0; i < literal.length(); i++) {
            if (bytes[pos + i] != (byte) literal.charAt(i)) return false;
        }
        return true;
    }
    private static int skipString(byte[] bytes, int pos) {
        while (pos < bytes.length) {
            int b = bytes[pos] & 0xff;
            if (b == '"') return pos + 1;
            if (b == '\\') {
                pos++;
                if (pos >= bytes.length) return -1;
                int esc = bytes[pos] & 0xff;
                if (esc == '"' || esc == '\\' || esc == '/' || esc == 'b' || esc == 'f' || esc == 'n' || esc == 'r' || esc == 't') {
                    pos++;
                } else if (esc == 'u') {
                    if (pos + 4 >= bytes.length) return -1;
                    for (int i = 1; i <= 4; i++) {
                        int hex = bytes[pos + i] & 0xff;
                        boolean valid = (hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') || (hex >= 'A' && hex <= 'F');
                        if (!valid) return -1;
                    }
                    pos += 5;
                } else return -1;
            } else if (b < 0x20) {
                return -1;
            } else if (b < 0x80) {
                pos++;
            } else if (b >= 0xc2 && b <= 0xdf) {
                if (pos + 1 >= bytes.length || (bytes[pos + 1] & 0xc0) != 0x80) return -1;
                pos += 2;
            } else if (b >= 0xe0 && b <= 0xef) {
                if (pos + 2 >= bytes.length) return -1;
                int b1 = bytes[pos + 1] & 0xff, b2 = bytes[pos + 2] & 0xff;
                if ((b == 0xe0 && (b1 < 0xa0 || b1 > 0xbf)) || (b == 0xed && (b1 < 0x80 || b1 > 0x9f))
                        || (b1 & 0xc0) != 0x80 || (b2 & 0xc0) != 0x80) return -1;
                pos += 3;
            } else if (b >= 0xf0 && b <= 0xf4) {
                if (pos + 3 >= bytes.length) return -1;
                int b1 = bytes[pos + 1] & 0xff, b2 = bytes[pos + 2] & 0xff, b3 = bytes[pos + 3] & 0xff;
                if ((b == 0xf0 && (b1 < 0x90 || b1 > 0xbf)) || (b == 0xf4 && (b1 < 0x80 || b1 > 0x8f))
                        || (b1 & 0xc0) != 0x80 || (b2 & 0xc0) != 0x80 || (b3 & 0xc0) != 0x80) return -1;
                pos += 4;
            } else return -1;
        }
        return -1;
    }
    private static int skipNumber(byte[] bytes, int pos) {
        if (bytes[pos] == '-') {
            pos++;
            if (pos >= bytes.length) return -1;
        }
        if (bytes[pos] == '0') {
            pos++;
            if (pos < bytes.length && bytes[pos] >= '0' && bytes[pos] <= '9') return -1;
        } else if (bytes[pos] >= '1' && bytes[pos] <= '9') {
            pos++;
            while (pos < bytes.length && bytes[pos] >= '0' && bytes[pos] <= '9') pos++;
        } else return -1;
        if (pos < bytes.length && bytes[pos] == '.') {
            pos++;
            if (pos >= bytes.length || bytes[pos] < '0' || bytes[pos] > '9') return -1;
            while (pos < bytes.length && bytes[pos] >= '0' && bytes[pos] <= '9') pos++;
        }
        if (pos < bytes.length && (bytes[pos] == 'e' || bytes[pos] == 'E')) {
            pos++;
            if (pos < bytes.length && (bytes[pos] == '+' || bytes[pos] == '-')) pos++;
            if (pos >= bytes.length || bytes[pos] < '0' || bytes[pos] > '9') return -1;
            while (pos < bytes.length && bytes[pos] >= '0' && bytes[pos] <= '9') pos++;
        }
        return pos;
    }
    static boolean hasBinaryListFrame(byte[] bytes) {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        boolean hasItem = false;
        try {
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 10) {
                    int size = input.readRawVarint32();
                    input.skipRawBytes(size);
                    if (size > 0) hasItem = true;
                } else if (!input.skipField(tag)) {
                    return false;
                }
            }
            return hasItem;
        } catch (IOException invalid) {
            return false;
        }
    }
    private static WirePayload binaryListOrError(byte[] bytes, JSONException jsonError) throws IOException {
        // Only a complete frame with a list item can disambiguate a failed JSON parse.
        if (hasBinaryListFrame(bytes)) return new WirePayload(null, bytes);
        throw new IOException("接口响应格式不匹配", jsonError);
    }
}
