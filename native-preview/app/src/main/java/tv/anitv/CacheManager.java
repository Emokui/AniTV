package tv.anitv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.cache.DiskCache;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only explicit cache directories and requested local records are touched. */
final class CacheManager implements AutoCloseable {
    static final int IMAGES=0, METADATA=1, ALL=2, HISTORY=3, SOURCES=4;
    interface Result { void done(String message); }
    interface Sizes { void done(long images,long metadata); }
    private final Context context;
    private final ExecutorService worker=WorkPools.fixed(1);
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    CacheManager(Context context){this.context=context.getApplicationContext();}
    void sizes(Sizes result){if(closed)return;worker.execute(()->{
        long images=bytes(new File(context.getCacheDir(),DiskCache.Factory.DEFAULT_DISK_CACHE_DIR));long metadata=0;
        for(String name:new String[]{"catalog","details","episode-metadata"})metadata+=bytes(new File(context.getCacheDir(),name));
        final long total=metadata;main.post(()->{if(!closed)result.done(images,total);});
    });}
    void clear(int kind,Result result) {
        if(closed)return;
        if(kind==IMAGES || kind==ALL)Glide.get(context).clearMemory();
        worker.execute(()->{
            String message;
            try{
                if(kind==IMAGES || kind==ALL)Glide.get(context).clearDiskCache();
                if(kind==METADATA || kind==ALL)CatalogRepository.clearCaches(context.getCacheDir());
                if(kind==HISTORY)new PlaybackHistory(context).clear();
                if(kind==SOURCES)context.getSharedPreferences("native-source-preferences",Context.MODE_PRIVATE).edit().clear().apply();
                message="清理完成";
            }catch(Exception error){message="部分内容清理失败，请稍后重试";}
            final String text=message;main.post(()->{if(!closed)result.done(text);});
        });
    }
    void clearEpisodes(){
        if(closed)return;
        android.content.SharedPreferences prefs=context.getSharedPreferences("episode-cache",Context.MODE_PRIVATE);
        prefs.edit().putLong("revision",prefs.getLong("revision",0)+1).apply();
        EpisodeCache.clear();
        worker.execute(()->{try{CatalogRepository.clearEpisodeDisk(context.getCacheDir());}catch(IOException ignored){}});
    }
    static long bytes(File file){
        if(file.isFile())return file.length();long size=0;
        File[] children=file.listFiles();if(children!=null)for(File child:children)size+=bytes(child);
        return size;
    }
    static void deleteContents(File directory)throws IOException{
        File[] children=directory.listFiles();if(children==null)return;
        for(File file:children){if(file.isDirectory())deleteContents(file);if(!file.delete()&&file.exists())throw new IOException("清理失败");}
    }
    static String label(long bytes){return String.format(java.util.Locale.ROOT,"%.1f MB",bytes/1048576d);}
    @Override public void close(){closed=true;worker.shutdown();main.removeCallbacksAndMessages(null);}
}
