package tv.anitv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bangumi's documented public metadata API; playback stays with the selected service. */
final class PublicCatalog {
    private PublicCatalog() {}
    static Request search(long offset, String keyword, String type, String region, String year) {
        try {
            JSONObject filter = new JSONObject().put("type", new JSONArray().put(2));
            JSONArray tags = new JSONArray();
            if ("tv".equals(type)) tags.put("TV");
            else if ("movie".equals(type)) tags.put("剧场版");
            else if ("ova".equals(type)) tags.put("OVA");
            if (!region.isEmpty()) tags.put(region);
            if (tags.length() > 0) filter.put("tag", tags);
            if (!year.isEmpty()) {
                int value = Integer.parseInt(year);
                filter.put("air_date", new JSONArray().put(">=" + value + "-01-01").put("<" + (value + 1) + "-01-01"));
            }
            JSONObject body = new JSONObject().put("keyword", keyword).put("sort", keyword.isEmpty() ? "heat" : "match").put("filter", filter);
            HttpUrl url = HttpUrl.get("https://api.bgm.tv/v0/search/subjects").newBuilder()
                    .addQueryParameter("limit", "20").addQueryParameter("offset", Long.toString(offset)).build();
            return new Request.Builder().url(url).post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        } catch (JSONException | NumberFormatException e) { throw new IllegalArgumentException("目录筛选条件无效", e); }
    }
    static HttpUrl detail(long id) { return HttpUrl.get("https://api.bgm.tv/v0/subjects/" + id); }
    static CatalogCodec.Page decode(byte[] bytes) throws IOException {
        if (bytes.length > CatalogCodec.MAX_BYTES) throw new IOException("目录响应过大");
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray values = root.getJSONArray("data");
            List<Show> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.getJSONObject(i);
                long id = item.getLong("id");
                if (id <= 0 || id >= Show.BANGUMI_OFFSET) throw new IOException("目录编号无效");
                String title = title(item);
                if (title.isEmpty()) throw new IOException("目录条目缺少标题");
                JSONObject images = item.optJSONObject("images");
                String image = images == null ? "" : images.optString("common", images.optString("large"));
                String subtitle = item.optString("date", "") + " · " + item.optString("platform", "动画");
                result.add(new Show(Show.BANGUMI_OFFSET + id, title, image, subtitle,ContentVisibility.marked(item)));
            }
            long next = root.getLong("offset") + values.length();
            return new CatalogCodec.Page(result, values.length() == 0 || next >= root.getLong("total") ? -1 : next);
        } catch (JSONException e) { throw new IOException("公共目录响应格式不匹配", e); }
    }
    static String title(JSONObject item) {
        String title = item.optString("name_cn").trim();
        return title.isEmpty() ? item.optString("name").trim() : title;
    }
}
