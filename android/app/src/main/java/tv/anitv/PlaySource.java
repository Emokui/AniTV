package tv.anitv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import okhttp3.HttpUrl;

public final class PlaySource {
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 8.0; Android TV) AppleWebKit/537.36 Chrome/108.0 Safari/537.36";
    public final String url, caption, type, provider;
    final String displayCaption;
    public final Map<String, String> headers;
    public PlaySource(String url, String caption, String type, Map<String, String> headers) {
        this(url,caption,type,headers,"");
    }
    public PlaySource(String url, String caption, String type, Map<String,String> headers, String provider) {
        this.provider=provider==null?"":provider.trim();
        this.url = url; this.type = type == null ? "" : type;
        String title = stripProviderPrefix(caption == null ? "" : caption.trim(), this.provider);
        this.caption = title;
        displayCaption = this.provider.isEmpty() ? title : title.isEmpty() ? this.provider : this.provider + " · " + title;
        Map<String, String> values = new LinkedHashMap<>();String userAgent=USER_AGENT;
        for(Map.Entry<String,String> header:(headers==null?Collections.<String,String>emptyMap():headers).entrySet()){
            if(header.getKey().equalsIgnoreCase("User-Agent")){
                String value=header.getValue();if(value!=null && !value.trim().isEmpty())userAgent=value;
            }
            else values.put(header.getKey(),header.getValue());
        }
        values.put("User-Agent",userAgent);
        this.headers = Collections.unmodifiableMap(values);
    }
    private static String stripProviderPrefix(String title, String provider) {
        if (provider.isEmpty() || title.isEmpty()) return title;
        if (sameToken(title, provider)) return "";
        if (title.regionMatches(true, 0, provider, 0, provider.length())) {
            String rest = title.substring(provider.length()).trim();
            if (rest.startsWith("·")) return rest.substring(1).trim();
        }
        String[] providerParts = provider.split("·", -1);
        int cursor = 0;
        for (int i = 0; i < providerParts.length; i++) {
            int split = title.indexOf('·', cursor);
            if (split < 0) {
                if (i == providerParts.length - 1 && sameToken(title.substring(cursor), providerParts[i])) return "";
                return title;
            }
            if (!sameToken(title.substring(cursor, split), providerParts[i])) return title;
            cursor = split + 1;
        }
        return title.substring(cursor).trim();
    }
    private static boolean sameToken(String first, String second) {
        String a = java.text.Normalizer.normalize(first, java.text.Normalizer.Form.NFKC).trim();
        String b = java.text.Normalizer.normalize(second, java.text.Normalizer.Form.NFKC).trim();
        return a.equalsIgnoreCase(b);
    }
    public boolean hls() {
        String hint = type.toLowerCase(Locale.ROOT);
        return hint.contains("m3u8") || hint.contains("hls") || hint.contains("mpegurl") ||
                HttpUrl.get(url).encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }
    private static final java.util.regex.Pattern RESOLUTION=java.util.regex.Pattern.compile(
            "(?i)(?<![a-z0-9])([0-9]{3,5}\\s*[x×]\\s*[0-9]{3,5}|(?:4320|2160|1440|1080|720|576|480|360|240)\\s*[pP]|[248][kK])(?![a-z0-9])");
    String resolutionLabel(){
        java.util.regex.Matcher match=RESOLUTION.matcher(java.text.Normalizer.normalize(displayCaption,java.text.Normalizer.Form.NFKC));
        java.util.LinkedHashSet<String> values=new java.util.LinkedHashSet<>();
        while(match.find())values.add(match.group(1).replaceAll("\\s+", "").replace('x','×').replace('X','×').toUpperCase(Locale.ROOT));
        return values.isEmpty()?"分辨率未知":String.join(" / ",values);
    }
    public static String networkUrl(String value) throws java.io.IOException {
        if (value == null || value.length() > 16384) throw new java.io.IOException("播放地址无效");
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null) throw new java.io.IOException("播放地址不是 HTTP/HTTPS 链接");
        return url.toString();
    }
}
