package tv.anitv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Display preference only: classify explicit adult labels, never fetch individual details. */
final class ContentVisibility {
    static final String PREFERENCE="safe-mode";
    private static final Pattern ADULT_LABEL=Pattern.compile(
            "(?i)(?<![a-z0-9])(?:r[ \t-]*18\\+?|18[ \t]*\\+|nsfw|hentai)(?![a-z0-9])|18禁|十八禁|成人动画|成人動畫|成人向|里番|裏番|エロアニメ");
    private ContentVisibility() {}
    static boolean labeled(String text){return text!=null && ADULT_LABEL.matcher(text).find();}
    static boolean marked(JSONObject item){
        if(item.optBoolean("nsfw") || item.optBoolean("r18"))return true;
        for(String field:new String[]{"tagline","type","genre","mark","tags","content_rating"})
            if(markedLabel(item.opt(field)))return true;
        return false;
    }
    private static boolean markedLabel(Object value){
        if(value instanceof org.json.JSONArray){
            org.json.JSONArray labels=(org.json.JSONArray)value;
            for(int i=0;i<labels.length();i++)if(markedLabel(labels.opt(i)))return true;
        }else if(value instanceof JSONObject)return markedLabel(((JSONObject)value).opt("name"));
        else if(value instanceof String){String label=((String)value).trim();return "成人".equals(label) || labeled(label);}
        return false;
    }
    static List<Show> filter(List<Show> shows,boolean showAdult){
        List<Show> visible=new ArrayList<>(shows.size());
        for(Show show:shows)if(showAdult || !show.r18)visible.add(show);
        return visible;
    }
}
