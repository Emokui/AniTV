package tv.anitv;

import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Alternative names supplied by the same metadata entry, including its season. */
final class MetadataTitles {
    private MetadataTitles() {}
    static String[] read(JSONObject metadata, String primary) {
        LinkedHashSet<String> names = new LinkedHashSet<>(); add(names, primary);
        add(names, metadata.optString("name"));
        JSONArray titles = metadata.optJSONArray("titles");
        if (titles != null) for (int i = 0; i < titles.length(); i++) add(names, titles.optString(i));
        JSONArray info = metadata.optJSONArray("infobox");
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONObject item = info.optJSONObject(i);
            if (item == null || !"别名".equals(item.optString("key"))) continue;
            Object value = item.opt("value");
            if (value instanceof String) add(names, (String) value);
            else if (value instanceof JSONArray) for (int j = 0; j < ((JSONArray)value).length(); j++) {
                JSONObject alias = ((JSONArray)value).optJSONObject(j);
                if (alias != null) add(names, alias.optString("v"));
            }
        }
        return names.toArray(new String[0]);
    }
    static String[] queries(String[] titles) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String title : titles) add(names, title);
        for (String title : titles) {
            if (title == null) continue;
            String normal = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).trim();
            add(names, normal);
            add(names, title.replaceAll("[\\s\\u3000]+", ""));
            add(names, normal.replaceAll("[!！?？]+$", ""));
            add(names, title.replaceAll("[\\s\\u3000]+", "").replaceAll("[!！?？]+$", ""));
        }
        return names.toArray(new String[0]);
    }
    private static void add(LinkedHashSet<String> names, String value) {
        String title = value == null ? "" : value.trim();
        if (!title.isEmpty() && title.length() <= 200 && names.size() < 64) names.add(title);
    }
}
