package tv.anitv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Narrows the provider's related results to recognisable series names. No extra requests. */
final class RelatedSeries {
    private static final Pattern PREFIX=Pattern.compile("^(?:(?:剧场版|劇場版|电影版|電影版|总集篇|總集篇|特别篇|特別篇|ova|oad|tv)\\s*[:：·・\\-]?\\s*)+",Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION=Pattern.compile("第[0-9一二三四五六七八九十百零〇两]+[季期部]|(?:season|part)\\s*[0-9ivx]+\\b|剧场版|劇場版|电影版|電影版|特别篇|特別篇|总集篇|總集篇|\\b(?:ova|oad|specials?)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBTITLE=Pattern.compile("[:：]|[\\s\\u3000]+|[·・]");
    private static final Pattern CJK=Pattern.compile("[\\p{script=Han}\\p{script=Hiragana}\\p{script=Katakana}]");
    private RelatedSeries(){}
    static List<Show> filter(List<Show> candidates,String... titles){
        Set<String> names=new LinkedHashSet<>();
        for(String title:titles){String name=stem(title);if(!name.isEmpty())names.add(name);}
        List<Show> result=new ArrayList<>();
        for(Show candidate:candidates){
            String name=stem(candidate.title);
            if(name.isEmpty())continue;
            for(String current:names)if(matches(current,name)){result.add(candidate);break;}
        }
        return result;
    }
    private static boolean matches(String a,String b){
        if(a.equals(b))return true;
        String shorter=a.length()<b.length()?a:b,longer=a.length()<b.length()?b:a;
        // Only a complete series stem may prefix a subtitle. Never use arbitrary shared fragments.
        int length=shorter.codePointCount(0,shorter.length());
        return length>=(CJK.matcher(shorter).find()?4:8) && longer.startsWith(shorter);
    }
    static String stem(String title){
        if(title==null)return "";
        String value=Normalizer.normalize(title,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        value=value.replaceAll("^[「『\\[【]+|[」』\\]】]+$","");
        value=PREFIX.matcher(value).replaceFirst("");
        java.util.regex.Matcher version=VERSION.matcher(value);
        if(version.find())value=value.substring(0,version.start());
        // Chinese/Japanese titles usually delimit subtitles with spaces; English titles keep all words.
        java.util.regex.Matcher separator=SUBTITLE.matcher(value);
        while(separator.find()){
            String prefix=value.substring(0,separator.start()).trim();
            if(CJK.matcher(prefix).find() || ((value.charAt(separator.start())==':' || value.charAt(separator.start())=='：') && prefix.length()>=8)){
                if(!prefix.isEmpty()){value=prefix;break;}
            }
        }
        value=value.replaceAll("[\\s\\p{P}\\p{S}]+","").replaceAll("(?<=\\p{L})[0-9]+$","");
        if(value.codePointCount(0,value.length())<2 || value.matches("动画|動畫|电影|電影|特别|特別|剧场|劇場|tv|ova|oad|special|themovie"))return "";
        return value;
    }
}
