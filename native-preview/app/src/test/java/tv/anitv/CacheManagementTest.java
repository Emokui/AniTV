package tv.anitv;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import okhttp3.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class CacheManagementTest {
    @Test public void metadataClearRemovesOnlyOwnedCacheFoldersAndRejectsOlderQueuedWrites()throws Exception{
        Context context=RuntimeEnvironment.getApplication();File root=context.getCacheDir();
        File catalog=new File(root,"catalog");catalog.mkdirs();Files.write(new File(catalog,"old").toPath(),new byte[100]);
        File image=new File(root,"image_manager_disk_cache");image.mkdirs();File poster=new File(image,"poster");Files.write(poster.toPath(),new byte[200]);
        File other=new File(root,"unrelated");Files.write(other.toPath(),new byte[]{1});
        try(CatalogRepository repo=new CatalogRepository(new OkHttpClient(),catalog)){
            Field worker=CatalogRepository.class.getDeclaredField("worker");worker.setAccessible(true);((ExecutorService)worker.get(repo)).shutdownNow();
            RefreshRaceTest.QueueExecutor queue=new RefreshRaceTest.QueueExecutor();worker.set(repo,queue);
            repo.save(HttpUrl.get("https://example.org/list"),new byte[]{2});CatalogRepository.clearCaches(root);queue.first();
            assertEquals(0,catalog.listFiles().length);assertTrue(poster.exists());assertTrue(other.exists());
        }
    }
    @Test public void resettingSourcesPreservesHistoryFavoritesAndSession()throws Exception{
        Context context=RuntimeEnvironment.getApplication();new Library(context).toggle(new Show(9,"收藏","",""));
        PlaybackHistory history=new PlaybackHistory(context);history.save("9",3,2000,10000);
        context.getSharedPreferences("api-account",0).edit().putString("sentinel","keep").commit();
        context.getSharedPreferences("native-source-preferences",0).edit().putString("provider","chosen").commit();
        AtomicBoolean done=new AtomicBoolean();
        try(CacheManager manager=new CacheManager(context)){
            manager.clear(CacheManager.SOURCES,message->done.set(true));long end=System.nanoTime()+5_000_000_000L;
            while(!done.get() && System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertTrue(done.get());assertTrue(context.getSharedPreferences("native-source-preferences",0).getAll().isEmpty());
            assertEquals(2000,history.position("9",3));assertTrue(new Library(context).contains(9));assertEquals("keep",context.getSharedPreferences("api-account",0).getString("sentinel",""));
        }
    }
}
