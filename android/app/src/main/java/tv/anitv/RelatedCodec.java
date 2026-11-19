package tv.anitv;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** AniCh bangumi_related schema differs from the ordinary list (image is field 8). */
final class RelatedCodec {
    private RelatedCodec() {}
    static List<Show> decode(byte[] bytes,long currentId) throws IOException {
        WirePayload payload = WirePayload.read(bytes);
        LinkedHashMap<Long,Show> shows=new LinkedHashMap<>();
        if (payload.json != null) {
            try {
                Object root=payload.json;
                if(root instanceof JSONObject) {
                    JSONObject object=(JSONObject)root;
                    String error = WirePayload.error(object, "关联番剧接口返回错误");
                    if (error != null) throw new IOException(error);
                    root=object.opt("data");
                    if(root instanceof JSONObject) {
                        object=(JSONObject)root;
                        error = WirePayload.error(object, "关联番剧接口返回错误");
                        if (error != null) throw new IOException(error);
                        root=object.opt("data");
                    }
                }
                if(!(root instanceof JSONArray))throw new IOException("关联番剧列表格式不匹配");
                JSONArray items=(JSONArray)root;
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.getJSONObject(i);
                    add(shows,currentId,item.optLong("id"),WirePayload.text(item.opt("title"), ""),WirePayload.text(item.opt("image"), ""),
                            WirePayload.text(item.opt("type"), ""),WirePayload.text(item.opt("name"), ""),WirePayload.text(item.opt("tagline"), ""),
                            item.optInt("episode"),item.optInt("episodes_total",item.optInt("episodesTotal")),ContentVisibility.marked(item));
                }
            }catch(org.json.JSONException e){throw new IOException("关联番剧格式不匹配",e);}
        } else {
            CodedInputStream input=CodedInputStream.newInstance(payload.bytes);
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
                if (id <= 0 || title.trim().isEmpty()) throw new IOException("关联番剧格式与当前接口不匹配");
                add(shows,currentId,id,title,image,type,name,tagline,episode,total,false);
            }
        }
        return new ArrayList<>(shows.values());
    }
    private static void add(LinkedHashMap<Long,Show> result,long currentId,long id,String title,String image,String type,String name,String tagline,int episode,int total,boolean r18) {
        title = title.trim();
        if(id<=0 || id>=Show.BANGUMI_OFFSET || id==currentId || title.isEmpty() || result.containsKey(id))return;
        String relation=type.trim();
        name = name.trim();
        tagline = tagline.trim();
        if(!name.isEmpty())relation+=(relation.isEmpty()?"":" · ")+name;
        if(relation.isEmpty())relation=tagline.isEmpty()?CatalogCodec.episodes(episode,total):tagline;
        result.put(id,new Show(id,title,image,relation,r18 || ContentVisibility.labeled(tagline)));
    }
}
