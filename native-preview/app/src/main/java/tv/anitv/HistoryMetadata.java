package tv.anitv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import okhttp3.HttpUrl;
import org.json.JSONObject;

/** Fills legacy history by its original metadata ID, with at most two requests in flight. */
final class HistoryMetadata implements AutoCloseable {
    interface Listener { void changed(); void complete(int failures); }
    private final PlaybackHistory history;
    private final CatalogRepository repository;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<PlaybackHistory.Entry> pending=new ArrayDeque<>();
    private final long revision;
    private final Listener listener;
    private int active,failures;
    private boolean closed,started;
    HistoryMetadata(Context context,List<PlaybackHistory.Entry> entries,Listener listener){
        history=new PlaybackHistory(context);revision=history.revision();this.listener=listener;
        repository=new CatalogRepository(((AniApp)context.getApplicationContext()).http(),new File(context.getCacheDir(),"details"));
        for(PlaybackHistory.Entry entry:entries)if(PlaybackHistory.incomplete(entry))pending.add(entry);
    }
    void start(){if(closed || started)return;started=true;pump();}
    boolean running(){return !closed && (!pending.isEmpty() || active>0);}
    private boolean valid(){return !closed && revision==history.revision();}
    private void pump(){
        if(!valid())return;
        while(active<2 && !pending.isEmpty()){PlaybackHistory.Entry entry=pending.removeFirst();active++;load(entry);}
        if(active==0 && pending.isEmpty())listener.complete(failures);
    }
    private void load(PlaybackHistory.Entry entry){
        HttpUrl url=entry.show.fromBangumi()?PublicCatalog.detail(entry.show.metadataId()):HttpUrl.get(entry.endpoint).newBuilder()
                .addPathSegment("bangumi").addPathSegment("detail").addPathSegment(Long.toString(entry.show.id)).build();
        final boolean[] fresh={false},resolved={false},finished={false};
        repository.get(url,true,new CatalogRepository.Listener(){
            public void loaded(byte[] bytes,boolean cached){
                if(!valid())return;
                repository.decode(()->{try{
                    PlaybackHistory.Entry metadata=decode(bytes,entry);
                    main.post(()->{
                        if(!valid() || cached && fresh[0])return;
                        if(!cached){fresh[0]=true;repository.save(url,bytes);}
                        resolved[0]=true;
                        if(history.enrich(metadata,revision))listener.changed();
                        if(!cached)finish(false);
                    });
                }catch(IOException error){if(!cached)main.post(()->finish(!resolved[0]));}});
            }
            public void failed(String error){finish(!resolved[0]);}
            private void finish(boolean failed){
                if(!valid() || finished[0])return;finished[0]=true;active--;if(failed)failures++;pump();
            }
        });
    }
    static PlaybackHistory.Entry decode(byte[] bytes,PlaybackHistory.Entry source)throws IOException{
        try{
            JSONObject row=new JSONObject(new String(WirePayload.unpack(bytes),StandardCharsets.UTF_8));
            if(row.optJSONObject("data")!=null)row=row.getJSONObject("data");
            if(row.optLong("id")!=source.show.metadataId())throw new IOException("历史详情编号不匹配");
            String title=row.optString(source.show.fromBangumi()?"name_cn":"title","").trim();
            if(title.isEmpty())title=row.optString("name","").trim();
            if(title.isEmpty() && !PlaybackHistory.missingTitle(source.show))title=source.show.title;
            Show show=new Show(source.show.id,title,"",source.show.subtitle);
            if(PlaybackHistory.missingTitle(show))throw new IOException("历史详情缺少标题");
            String image=row.optString("image","");JSONObject images=row.optJSONObject("images");
            if(image.isEmpty() && images!=null)image=images.optString("common",images.optString("large",""));
            if(!image.isEmpty())try{image=PlaySource.networkUrl(image);}catch(IOException invalid){image="";}
            return new PlaybackHistory.Entry(new Show(source.show.id,title,image,source.show.subtitle,source.show.r18 || ContentVisibility.marked(row)),source.endpoint,MetadataTitles.read(row,title));
        }catch(org.json.JSONException error){throw new IOException("历史详情格式不匹配",error);}
    }
    @Override public void close(){closed=true;pending.clear();repository.close();main.removeCallbacksAndMessages(null);}
}
