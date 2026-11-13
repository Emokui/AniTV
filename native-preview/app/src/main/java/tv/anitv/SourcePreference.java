package tv.anitv;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import okhttp3.HttpUrl;

/** Per-show preference: stable provider/quality identity, never a signed episode URL or row index. */
final class SourcePreference {
    private final SharedPreferences prefs;
    private final String key;
    SourcePreference(Context context,String endpoint,String show) {
        prefs=context.getSharedPreferences("native-source-preferences",Context.MODE_PRIVATE);
        key=PlaybackHistory.key(0,HttpUrl.get(endpoint).toString())+":"+show+":";
    }
    boolean saved(){return prefs.contains(key+"provider");}
    void remember(PlaySource source) {
        prefs.edit().putString(key+"provider",provider(source)).putString(key+"caption",caption(source))
                .putString(key+"quality",quality(source)).putString(key+"type",source.type.toLowerCase(Locale.ROOT))
                .putString(key+"host",HttpUrl.get(source.url).host()).apply();
    }
    int match(List<PlaySource> values) {
        if(!saved())return -1;
        String provider=prefs.getString(key+"provider",""),caption=prefs.getString(key+"caption",""),
                quality=prefs.getString(key+"quality",""),type=prefs.getString(key+"type",""),host=prefs.getString(key+"host","");
        int best=-1,score=0;
        for(int i=0;i<values.size();i++) {
            PlaySource source=values.get(i);
            if(!provider.equals(provider(source)) || !quality.equals(quality(source)))continue;
            int candidate=1+(caption.equals(caption(source))?8:0)+(host.equals(HttpUrl.get(source.url).host())?4:0)+(type.equals(source.type.toLowerCase(Locale.ROOT))?2:0);
            if(candidate>score){best=i;score=candidate;}
        }
        return best;
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }
    private static String provider(PlaySource source) {
        if(!source.provider.isEmpty())return normalized(source.provider);
        int split=source.caption.indexOf('·');
        return split>0?normalized(source.caption.substring(0,split)):caption(source);
    }
    private static String caption(PlaySource source) {
        return normalized(source.caption)
                .replaceAll("第?\\s*\\d+(?:\\.\\d+)?\\s*[集话話]","")
                .replaceAll("\\b(?:ep(?:isode)?\\.?\\s*\\d+|s\\d+e\\d+)\\b","")
                .replaceAll("(^|·)\\s*\\d+(?=\\s*(?:[(\\[]|$))","$1")
                .replaceAll("\\s+","").replace("·","");
    }
    private static String quality(PlaySource source) {
        String text=normalized(source.caption);
        java.util.regex.Matcher quality=java.util.regex.Pattern.compile("8k|4k|2160p?|1440p?|1080p?|720p?|480p?|360p?").matcher(text);
        java.util.Set<String> resolutions=new java.util.TreeSet<>();
        while(quality.find()) {
            String resolution=quality.group().replace("p","");
            if(resolution.equals("4k"))resolution="2160";
            else if(resolution.equals("8k"))resolution="4320";
            resolutions.add(resolution);
        }
        StringBuilder value=new StringBuilder();for(String resolution:resolutions)value.append(resolution).append('/');
        if(text.contains("h265")||text.contains("h.265")||text.contains("hevc"))value.append("hevc");
        else if(text.contains("h264")||text.contains("h.264")||text.contains("avc"))value.append("avc");
        return value.toString();
    }
}
