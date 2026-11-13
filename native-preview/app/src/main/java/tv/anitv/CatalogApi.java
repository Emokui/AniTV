package tv.anitv;

import java.io.IOException;
import okhttp3.HttpUrl;

/** Original AniCh catalog routes. Pagination uses the last returned AniCh ID. */
final class CatalogApi {
    private CatalogApi() {}
    static HttpUrl url(HttpUrl base,int section,long cursor,String keyword,String type,String language,String year) {
        return url(base,section,cursor,keyword,type,language,year,false);
    }
    static HttpUrl url(HttpUrl base,int section,long cursor,String keyword,String type,String language,String year,boolean showR18){
        if(cursor<0 || cursor>=Show.BANGUMI_OFFSET)throw new IllegalArgumentException("目录分页编号无效");
        HttpUrl.Builder url=base.newBuilder().addPathSegment("bangumi")
                .addPathSegment(section==0?"recommend":section==3?"search":"list");
        if(section==3)url.addQueryParameter("keyword",keyword.trim());
        if(section==1) {
            if(!type.isEmpty())url.addQueryParameter("type",type);
            if(!language.isEmpty())url.addQueryParameter("lang",language);
            if(!year.isEmpty())url.addQueryParameter("year",year);
        }
        // AniCh sends server filter keys, not its local preference names.
        if(!showR18)url.addQueryParameter("nsfw","false");
        return url.addQueryParameter("skip",Long.toString(cursor)).build();
    }
    static HttpUrl related(HttpUrl base,long id){
        return base.newBuilder().addPathSegment("bangumi").addPathSegment("related").addPathSegment(Long.toString(id)).build();
    }
    static CatalogCodec.Page decode(byte[] bytes) throws IOException {
        CatalogCodec.Page page=CatalogCodec.decode(bytes);
        for(Show show:page.shows)if(show.fromBangumi())throw new IOException("目录返回了不兼容的番剧编号");
        long last=page.shows.isEmpty()?-1:page.shows.get(page.shows.size()-1).id;
        // The original client passes result.last.id as skip, including search.
        return new CatalogCodec.Page(page.shows,page.next<0?-1:last);
    }
}
