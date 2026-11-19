package tv.anitv;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Short-lived metadata only; never stores source URLs. Account revision is part of each key. */
final class EpisodeCache {
    static final long TTL_MS=12L*60*60*1000;
    private static final int MAX_SHOWS=8,MAX_EPISODES=2000;
    private static final LinkedHashMap<String,Entry> cache=new LinkedHashMap<>(8,.75f,true);
    private static final class Entry{
        final long expires;final List<Episode> episodes;
        Entry(List<Episode> episodes,long remaining){this.expires=SystemClock.elapsedRealtime()+remaining;this.episodes=new ArrayList<>(episodes);}
    }
    private static long epoch;
    static synchronized long epoch(){return epoch;}
    static synchronized List<Episode> get(String key){
        Entry value=cache.get(key);if(value==null)return null;
        if(SystemClock.elapsedRealtime()>=value.expires){cache.remove(key);return null;}
        return new ArrayList<>(value.episodes);
    }
    static synchronized void put(String key,List<Episode> episodes,long expectedEpoch){put(key,episodes,expectedEpoch,TTL_MS);}
    static synchronized void put(String key,List<Episode> episodes,long expectedEpoch,long remaining){
        if(epoch!=expectedEpoch)return;
        if(remaining<=0 || episodes.isEmpty() || episodes.size()>MAX_EPISODES)return;
        cache.put(key,new Entry(episodes,remaining));int total=0;for(Entry entry:cache.values())total+=entry.episodes.size();
        Iterator<Map.Entry<String,Entry>> it=cache.entrySet().iterator();
        while(it.hasNext() && (cache.size()>MAX_SHOWS || total>MAX_EPISODES)){total-=it.next().getValue().episodes.size();it.remove();}
    }
    static synchronized void clear(){epoch++;cache.clear();}
}
