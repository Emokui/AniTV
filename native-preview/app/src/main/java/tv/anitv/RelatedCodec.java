package tv.anitv;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** AniCh bangumi_related schema differs from the ordinary list (image is field 8). */
final class RelatedCodec {
    private RelatedCodec() {}
    static List<Show> decode(byte[] bytes,long currentId) throws IOException {
        bytes=WirePayload.unpack(bytes);LinkedHashMap<Long,Show> shows=new LinkedHashMap<>();
        int first=0;while(first<bytes.length && Character.isWhitespace((char)bytes[first]))first++;
        if(first<bytes.length && (bytes[first]=='[' || bytes[first]=='{')) {
            try {
                Object root=new JSONTokener(new String(bytes,StandardCharsets.UTF_8)).nextValue();
                if(root instanceof JSONObject) {
                    JSONObject object=(JSONObject)root;
                    if(object.optBoolean("error"))throw new IOException("关联番剧接口返回错误");
                    root=object.opt("data");
                    if(root instanceof JSONObject)root=((JSONObject)root).opt("data");
                }
                if(!(root instanceof JSONArray))throw new IOException("关联番剧列表格式不匹配");
                JSONArray items=(JSONArray)root;
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.getJSONObject(i);
                    add(shows,currentId,item.optLong("id"),item.optString("title"),item.optString("image"),
                            item.optString("type"),item.optString("name"),item.optString("tagline"),
                            item.optInt("episode"),item.optInt("episodes_total",item.optInt("episodesTotal")),ContentVisibility.marked(item));
                }
            }catch(org.json.JSONException e){throw new IOException("关联番剧格式不匹配",e);}
        } else {
            CodedInputStream input=CodedInputStream.newInstance(bytes);
            while(!input.isAtEnd()) {
                int tag=input.readTag();
                if(tag!=10){if(!input.skipField(tag))throw new IOException("关联列表字段无效");continue;}
                CodedInputStream item=CodedInputStream.newInstance(input.readByteArray());
                long id=0;int episode=0,total=0;String title="",image="",type="",name="",tagline="";
                while(!item.isAtEnd()) {
                    int field=item.readTag();
                    if(field==8)id=item.readUInt32();
                    else if(field==18)title=item.readStringRequireUtf8();
                    else if(field==26)name=item.readStringRequireUtf8();
                    else if(field==32)episode=item.readUInt32();
                    else if(field==40)total=item.readUInt32();
                    else if(field==66)image=item.readStringRequireUtf8();
                    else if(field==74)tagline=item.readStringRequireUtf8();
                    else if(field==82)type=item.readStringRequireUtf8();
                    else if(!item.skipField(field))throw new IOException("关联条目字段无效");
                }
                add(shows,currentId,id,title,image,type,name,tagline,episode,total,false);
            }
        }
        return new ArrayList<>(shows.values());
    }
    private static void add(LinkedHashMap<Long,Show> result,long currentId,long id,String title,String image,String type,String name,String tagline,int episode,int total,boolean r18) {
        if(id<=0 || id>=Show.BANGUMI_OFFSET || id==currentId || title.trim().isEmpty() || result.containsKey(id))return;
        String relation=type;
        if(!name.isEmpty())relation+=(relation.isEmpty()?"":" · ")+name;
        if(relation.isEmpty())relation=tagline.isEmpty()?CatalogCodec.episodes(episode,total):tagline;
        result.put(id,new Show(id,title,image,relation,r18 || ContentVisibility.labeled(tagline)));
    }
}
