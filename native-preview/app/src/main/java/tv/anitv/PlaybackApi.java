package tv.anitv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.HttpUrl;

final class PlaybackApi implements AutoCloseable {
    interface Result<T> { void success(T value); void error(String message); default void cached(T value){success(value);} default void progress(String message) {} default void warning(String message) {} }
    interface Parser<T> { T parse(byte[] bytes) throws IOException; }
    static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile CatalogRepository.Job job;
        private final java.util.Set<CatalogRepository.Job> jobs=java.util.concurrent.ConcurrentHashMap.newKeySet();
        void cancel() { cancelled.set(true); if (job != null) job.cancel();for(CatalogRepository.Job item:jobs)item.cancel();jobs.clear(); }
    }
    private final CatalogRepository repository;
    private final HttpUrl base;
    private final AccountSession session;
    private final android.content.SharedPreferences episodePrefs;
    private final java.util.Map<Long, String> preferredTitles = new java.util.HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    PlaybackApi(Context context) {
        AniApp app = (AniApp) context.getApplicationContext();
        episodePrefs=app.getSharedPreferences("episode-cache",Context.MODE_PRIVATE);
        repository = new CatalogRepository(app.http().newBuilder().readTimeout(50, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build(), new File(context.getCacheDir(), "episode-metadata"));
        base = HttpUrl.get(new Library(context).endpoint());
        session = new AccountSession(context);
    }
    static HttpUrl episodesUrl(HttpUrl base, long id, String title) {
        if (current(base)) return base.newBuilder().addPathSegment("vod").addPathSegment("episode")
                .addQueryParameter("title", title).build();
        return base.newBuilder().addPathSegment("bangumi").addPathSegment("episodes").addPathSegment(Long.toString(id)).build();
    }
    static HttpUrl sourcesUrl(HttpUrl base, long id, String title, Episode episode) {
        if (current(base)) return base.newBuilder().addPathSegment("vod").addPathSegment("get")
                .addQueryParameter("title", title).addQueryParameter("episode", episode.named ? episode.title : "第" + episode.number + "集").build();
        return base.newBuilder().addPathSegment("vod").addPathSegment(Long.toString(id)).addPathSegment(Integer.toString(episode.number)).build();
    }
    private static boolean current(HttpUrl base) { return base.host().equals(HttpUrl.get(Library.DEFAULT_ENDPOINT).host()); }
    static HttpUrl canonicalEpisodesUrl(HttpUrl base, long id) {
        return base.newBuilder().addPathSegment("bangumi").addPathSegment("episodes").addPathSegment(Long.toString(id)).build();
    }
    Task episodes(long id, String title, Result<List<Episode>> result) {
        HttpUrl url=current(base) && session.available(base) && id>0 && id<Show.BANGUMI_OFFSET ? canonicalEpisodesUrl(base,id) : episodesUrl(base,id,title);
        long cacheEpoch=EpisodeCache.epoch();
        long diskRevision=episodePrefs.getLong("revision",0);
        String key=url+"|"+session.revision()+"|"+session.available(base)+"|"+cacheEpoch;
        List<Episode> cached=EpisodeCache.get(key);
        if(cached!=null){Task task=new Task();main.post(()->{if(!closed && !task.cancelled.get())result.success(cached);});return task;}
        Task task=new Task();final boolean[] applied={false},fresh={false},readingCache={false};final String[] deferredError={null};
        okhttp3.Request request=new okhttp3.Request.Builder().url(url).tag(CatalogRepository.CacheScope.class,
                new CatalogRepository.CacheScope(session.revision()+":"+session.available(base)+":"+diskRevision)).build();
        task.job=repository.request(request,true,new CatalogRepository.Listener(){
            long cachedUntil;
            public void loadedCache(byte[] bytes,long remaining){cachedUntil=android.os.SystemClock.elapsedRealtime()+remaining;loaded(bytes,true);}
            public void loaded(byte[] bytes,boolean stored){
                if(closed || task.cancelled.get())return;
                if(stored)readingCache[0]=true;
                repository.decode(()->{try{
                    List<Episode> values=PlaybackCodec.episodes(bytes);
                    if(values.isEmpty() && stored)throw new IOException("本地剧集为空");
                    main.post(()->{
                        if(stored)readingCache[0]=false;
                        if(closed || task.cancelled.get() || stored && fresh[0])return;
                        if(values.isEmpty()){if(applied[0])result.warning("更新暂不可用，已保留本地剧集");else result.success(values);return;}
                        applied[0]=true;
                        EpisodeCache.put(key,values,cacheEpoch,stored?cachedUntil-android.os.SystemClock.elapsedRealtime():EpisodeCache.TTL_MS);
                        if(!stored){fresh[0]=true;if(EpisodeCache.epoch()==cacheEpoch)repository.save(request,bytes);result.success(values);}
                        else{result.cached(values);if(deferredError[0]!=null)result.warning("更新暂不可用，已保留本地剧集");}
                    });
                }catch(IOException error){main.post(()->{
                    if(stored){readingCache[0]=false;if(deferredError[0]!=null && !applied[0] && !fresh[0])failed(deferredError[0]);}
                    else failed(error.getMessage());
                });}});
            }
            public void failed(String error){
                if(closed || task.cancelled.get())return;
                if(!applied[0] && readingCache[0]){deferredError[0]=error;return;}
                if(applied[0])result.warning("更新暂不可用，已保留本地剧集");else result.error(error);
            }
        });
        if(task.cancelled.get())task.job.cancel();return task;
    }

    Task sources(long id, String[] titles, Episode episode, Result<List<PlaySource>> result) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (preferredTitles.containsKey(id)) candidates.add(preferredTitles.get(id));
        java.util.Collections.addAll(candidates, current(base) ? MetadataTitles.queries(titles) : new String[]{titles[0]});
        Task task = new Task();
        java.util.List<String> names=new java.util.ArrayList<>(candidates);
        if(current(base) && session.available(base) && id>0 && id<Show.BANGUMI_OFFSET) {
            result.progress("正在读取完整线路…");
            HttpUrl full=base.newBuilder().addPathSegment("vod").addPathSegment(Long.toString(id)).addPathSegment(Integer.toString(episode.number)).build();
            request(full,PlaybackCodec::sources,task,new Result<List<PlaySource>>() {
                public void success(List<PlaySource> values){result.success(values);}
                public void error(String message){result.error("完整取源失败："+message);}
            });
        } else new SourceCollector(id,names,episode,task,result).pump();
        return task;
    }
    /** Two requests at a time; merge in metadata order, independent of response timing. */
    private final class SourceCollector {
        final long id;final java.util.List<String> titles;final Episode episode;final Task task;final Result<List<PlaySource>> result;
        final java.util.Map<Integer,List<PlaySource>> batches=new java.util.HashMap<>();
        int next,active,completed;String error;
        SourceCollector(long id,java.util.List<String> titles,Episode episode,Task task,Result<List<PlaySource>> result){this.id=id;this.titles=titles;this.episode=episode;this.task=task;this.result=result;}
        void pump(){
            if(closed || task.cancelled.get())return;
            if(completed==titles.size()){
                SourceMerge merged=new SourceMerge();
                for(int i=0;i<titles.size();i++){List<PlaySource> batch=batches.get(i);if(batch!=null){merged.addAll(batch);if(!batch.isEmpty()&&!preferredTitles.containsKey(id))preferredTitles.put(id,titles.get(i));}}
                List<PlaySource> values=merged.snapshot();if(!values.isEmpty()||error==null)result.success(values);else result.error(error);return;
            }
            while(active<2 && next<titles.size()){
                final int index=next++;active++;
                result.progress("正在收集线路 "+completed+"/"+titles.size());
                request(sourcesUrl(base,id,titles.get(index),episode),PlaybackCodec::sources,task,new Result<List<PlaySource>>(){
                    public void success(List<PlaySource> values){batches.put(index,values);done();}
                    public void error(String message){error=message;done();}
                    private void done(){active--;completed++;pump();}
                });
            }
        }
    }
    private <T> Task request(HttpUrl url, Parser<T> parser, Result<T> result) {
        return request(url, parser, new Task(), result);
    }
    private <T> Task request(HttpUrl url, Parser<T> parser, Task task, Result<T> result) {
        if (closed || task.cancelled.get()) return task;
        final CatalogRepository.Job[] pending={null};
        task.job = repository.get(url, false, new CatalogRepository.Listener() {
            @Override public void loaded(byte[] bytes, boolean cached) {
                task.jobs.remove(pending[0]);
                if (closed || task.cancelled.get()) return;
                repository.decode(() -> {
                    try {
                        T value = parser.parse(bytes);
                        main.post(() -> { if (!closed && !task.cancelled.get()) result.success(value); });
                    } catch (IOException e) { main.post(() -> { if (!closed && !task.cancelled.get()) result.error(e.getMessage()); }); }
                });
            }
            @Override public void failed(String message) { task.jobs.remove(pending[0]);if (!closed && !task.cancelled.get()) result.error(message); }
        });
        pending[0]=task.job;task.jobs.add(task.job);
        if (task.cancelled.get()) task.job.cancel();
        return task;
    }
    @Override public void close() { closed = true; repository.close(); main.removeCallbacksAndMessages(null); }
}
