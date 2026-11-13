package tv.anitv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import okhttp3.HttpUrl;

public final class PlaySource {
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 8.0; Android TV) AppleWebKit/537.36 Chrome/108.0 Safari/537.36";
    public final String url, caption, type, provider;
    public final Map<String, String> headers;
    public PlaySource(String url, String caption, String type, Map<String, String> headers) {
        this(url,caption,type,headers,"");
    }
    public PlaySource(String url, String caption, String type, Map<String,String> headers, String provider) {
        this.provider=provider==null?"":provider;
        this.url = url; this.caption = caption; this.type = type;
        Map<String, String> values = new LinkedHashMap<>();String userAgent=USER_AGENT;
        for(Map.Entry<String,String> header:headers.entrySet()){
            if(header.getKey().equalsIgnoreCase("User-Agent")){
                String value=header.getValue();if(value!=null && !value.trim().isEmpty())userAgent=value;
            }
            else values.put(header.getKey(),header.getValue());
        }
        values.put("User-Agent",userAgent);
        this.headers = Collections.unmodifiableMap(values);
    }
    public boolean hls() {
        String hint = type.toLowerCase(Locale.ROOT);
        return hint.contains("m3u8") || hint.contains("hls") || hint.contains("mpegurl") ||
                HttpUrl.get(url).encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }
    private static final java.util.regex.Pattern RESOLUTION=java.util.regex.Pattern.compile(
            "(?i)(?<![a-z0-9])([0-9]{3,5}\\s*[x×]\\s*[0-9]{3,5}|(?:4320|2160|1440|1080|720|576|480|360|240)\\s*[pP]|[248][kK])(?![a-z0-9])");
    String resolutionLabel(){
        java.util.regex.Matcher match=RESOLUTION.matcher(caption);
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
