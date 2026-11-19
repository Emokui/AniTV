package tv.anitv;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Wire fields follow Sle2p/AniCh lib/protobuf/bangumi.pbjson.dart.
 * Unknown fields are skipped; invalid/incompatible responses surface as errors. */
public final class CatalogCodec {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final class Page {
        public final List<Show> shows;
        public final long next;
        public Page(List<Show> shows, long next) {
            this.shows = Collections.unmodifiableList(new ArrayList<>(shows)); this.next = next;
        }
    }
    private CatalogCodec() {}
    public static Page decode(byte[] bytes) throws IOException {
        WirePayload payload = WirePayload.read(bytes);
        if (payload.json != null) return json(payload.json);
        bytes = payload.bytes;
        if (bytes.length == 0) return new Page(Collections.emptyList(), 0);
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        LinkedHashMap<Long, Show> shows = new LinkedHashMap<>();
        long next = 0;
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 10) {
                Show show = item(input.readByteArray());
                if (show.id <= 0 || show.title.isEmpty()) throw new IOException("番剧列表格式与当前接口不匹配");
                shows.put(show.id, show);
            } else if (tag == 24) next = input.readUInt32();
            else if (!input.skipField(tag)) throw new IOException("不支持的数据字段");
        }
        return new Page(new ArrayList<>(shows.values()), next);
    }
    private static Show item(byte[] bytes) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        long id = 0; int episode = 0, total = 0;
        String title = "", image = "", subtitle = "";
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            switch (tag) {
                case 8: id = input.readUInt32(); break;
                case 18: title = input.readStringRequireUtf8(); break;
                case 24: episode = input.readUInt32(); break;
                case 32: total = input.readUInt32(); break;
                case 58: image = input.readStringRequireUtf8(); break;
                case 66: subtitle = input.readStringRequireUtf8(); break;
                default: if (!input.skipField(tag)) throw new IOException("不支持的条目字段");
            }
        }
        title = title.trim();
        subtitle = subtitle.trim();
        if (subtitle.isEmpty()) subtitle = episodes(episode, total);
        return new Show(id, title, image, subtitle);
    }
    private static Page json(Object root) throws IOException {
        try {
            JSONArray items; long next = 0;
            if (root instanceof JSONArray) items = (JSONArray) root;
            else if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                String error = WirePayload.error(object, "目录接口返回错误");
                if (error != null) throw new IOException(error);
                next = object.optLong("next", 0);
                Object data = object.opt("data");
                if (data instanceof JSONObject) {
                    object = (JSONObject) data;
                    error = WirePayload.error(object, "目录接口返回错误");
                    if (error != null) throw new IOException(error);
                    next = object.optLong("next", next); data = object.opt("data");
                }
                if (!(data instanceof JSONArray)) throw new IOException("列表响应格式不匹配");
                items = (JSONArray) data;
            } else throw new IOException("列表响应格式不匹配");
            LinkedHashMap<Long, Show> shows = new LinkedHashMap<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                // 1.5.27 recommendation response groups cards into named sections.
                if (o.optJSONArray("list") != null) {
                    JSONArray section = o.getJSONArray("list");
                    for (int j = 0; j < section.length(); j++) addJsonItem(section.getJSONObject(j), shows);
                    continue;
                }
                addJsonItem(o, shows);
            }
            return new Page(new ArrayList<>(shows.values()), next);
        } catch (JSONException e) { throw new IOException("无法解析列表响应", e); }
    }
    private static void addJsonItem(JSONObject o, LinkedHashMap<Long, Show> shows) throws IOException {
                long id = o.optLong("id", 0); String title = WirePayload.text(o.opt("title"), "").trim();
                if (id <= 0 || title.isEmpty()) throw new IOException("列表条目缺少编号或标题");
                String subtitle = WirePayload.text(o.opt("tagline"), "").trim();
                if (subtitle.isEmpty()) subtitle = episodes(o.optInt("episode"), o.optInt("episodes_total", o.optInt("episodesTotal")));
                shows.put(id, new Show(id, title, WirePayload.text(o.opt("image"), ""), subtitle, ContentVisibility.marked(o)));
    }
    static String episodes(int episode, int total) {
        if (episode > 0 && total > 0) return "更新至 " + episode + " / " + total + " 集";
        if (total > 0) return "全 " + total + " 集";
        if (episode > 0) return "更新至 " + episode + " 集";
        return "番剧";
    }
}
