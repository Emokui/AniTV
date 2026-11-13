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
        Map<String, String> values = new LinkedHashMap<>(); values.put("User-Agent", USER_AGENT); values.putAll(headers);
        this.headers = Collections.unmodifiableMap(values);
    }
    public boolean hls() {
        String hint = type.toLowerCase(Locale.ROOT);
        return hint.contains("m3u8") || hint.contains("hls") || hint.contains("mpegurl") ||
                HttpUrl.get(url).encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }
    public static String networkUrl(String value) throws java.io.IOException {
        if (value == null || value.length() > 16384) throw new java.io.IOException("播放地址无效");
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null) throw new java.io.IOException("播放地址不是 HTTP/HTTPS 链接");
        return url.toString();
    }
}
