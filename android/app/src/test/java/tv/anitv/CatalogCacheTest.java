package tv.anitv;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CatalogCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void largeMetadataFilesEvictOldestBeforeCrossingByteBudget() throws Exception {
        File directory=temporary.newFolder();File first=new File(directory,"first");File second=new File(directory,"second");File newest=new File(directory,"newest");
        int order=0;for(File file:new File[]{first,second,newest}){Files.write(file.toPath(),new byte[3*1024*1024]);assertTrue(file.setLastModified(1000+(order++)));}
        File pending=new File(directory,"inflight.tmp");Files.write(pending.toPath(),new byte[1]);
        CatalogRepository.trimCache(directory);
        assertFalse(first.exists());assertTrue(second.exists());assertTrue(newest.exists());assertTrue(pending.exists());
        assertTrue(second.length()+newest.length()<=CatalogRepository.MAX_CACHE_BYTES);
    }
    @Test public void smallFilesStillRespectCountLimit() throws Exception {
        File directory=temporary.newFolder();
        for(int i=0;i<30;i++){File file=new File(directory,"entry"+i);Files.write(file.toPath(),new byte[]{1});assertTrue(file.setLastModified(1000+i));}
        CatalogRepository.trimCache(directory);assertEquals(24,directory.listFiles().length);
        assertFalse(new File(directory,"entry0").exists());assertTrue(new File(directory,"entry29").exists());
    }
}
