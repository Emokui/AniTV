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
import org.json.JSONObject;

/** AniCh's public episode/vod wire schemas, with explicit JSON counterparts. */
public final class PlaybackCodec {
    private PlaybackCodec() {}
    private static final java.util.regex.Pattern EPISODE_NUMBER=java.util.regex.Pattern.compile("^第?0*(\\d+)(?:集|话|話)$");
    private static JSONArray array(Object root) throws IOException {
        if (root instanceof JSONArray) return (JSONArray) root;
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            String error = WirePayload.error(object, "播放接口返回错误");
            if (error != null) throw new IOException(error);
            Object data = object.opt("data");
            if (data instanceof JSONObject) {
                object = (JSONObject) data;
                error = WirePayload.error(object, "播放接口返回错误");
                if (error != null) throw new IOException(error);
                data = object.opt("data");
            }
            if (data instanceof JSONArray) return (JSONArray) data;
        }
        throw new IOException("播放数据缺少列表");
    }
    public static List<Episode> episodes(byte[] bytes) throws IOException {
        WirePayload payload = WirePayload.read(bytes);
        TreeMap<Integer, Episode> result = new TreeMap<>();
        Map<Integer, Episode> specials = new LinkedHashMap<>();
        if (payload.json != null) {
            JSONArray values = array(payload.json);
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
                        result.putIfAbsent(number, new Episode(number, title, true));
                    } else {
                        // Keep existing episode IDs for saved history, and present specials in API order.
                        number = Episode.SPECIAL_NUMBER_START + (title.hashCode() & 0x1fffffff);
                        Episode previous = specials.get(number);
                        while (previous != null && !previous.title.equals(title)) previous = specials.get(++number);
                        if (previous == null) specials.put(number, new Episode(number, title, true));
                    }
                    continue;
                }
                JSONObject o = values.optJSONObject(i);
                if (o == null || !o.has("sort")) throw new IOException("剧集数据缺少集数");
                int number = o.optInt("sort", -1);
                if (number < 0) throw new IOException("剧集编号无效");
                result.put(number, new Episode(number, WirePayload.text(o.opt("title"), ""), false, WirePayload.text(o.opt("image"), ""),
                        o.optInt("duration"), WirePayload.flag(o.opt("status"))));
            }
        } else {
            CodedInputStream input = CodedInputStream.newInstance(payload.bytes);
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
        List<Episode> episodes = new ArrayList<>(result.size() + specials.size());
        episodes.addAll(result.values());
        episodes.addAll(specials.values());
        return episodes;
    }
    public static List<PlaySource> sources(byte[] bytes) throws IOException {
        WirePayload payload = WirePayload.read(bytes);
        if (payload.json == null) return binarySources(payload.bytes);
        List<PlaySource> result = new ArrayList<>();
        IOException firstInvalid = null;
        JSONArray values;
        try {
            values = array(payload.json);
        } catch (IOException error) {
            if ("播放数据缺少列表".equals(error.getMessage()) && bytes.length > 0 && bytes[0] == 10 && WirePayload.hasBinaryListFrame(bytes)) {
                return binarySources(bytes);
            }
            throw error;
        }
        for (int i = 0; i < values.length(); i++) {
            if (values.isNull(i)) {
                if (firstInvalid == null) firstInvalid = new IOException("播放地址无效");
                continue;
            }
            JSONObject o = values.optJSONObject(i);
            if (o == null) {
                if (bytes.length > 0 && bytes[0] == 10 && WirePayload.hasBinaryListFrame(bytes)) return binarySources(bytes);
                throw new IOException("线路数据格式不匹配");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                java.util.Iterator<String> keys = h.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!h.isNull(key)) headers.put(key, h.optString(key));
                }
            }
            String provider = WirePayload.text(o.opt("provider"), "").trim();
            String title = WirePayload.text(o.opt("caption"), "").trim();
            try {
                String url = decodeUrl(WirePayload.text(o.opt("url"), ""));
                if (provider.isEmpty()) title = caption(title, result.size());
                result.add(new PlaySource(url, title, WirePayload.text(o.opt("type"), ""), headers, provider));
            } catch (IOException invalid) {
                /* One broken URL does not discard valid sibling sources. */
                if (firstInvalid == null) firstInvalid = invalid;
            }
        }
        if (result.isEmpty() && firstInvalid != null) throw firstInvalid;
        return result;
    }
    private static List<PlaySource> binarySources(byte[] bytes) throws IOException {
        List<PlaySource> result = new ArrayList<>();
        IOException firstInvalid = null;
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag != 10) { if (!input.skipField(tag)) throw new IOException("线路列表字段无效"); continue; }
            CodedInputStream item = CodedInputStream.newInstance(input.readByteArray());
            String url = "", type = "", caption = "", provider = "";
            while (!item.isAtEnd()) {
                int field = item.readTag();
                if (field == 10) url = item.readStringRequireUtf8();
                else if (field == 26) type = item.readStringRequireUtf8();
                else if (field == 34) caption = item.readStringRequireUtf8();
                else if (field == 42) provider = item.readStringRequireUtf8();
                else if (!item.skipField(field)) throw new IOException("线路字段无效");
            }
            provider = provider.trim();
            caption = caption.trim();
            try {
                String decoded = decodeUrl(url);
                if (provider.isEmpty()) caption = caption(caption, result.size());
                result.add(new PlaySource(decoded, caption, type, Collections.emptyMap(), provider));
            } catch (IOException invalid) {
                /* Skip only this broken URL when sibling sources are valid. */
                if (firstInvalid == null) firstInvalid = invalid;
            }
        }
        if (result.isEmpty() && firstInvalid != null) throw firstInvalid;
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
