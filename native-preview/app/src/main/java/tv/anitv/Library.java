package tv.anitv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Library {
    // Literal shipped by AniCh 1.5.27. The public repository's api.json is older.
    static final String DEFAULT_ENDPOINT = "https://eo.api.anich.sends.eu.org.cdn.cloudflare.net";
    final SharedPreferences prefs;
    Library(Context context) { prefs = context.getSharedPreferences("native-library", Context.MODE_PRIVATE);
        if(prefs.contains("valid-only"))prefs.edit().remove("valid-only").apply();
    }
    String endpoint() {
        String value = prefs.getString("endpoint", DEFAULT_ENDPOINT);
        if ("https://ani.emmmm.eu.org".equals(value) || "https://ani.emmmm.eu.org/".equals(value)) return DEFAULT_ENDPOINT;
        return value;
    }
    boolean safeMode(){return prefs.getBoolean(ContentVisibility.PREFERENCE,true);}
    boolean showR18(){return !safeMode();}
    List<Show> visible(List<Show> shows){return ContentVisibility.filter(shows,showR18());}
    List<Show> favorites() {
        List<Show> result = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                result.add(new Show(o.getLong("id"), o.getString("title"), o.optString("image"), o.optString("subtitle"),o.optBoolean("r18")));
            }
        } catch (JSONException ignored) { }
        return result;
    }
    boolean contains(long id) { for (Show s : favorites()) if (s.id == id) return true; return false; }
    boolean toggle(Show show) {
        List<Show> values = favorites();
        boolean removed = values.removeIf(s -> s.id == show.id);
        if (!removed) values.add(0, show);
        JSONArray array = new JSONArray();
        for (Show s : values) {
            try { array.put(new JSONObject().put("id", s.id).put("title", s.title).put("image", s.image).put("subtitle", s.subtitle).put("r18",s.r18)); }
            catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
        prefs.edit().putString("favorites", array.toString()).apply(); return !removed;
    }
    static List<Show> demo() {
        List<Show> values = new ArrayList<>();
        String[] names = {"星河漫游", "夏日来信", "风起之时", "远方的光", "月色旅人", "时光回廊", "海边的约定", "纸上世界"};
        for (int i = 1; i <= 240; i++) values.add(new Show(1000000L + i,
                names[(i - 1) % names.length] + " " + String.format(java.util.Locale.ROOT, "%02d", i), "", "界面示例 · 本地卡片"));
        return values;
    }
}
