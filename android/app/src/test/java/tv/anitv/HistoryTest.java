package tv.anitv;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class HistoryTest {
    @Test public void oldProgressFollowsMostRecentEndpointAndThenStaysIndependent(){
        Context app=RuntimeEnvironment.getApplication();
        String a="https://a.example", b="https://b.example/";
        String rows="[{\"id\":42,\"title\":\"B\",\"endpoint\":\""+b+"\"},{\"id\":42,\"title\":\"A\",\"endpoint\":\""+a+"\"}]";
        app.getSharedPreferences("native-history-list",0).edit().putBoolean("migrated",true).putString("entries",rows).commit();
        app.getSharedPreferences("native-playback",0).edit().putInt("42:last",2).putLong("42:1",3000).putLong("42:2",42000).commit();
        PlaybackHistory h=new PlaybackHistory(app);
        String keyA=PlaybackHistory.progressKey(a,42), keyB=PlaybackHistory.progressKey("https://b.example",42);
        assertFalse(h.hasProgress(keyA)); assertEquals(42000,h.position(keyB,2)); assertEquals(3000,h.position(keyB,1));
        h.save(keyA,1,10000,90000);
        assertEquals(2,h.lastEpisode(keyB,0)); assertEquals(1,h.lastEpisode(keyA,0));
        h.remove(42,a); assertFalse(h.hasProgress(keyA)); assertEquals(42000,h.position(keyB,2));
    }
    @Test public void historicalEndpointsWithEitherSlashAreRecoveredAndDeduplicated() {
        Context app = RuntimeEnvironment.getApplication();
        PlaybackHistory history = new PlaybackHistory(app);
        String address = Library.DEFAULT_ENDPOINT;
        String rows = "[{\"id\":42,\"title\":\"旧记录\",\"endpoint\":\"" + address + "\"}," +
                "{\"id\":42,\"title\":\"同一番剧\",\"endpoint\":\"" + address + "/\"}," +
                "{\"id\":43,\"title\":\"带斜杠的旧记录\",\"endpoint\":\"" + address + "/\"}]";
        app.getSharedPreferences("native-history-list", 0).edit().putBoolean("migrated", true).putString("entries", rows).commit();
        history.save("42", Episode.SPECIAL_NUMBER_START, 30000, 40000);
        assertEquals(2, history.shows(address + "/").size());
        assertEquals("旧记录", history.find(42, address + "/").show.title);
        assertEquals("带斜杠的旧记录", history.find(43, address).show.title);
        assertEquals("特别篇 · 0:30", history.shows(address).get(0).subtitle);
        history.save("42", Episode.SPECIAL_NUMBER_START, 30000, 40000, "OVA 01");
        assertEquals("OVA 01 · 0:30", history.shows(address).get(0).subtitle);
        assertTrue(history.remove(42, address));
        assertNull(history.find(42, address + "/"));
        assertEquals(1, history.shows(address).size());
    }
    @Test public void evictingHistoryAlsoPrunesItsProgressButPreservesFavoritesAndSources(){
        Context app=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();String endpoint=new Library(app).endpoint();
        new Library(app).toggle(new Show(1,"收藏保留","",""));app.getSharedPreferences("native-source-preferences",0).edit().putString("provider","keep").commit();
        for(int i=1;i<=101;i++){history.remember(new Show(i,"番剧"+i,"",""),endpoint,new String[]{"番剧"+i});history.save(Integer.toString(i),2,5000,10000);}
        assertEquals(100,history.shows(endpoint).size());assertNull(history.find(1,endpoint));assertEquals(0,history.position("1",2));assertEquals(5000,history.position("101",2));
        assertTrue(new Library(app).contains(1));assertEquals("keep",app.getSharedPreferences("native-source-preferences",0).getString("provider",""));
        long shows=app.getSharedPreferences("native-playback",0).getAll().keySet().stream().filter(k->k.endsWith(":last")).count();assertEquals(100,shows);
    }
    @Test public void newestFirstAndRepeatedWatchMovesToTopWithSavedEpisodeAndCover(){
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);history.clear();
        history.remember(new Show(42,"第一季","https://example.org/42.jpg",""),"https://a.example/",new String[]{"第一季","season1"});history.save(PlaybackHistory.progressKey("https://a.example",42),7,123000,1400000);
        history.remember(new Show(43,"剧场版","",""),"https://a.example/",new String[]{"剧场版"});
        assertEquals(43,history.shows("https://a.example/").get(0).id);
        history.remember(new Show(42,"第一季","https://example.org/42.jpg",""),"https://a.example/",new String[]{"第一季","season1"});
        assertEquals(2,history.shows("https://a.example/").size());assertEquals(42,history.shows("https://a.example/").get(0).id);
        assertTrue(history.shows("https://a.example/").get(0).subtitle.contains("第 7 集 · 2:03"));
        assertEquals("https://example.org/42.jpg",history.find(42,"https://a.example/").show.image);
        assertEquals(2,history.find(42,"https://a.example/").titles.length);assertTrue(history.shows("https://b.example/").isEmpty());
    }
    @Test public void capsEntriesAtOneHundredAndClearLeavesFavoritesAndSession(){
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);history.clear();
        for(int i=1;i<=150;i++)history.remember(new Show(i,"番剧"+i,"",""),"https://a.example/",new String[]{"番剧"+i});
        assertEquals(100,history.shows("https://a.example/").size());assertEquals(150,history.shows("https://a.example/").get(0).id);assertNull(history.find(50,"https://a.example/"));
        new Library(context).toggle(new Show(999,"收藏","",""));
        context.getSharedPreferences("api-account",0).edit().putString("sentinel","keep").commit();history.save("150",2,10000,100000);
        history.clear();assertTrue(history.shows("https://a.example/").isEmpty());assertEquals(0,history.position("150",2));
        assertTrue(new Library(context).contains(999));assertEquals("keep",context.getSharedPreferences("api-account",0).getString("sentinel",""));
    }
    @Test public void legacyProgressMigratesWithoutLossAndKnownFavoriteSuppliesMetadata(){
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("native-history-list",0).edit().clear().commit();
        PlaybackHistory history=new PlaybackHistory(context);history.save("42",49,45000,100000);
        new Library(context).toggle(new Show(42,"药屋少女","https://example.org/p.jpg",""));
        assertEquals("药屋少女",history.shows(new Library(context).endpoint()).get(0).title);
        assertEquals(49,history.lastEpisode("42",1));assertEquals(45000,history.position("42",49));
    }
    @Test public void singleEndpointMigrationPreservesOtherEndpointsUnmigratedProgress(){
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("native-history-list",0).edit().clear().commit();
        String other="https://other.example";
        String otherKey=PlaybackHistory.progressKey(other,99);
        context.getSharedPreferences("native-playback",0).edit().clear()
                .putBoolean("scoped-progress",true)
                .putInt("42:last",3).putLong("42:3",30000)
                .putInt(otherKey+":last",5).putLong(otherKey+":5",55000)
                .commit();
        PlaybackHistory history=new PlaybackHistory(context);
        assertEquals(1,history.shows(Library.DEFAULT_ENDPOINT).size());
        assertEquals(30000,history.position("42",3));
        assertEquals(55000,history.position(otherKey,5));
        assertEquals(1,history.shows(other).size());
        assertEquals(99,history.shows(other).get(0).id);
    }
}
