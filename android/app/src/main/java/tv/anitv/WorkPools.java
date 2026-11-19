package tv.anitv;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Cached pages keep their data; idle worker threads do not need to stay resident. */
final class WorkPools {
    private WorkPools() {}
    static ThreadPoolExecutor fixed(int count){
        return fixed(count,Executors.defaultThreadFactory());
    }
    static ThreadPoolExecutor fixed(int count,java.util.concurrent.ThreadFactory factory){
        ThreadPoolExecutor pool=(ThreadPoolExecutor)Executors.newFixedThreadPool(count,factory);
        pool.setKeepAliveTime(30,TimeUnit.SECONDS);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
