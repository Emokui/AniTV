package tv.anitv;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** AniCh's public episode/vod wire schemas, with explicit JSON counterparts. */
public final class PlaybackCodec {
    private PlaybackCodec() {}
    private static final java.util.regex.Pattern EPISODE_NUMBER=java.util.regex.Pattern.compile("^第?0*(\\d+)(?:集|话|話)$");
    private static boolean json(byte[] bytes) throws IOException {
        if (bytes.length > CatalogCodec.MAX_BYTES) throw new IOException("播放数据过大");
        for (byte b : bytes) if (!Character.isWhitespace((char) b)) return b == '{' || b == '[';
        return false;
    }
    private static JSONArray array(byte[] bytes) throws IOException {
        try {
            Object root = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
            if (root instanceof JSONArray) return (JSONArray) root;
            if (root instanceof JSONObject && ((JSONObject) root).opt("data") instanceof JSONArray)
                return ((JSONObject) root).getJSONArray("data");
        } catch (JSONException e) { throw new IOException("播放数据格式不匹配", e); }
        throw new IOException("播放数据缺少列表");
    }
    public static List<Episode> episodes(byte[] bytes) throws IOException {
        bytes = WirePayload.unpack(bytes);
        TreeMap<Integer, Episode> result = new TreeMap<>();
        if (json(bytes)) {
            JSONArray values = array(bytes);
            for (int i = 0; i < values.length(); i++) {
                Object value = values.opt(i);
                if (value instanceof String) {
                    String title = ((String) value).trim();
                    if (title.isEmpty()) continue;
                    java.util.regex.Matcher match = EPISODE_NUMBER.matcher(title);
                    int number;
                    if (match.matches()) {
                        try { number = Integer.parseInt(match.group(1)); }
                        catch (NumberFormatException error) { throw new IOException("剧集编号无效", error); }
                    } else number = 1000000000 + (title.hashCode() & 0x1fffffff);
                    Episode previous = result.get(number);
                    if (previous != null && previous.title.equals(title)) continue;
                    if (match.matches() && previous != null) continue;
                    while (result.containsKey(number)) number++;
                    result.put(number, new Episode(number, title, true));
                    continue;
                }
                JSONObject o = values.optJSONObject(i);
                if (o == null || !o.has("sort")) throw new IOException("剧集数据缺少集数");
                int number = o.optInt("sort", -1);
                if (number < 0) throw new IOException("剧集编号无效");
                result.put(number, new Episode(number, o.optString("title"), false, o.optString("image"),
                        o.optInt("duration"), o.optBoolean("status")));
            }
        } else {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag != 10) { if (!input.skipField(tag)) throw new IOException("剧集列表字段无效"); continue; }
                CodedInputStream item = CodedInputStream.newInstance(input.readByteArray());
                int number = -1, duration = 0; String title = "", image = ""; Boolean available = false;
                while (!item.isAtEnd()) {
                    int field = item.readTag();
                    if (field == 8) available = item.readBool();
                    else if (field == 16) number = item.readUInt32();
                    else if (field == 32) duration = item.readUInt32();
                    else if (field == 58) image = item.readStringRequireUtf8();
                    else if (field == 66) title = item.readStringRequireUtf8();
                    else if (!item.skipField(field)) throw new IOException("剧集字段无效");
                }
                if (number < 0) throw new IOException("剧集格式与当前接口不匹配");
                result.put(number, new Episode(number, title, false, image, duration, available));
            }
        }
        return new ArrayList<>(result.values());
    }
    public static List<PlaySource> sources(byte[] bytes) throws IOException {
        bytes = WirePayload.unpack(bytes);
        List<PlaySource> result = new ArrayList<>();
        if (json(bytes)) {
            JSONArray values = array(bytes);
            for (int i = 0; i < values.length(); i++) {
                JSONObject o = values.optJSONObject(i);
                if (o == null) continue;
                Map<String, String> headers = new LinkedHashMap<>();
                JSONObject h = o.optJSONObject("headers");
                if (h != null) {
                    java.util.Iterator<String> keys = h.keys();
                    while (keys.hasNext()) { String key = keys.next(); headers.put(key, h.optString(key)); }
                }
                try {result.add(new PlaySource(decodeUrl(o.optString("url")),
                        caption(o.optString("caption"), i), o.optString("type"), headers, o.optString("provider")));}
                catch(IOException invalid){/* One broken URL does not discard valid sibling sources. */}
            }
        } else {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag != 10) { if (!input.skipField(tag)) throw new IOException("线路列表字段无效"); continue; }
                byte[] payload=input.readByteArray();
                try {
                CodedInputStream item = CodedInputStream.newInstance(payload);
                String url = "", type = "", caption = "", provider = "";
                while (!item.isAtEnd()) {
                    int field = item.readTag();
                    if (field == 10) url = item.readStringRequireUtf8();
                    else if (field == 26) type = item.readStringRequireUtf8();
                    else if (field == 34) caption = item.readStringRequireUtf8();
                    else if (field == 42) provider = item.readStringRequireUtf8();
                    else if (!item.skipField(field)) throw new IOException("线路字段无效");
                }
                result.add(new PlaySource(decodeUrl(url), caption(provider.isEmpty() ? caption : provider + " · " + caption, result.size()), type, Collections.emptyMap(), provider));
                }catch(IOException invalid){/* Skip only this delimited source item. */}
            }
        }
        return result;
    }
    private static String caption(String value, int index) { return value.isEmpty() ? "线路 " + (index + 1) : value; }
    public static String decodeUrl(String value) throws IOException {
        try { return PlaySource.networkUrl(value); } catch (IOException ignored) { }
        if (value == null || value.length() < 5 || value.length() > 16384) throw new IOException("播放地址编码无效");
        // Same transformation as AniCh 1.0.0 urlDecode: remove character at index 3.
        String encoded = (value.substring(0, 3) + value.substring(4)).replace('-', '+').replace('_', '/');
        try { return PlaySource.networkUrl(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e) { throw new IOException("无法读取当前接口的播放地址", e); }
    }
}
