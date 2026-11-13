package tv.anitv;

import static org.junit.Assert.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WorkPoolsTest {
    @Test public void idleThreadsExitAndWorkRestartsWithoutClosingThePool()throws Exception{
        ThreadPoolExecutor pool=WorkPools.fixed(2);
        try{
            assertTrue(pool.allowsCoreThreadTimeOut());assertEquals(30,pool.getKeepAliveTime(TimeUnit.SECONDS));
            pool.setKeepAliveTime(30,TimeUnit.MILLISECONDS);assertEquals(42,pool.submit(()->42).get(1,TimeUnit.SECONDS).intValue());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(pool.getPoolSize()>0 && System.nanoTime()<end)Thread.sleep(10);
            assertEquals(0,pool.getPoolSize());assertFalse(pool.isShutdown());
            assertEquals(43,pool.submit(()->43).get(1,TimeUnit.SECONDS).intValue());
        }finally{pool.shutdownNow();}
    }
}
