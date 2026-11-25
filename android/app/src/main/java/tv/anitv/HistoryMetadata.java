package tv.anitv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import okhttp3.HttpUrl;
import org.json.JSONObject;

/** Fills legacy history by its original metadata ID, with at most two requests in flight. */
final class HistoryMetadata implements AutoCloseable {
    interface Listener { void changed(); void complete(int failures); }
    private final Context context;
    private final PlaybackHistory history;
    private final CatalogRepository repository;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<PlaybackHistory.Entry> pending=new ArrayDeque<>();
    private final long revision;
    private final Listener listener;
    private int active,failures;
    private boolean closed,started;
    HistoryMetadata(Context context,List<PlaybackHistory.Entry> entries,Listener listener){
        this.context=context.getApplicationContext();
        history=new PlaybackHistory(context);revision=history.revision();this.listener=listener;
        repository=new CatalogRepository(((AniApp)this.context).http(),new File(context.getCacheDir(),"details"));
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
        final HttpUrl endpointUrl=HttpUrl.get(entry.endpoint);
        HttpUrl url=entry.show.fromBangumi()?PublicCatalog.detail(entry.show.metadataId()):endpointUrl.newBuilder()
                .addPathSegment("bangumi").addPathSegment("detail").addPathSegment(Long.toString(entry.show.id)).build();
        AccountSession account=new AccountSession(context);
        final String scope=entry.show.fromBangumi()?"":account.cacheScope(endpointUrl);
        final okhttp3.Request request=entry.show.fromBangumi()?new okhttp3.Request.Builder().url(url).build():account.scopedRequest(url,endpointUrl);
        final boolean[] fresh={false},resolved={false},finished={false},cacheDecoding={false},networkDone={false};
        final int[] hydratedFavoriteFields={0};
        final String[] cachedTitle={""};
        repository.request(request,true,new CatalogRepository.Listener(){
            public void loaded(byte[] bytes,boolean cached){
                if(!valid())return;
                if(cached)cacheDecoding[0]=true;
                repository.decode(()->{try{
                    PlaybackHistory.Entry metadata=decode(bytes,entry);
                    main.post(()->{
                        if(cached)cacheDecoding[0]=false;
                        if(!valid())return;
                        if(cached && fresh[0]){settle();return;}
                        if(!cached){fresh[0]=true;networkDone[0]=true;if(entry.show.fromBangumi() || scope.equals(new AccountSession(context).cacheScope(endpointUrl)))repository.save(request,bytes);}
                        resolved[0]=true;
                        boolean historyChanged=history.enrich(metadata,revision);
                        int syncResult=new Library(context).syncFavoriteFields(metadata.show,hydratedFavoriteFields[0],
                                MetadataTitles.merge(new String[]{entry.show.title,cachedTitle[0]},metadata.titles));
                        hydratedFavoriteFields[0] |= syncResult & Library.REPLACE_ALL;
                        if(cached)cachedTitle[0]=metadata.show.title;
                        boolean favoriteChanged=(syncResult & Library.FAVORITE_CHANGED)!=0;
                        if(historyChanged || favoriteChanged)listener.changed();
                        if(!cached)finish(false);else settle();
                    });
                }catch(IOException error){main.post(()->{
                    if(cached)cacheDecoding[0]=false;else networkDone[0]=true;
                    settle();
                });}});
            }
            public void failed(String error){networkDone[0]=true;settle();}
            private void settle(){if(networkDone[0] && !cacheDecoding[0])finish(!resolved[0]);}
            private void finish(boolean failed){
                if(!valid() || finished[0])return;finished[0]=true;active--;if(failed)failures++;pump();
            }
        });
    }
    static PlaybackHistory.Entry decode(byte[] bytes,PlaybackHistory.Entry source)throws IOException{
        try{
            JSONObject row=WirePayload.object(bytes);
            String error = WirePayload.error(row, "历史详情接口返回错误");
            if (error != null) throw new IOException(error);
            for (int depth = 0; depth < 2; depth++) {
                JSONObject data = row.optJSONObject("data");
                if (data == null || (depth > 0 && row.has("id"))) break;
                error = WirePayload.error(data, "历史详情接口返回错误");
                if (error != null) throw new IOException(error);
                row = data;
            }
            return decode(row, source);
        }catch(org.json.JSONException error){throw new IOException("历史详情格式不匹配",error);}
    }
    static PlaybackHistory.Entry decode(JSONObject row, PlaybackHistory.Entry source) throws IOException {
        String error = WirePayload.error(row, "历史详情接口返回错误");
        if (error != null) throw new IOException(error);
        if (row.optLong("id") != source.show.metadataId()) throw new IOException("历史详情编号不匹配");
        String title = WirePayload.text(row.opt(source.show.fromBangumi() ? "name_cn" : "title"), "").trim();
        if (title.isEmpty()) title = WirePayload.text(row.opt("name"), "").trim();
        if (title.isEmpty() && !PlaybackHistory.missingTitle(source.show)) title = source.show.title;
        String subtitle = PlaybackHistory.cleanSubtitle(source.show.subtitle);
        if (subtitle.isEmpty()) {
            if (source.show.fromBangumi()) {
                String date = WirePayload.text(row.opt("date"), "").trim();
                String platform = WirePayload.text(row.opt("platform"), "").trim();
                subtitle = !date.isEmpty() && !platform.isEmpty() ? date + " · " + platform : !date.isEmpty() ? date : platform;
            } else {
                subtitle = WirePayload.text(row.opt("tagline"), "").trim();
                if (subtitle.isEmpty()) subtitle = WirePayload.text(row.opt("subtitle"), "").trim();
                if (subtitle.isEmpty()) {
                    int episode = row.optInt("episode"), total = row.optInt("episodes_total", row.optInt("episodesTotal"));
                    if (episode > 0 || total > 0) subtitle = CatalogCodec.episodes(episode, total);
                }
            }
        }
        Show show = new Show(source.show.id, title, "", subtitle);
        if (PlaybackHistory.missingTitle(show)) throw new IOException("历史详情缺少标题");
        String image = WirePayload.text(row.opt("image"), "").trim();
        JSONObject images = row.optJSONObject("images");
        if (image.isEmpty() && images != null) {
            image = WirePayload.text(images.opt("common"), "").trim();
            if (image.isEmpty()) image = WirePayload.text(images.opt("large"), "").trim();
        }
        if (!image.isEmpty()) try { image = PlaySource.networkUrl(image); } catch (IOException invalid) { image = ""; }
        Show updated = new Show(show.id, title, image, show.subtitle, source.show.r18 || ContentVisibility.marked(row));
        String[] titles = MetadataTitles.read(row, title);
        if (!PlaybackHistory.missingTitle(source.show)) titles = MetadataTitles.merge(titles, source.titles);
        return new PlaybackHistory.Entry(updated, source.endpoint, titles, true);
    }
    @Override public void close(){closed=true;pending.clear();repository.close();main.removeCallbacksAndMessages(null);}
}
