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
    static final int REPLACE_TITLE = 1, REPLACE_IMAGE = 2, REPLACE_SUBTITLE = 4,
            REPLACE_ALL = REPLACE_TITLE | REPLACE_IMAGE | REPLACE_SUBTITLE,
            FAVORITE_CHANGED = 8;
    private static final Object LOCK = new Object();
    final SharedPreferences prefs;
    Library(Context context) { prefs = context.getSharedPreferences("native-library", Context.MODE_PRIVATE);
        if(prefs.contains("valid-only"))prefs.edit().remove("valid-only").apply();
    }
    String endpoint() {
        String value = prefs.getString("endpoint", DEFAULT_ENDPOINT);
        if ("https://ani.emmmm.eu.org".equals(value) || "https://ani.emmmm.eu.org/".equals(value)) return DEFAULT_ENDPOINT;
        return value;
    }
    static String endpointKey(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
    boolean safeMode(){return prefs.getBoolean(ContentVisibility.PREFERENCE,true);}
    boolean showR18(){return !safeMode();}
    List<Show> visible(List<Show> shows){return ContentVisibility.filter(shows,showR18());}
    private List<Show> readFavorites(java.util.BitSet polluted) {
        List<Show> result = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String rawSubtitle = o.optString("subtitle");
                String subtitle = PlaybackHistory.cleanSubtitle(rawSubtitle);
                if (polluted != null && !subtitle.equals(rawSubtitle)) polluted.set(result.size());
                result.add(new Show(o.getLong("id"), o.getString("title"), o.optString("image"), subtitle, o.optBoolean("r18")));
            }
        } catch (JSONException ignored) { }
        return result;
    }
    List<Show> favorites() {
        synchronized (LOCK) {
            java.util.BitSet polluted = new java.util.BitSet();
            List<Show> result = readFavorites(polluted);
            if (!polluted.isEmpty()) storeFavorites(result);
            return result;
        }
    }
    boolean contains(long id) { for (Show s : favorites()) if (s.id == id) return true; return false; }
    boolean toggle(Show show) {
        synchronized (LOCK) {
            List<Show> values = readFavorites(null);
            boolean removed = values.removeIf(s -> s.id == show.id);
            if (!removed) {
                String subtitle = PlaybackHistory.cleanSubtitle(show.subtitle);
                values.add(0, subtitle.equals(show.subtitle) ? show : new Show(show.id, show.title, show.image, subtitle, show.r18));
            }
            storeFavorites(values);
            return !removed;
        }
    }
    void removeFavorites(java.util.Set<Long> ids) {
        synchronized (LOCK) {
            java.util.BitSet polluted = new java.util.BitSet();
            List<Show> values = readFavorites(polluted);
            boolean removed = values.removeIf(show -> ids.contains(show.id));
            if (removed || !polluted.isEmpty()) storeFavorites(values);
        }
    }
    boolean syncFavorite(Show updated, boolean replaceCurrent) {
        return syncFavorite(updated, replaceCurrent, (String[]) null);
    }
    boolean syncFavorite(Show updated, boolean replaceCurrent, String... knownTitles) {
        return (syncFavoriteFields(updated, replaceCurrent ? REPLACE_ALL : 0, knownTitles) & FAVORITE_CHANGED) != 0;
    }
    int syncFavoriteFields(Show updated, int replaceMask, String... knownTitles) {
        if (updated.id <= 0) return 0;
        synchronized (LOCK) {
            java.util.BitSet polluted = new java.util.BitSet();
            List<Show> values = readFavorites(polluted);
            String updatedSubtitle = PlaybackHistory.cleanSubtitle(updated.subtitle);
            int resultMask = replaceMask & REPLACE_ALL;
            boolean changed = false;
            for (int i = 0; i < values.size(); i++) {
                Show existing = values.get(i);
                if (existing.id != updated.id) continue;
                boolean placeholder = PlaybackHistory.missingTitle(existing);
                boolean aliasMatch = existing.title.equals(updated.title);
                if (!aliasMatch && knownTitles != null) {
                    for (String known : knownTitles) {
                        if (known != null && existing.title.trim().equals(known.trim())) { aliasMatch = true; break; }
                    }
                }
                if ((replaceMask & REPLACE_ALL) == 0 && !placeholder && !aliasMatch) continue;
                if (polluted.get(i)) changed = true;
                boolean unhydrated = placeholder || (existing.image.trim().isEmpty() && existing.subtitle.isEmpty());
                boolean canReplaceTitle = (replaceMask & REPLACE_TITLE) != 0 || unhydrated;
                String title = canReplaceTitle && !PlaybackHistory.missingTitle(updated) ? updated.title : existing.title;
                if (unhydrated && !PlaybackHistory.missingTitle(updated)) resultMask |= REPLACE_TITLE;
                String nextImage = updated.image.trim();
                boolean emptyImage = existing.image.trim().isEmpty();
                String image = nextImage.isEmpty() ? existing.image
                        : ((replaceMask & REPLACE_IMAGE) != 0 || emptyImage ? nextImage : existing.image);
                if ((emptyImage || unhydrated) && !nextImage.isEmpty()) resultMask |= REPLACE_IMAGE;
                boolean emptySubtitle = existing.subtitle.isEmpty();
                String subtitle = updatedSubtitle.isEmpty() ? existing.subtitle
                        : ((replaceMask & REPLACE_SUBTITLE) != 0 || emptySubtitle ? updatedSubtitle : existing.subtitle);
                if ((emptySubtitle || unhydrated) && !updatedSubtitle.isEmpty()) resultMask |= REPLACE_SUBTITLE;
                boolean r18 = existing.r18 || updated.r18;
                Show merged = new Show(existing.id, title, image, subtitle, r18);
                if (!merged.equals(existing)) {
                    values.set(i, merged);
                    changed = true;
                }
            }
            if (changed || !polluted.isEmpty()) storeFavorites(values);
            if (changed) resultMask |= FAVORITE_CHANGED;
            return resultMask;
        }
    }
    private void storeFavorites(List<Show> values) {
        JSONArray array = new JSONArray();
        for (Show s : values) {
            try { array.put(new JSONObject().put("id", s.id).put("title", s.title).put("image", s.image).put("subtitle", PlaybackHistory.cleanSubtitle(s.subtitle)).put("r18",s.r18)); }
            catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
        prefs.edit().putString("favorites", array.toString()).apply();
    }
    static List<Show> demo() {
        List<Show> values = new ArrayList<>();
        String[] names = {"星河漫游", "夏日来信", "风起之时", "远方的光", "月色旅人", "时光回廊", "海边的约定", "纸上世界"};
        for (int i = 1; i <= 240; i++) values.add(new Show(1000000L + i,
                names[(i - 1) % names.length] + " " + String.format(java.util.Locale.ROOT, "%02d", i), "", "界面示例 · 本地卡片"));
        return values;
    }
}
