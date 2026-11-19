package tv.anitv;

import java.util.concurrent.ExecutorService;
import okhttp3.Call;

/** TLS socket cancellation can perform I/O on Android 8; never do it on the UI thread. */
final class NetworkCleanup {
    private static final ExecutorService WORKER=WorkPools.fixed(2,task->{
        Thread thread=new Thread(task,"network-cleanup");thread.setDaemon(true);return thread;
    });
    private NetworkCleanup() {}
    static void cancel(Call call) { if(call!=null)WORKER.execute(call::cancel); }
    static void release(Call call,Runnable cleanup) { WORKER.execute(()->{if(call!=null)call.cancel();cleanup.run();}); }
}
