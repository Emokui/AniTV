package tv.anitv;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import okhttp3.HttpUrl;

/** Per-show preference: stable provider/quality identity, never a signed episode URL or row index. */
final class SourcePreference {
    private static final java.util.regex.Pattern QUALITY = java.util.regex.Pattern.compile(
            "(?:(?<![a-z0-9])(?:8k|4k|2k)|(?<!\\d)(?:4320|2160|1440|1080|720|576|480|360|240)p?)(?![a-z0-9])");
    private final SharedPreferences prefs;
    private final String key;
    SourcePreference(Context context,String endpoint,String show) {
        prefs=context.getSharedPreferences("native-source-preferences",Context.MODE_PRIVATE);
        key=PlaybackHistory.key(0,HttpUrl.get(endpoint).toString())+":"+show+":";
    }
    boolean saved(){return prefs.contains(key+"provider");}
    void remember(PlaySource source) {
        prefs.edit().putString(key+"provider",provider(source)).putString(key+"caption",detailCaption(source))
                .putString(key+"quality",quality(source.displayCaption)).putString(key+"type",source.type.toLowerCase(Locale.ROOT))
                .putString(key+"host",HttpUrl.get(source.url).host()).apply();
    }
    int match(List<PlaySource> values) {
        if(!saved())return -1;
        String provider=prefs.getString(key+"provider",""),caption=prefs.getString(key+"caption",""),
                quality=prefs.getString(key+"quality",""),type=prefs.getString(key+"type",""),host=prefs.getString(key+"host","");
        int best=-1,score=0;
        for(int i=0;i<values.size();i++) {
            PlaySource source=values.get(i);
            Alignment alignment = align(provider, caption, source);
            if (alignment == null) continue;
            boolean captionMatches = caption.equals(alignment.detailCaption);
            // Older builds stored provider prefixes inside caption preferences.
            boolean legacyMatches = !captionMatches && caption.equals(caption(source.displayCaption));
            String fullQuality = quality(source.displayCaption);
            boolean legacyProviderOnlyQuality = quality.isEmpty() && !alignment.providerRaw.isEmpty() && quality(alignment.detailRaw).isEmpty();
            if (!quality.equals(fullQuality) && !legacyProviderOnlyQuality) continue;
            int candidate=1+((captionMatches || legacyMatches)?8:0)+(host.equals(HttpUrl.get(source.url).host())?4:0)+(type.equals(source.type.toLowerCase(Locale.ROOT))?2:0);
            if(candidate>score){best=i;score=candidate;}
        }
        return best;
    }
    private static final class Alignment {
        final String detailCaption, providerRaw, detailRaw;
        Alignment(String detailCaption, String providerRaw, String detailRaw) {
            this.detailCaption = detailCaption;
            this.providerRaw = providerRaw;
            this.detailRaw = detailRaw;
        }
    }
    private static Alignment align(String savedProvider, String savedCaption, PlaySource source) {
        if (!source.provider.isEmpty()) {
            if (sameProvider(savedProvider, providerKey(source.provider))) {
                return new Alignment(caption(source.caption), source.provider, source.caption);
            }
            java.util.List<String> parts = nonEpisodeSegments(source.provider);
            for (int k = 1; k < parts.size(); k++) {
                String prefix = joinSegments(parts, 0, k);
                String spillRaw = joinSegments(parts, k, parts.size());
                String spillCaption = caption(spillRaw);
                if (sameProvider(savedProvider, providerKey(prefix)) && !spillCaption.isEmpty() && savedCaption.startsWith(spillCaption)) {
                    String combinedRaw = source.caption.isEmpty() ? spillRaw : spillRaw + " · " + source.caption;
                    return new Alignment(caption(combinedRaw), source.provider, source.caption);
                }
            }
            return null;
        }
        java.util.List<String> parts = embeddedSegments(source.caption);
        if (parts.isEmpty()) {
            return sameProvider(savedProvider, "") ? new Alignment("", "", "") : null;
        }
        for (int k = 1; k <= parts.size(); k++) {
            String candidateProvider = joinSegments(parts, 0, k);
            if (sameProvider(savedProvider, providerKey(candidateProvider))) {
                String detailRaw = joinSegments(parts, k, parts.size());
                return new Alignment(caption(detailRaw), candidateProvider, detailRaw);
            }
        }
        return null;
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }
    private static String stripEpisodes(String value) {
        return normalized(value)
                .replaceAll("第?\\s*\\d+(?:\\.\\d+)?\\s*[集话話期回]", "")
                .replaceAll("(?<![a-z0-9])(?:ep(?:isode)?\\.?\\s*\\d+|s\\d+e\\d+)(?![a-z0-9])", "");
    }
    private static boolean isEpisodeSegment(String segment) {
        return stripEpisodes(segment).replace("·", "").trim().isEmpty();
    }
    private static java.util.List<String> nonEpisodeSegments(String text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String raw : text.split("·", -1)) {
            if (!isEpisodeSegment(raw)) parts.add(raw.trim());
        }
        return parts;
    }
    private static java.util.List<String> embeddedSegments(String rawCaption) {
        String[] rawParts = rawCaption.split("·", -1);
        int start = 0;
        while (start < rawParts.length && isEpisodeSegment(rawParts[start])) start++;
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = start; i < rawParts.length; i++) {
            String trimmed = rawParts[i].trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }
    private static String joinSegments(java.util.List<String> parts, int from, int to) {
        if (from >= to) return "";
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) out.append(" · ");
            out.append(parts.get(i));
        }
        return out.toString();
    }
    private static boolean sameProvider(String saved, String current) {
        return saved.equals(current) || saved.replace(" ", "").equals(current.replace(" ", ""));
    }
    private static String provider(PlaySource source) {
        if (!source.provider.isEmpty()) return providerKey(source.provider);
        java.util.List<String> parts = embeddedSegments(source.caption);
        return parts.isEmpty() ? "" : providerKey(parts.get(0));
    }
    private static String providerKey(String value) {
        return stripEpisodes(value).replace("·", " ").replaceAll("\\s+", " ").trim();
    }
    private static String detailCaption(PlaySource source) {
        if (!source.provider.isEmpty()) return caption(source.caption);
        java.util.List<String> parts = embeddedSegments(source.caption);
        return parts.size() <= 1 ? "" : caption(joinSegments(parts, 1, parts.size()));
    }
    private static String caption(String title) {
        return stripEpisodes(title)
                .replaceAll("(^|·)\\s*\\d+(?=\\s*(?:[(\\[]|$))","$1")
                .replaceAll("\\s+","").replace("·","");
    }
    private static String quality(String title) {
        String text=stripEpisodes(title);
        java.util.regex.Matcher quality=QUALITY.matcher(text);
        java.util.Set<String> resolutions=new java.util.TreeSet<>();
        while(quality.find()) {
            String resolution=quality.group().replace("p","");
            if(resolution.equals("2k"))resolution="1440";
            else if(resolution.equals("4k"))resolution="2160";
            else if(resolution.equals("8k"))resolution="4320";
            resolutions.add(resolution);
        }
        StringBuilder value=new StringBuilder();for(String resolution:resolutions)value.append(resolution).append('/');
        if(text.contains("h265")||text.contains("h.265")||text.contains("hevc"))value.append("hevc");
        else if(text.contains("h264")||text.contains("h.264")||text.contains("avc"))value.append("avc");
        return value.toString();
    }
}
