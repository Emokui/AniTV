package tv.anitv;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Measures HTTP time to the first body byte, not ICMP ping or streaming bandwidth. */
final class SourceLatency implements AutoCloseable {
    interface Listener { void measured(int index, Result result); }
    static final class Result {
        final long millis;
        final String label;
        Result(long millis, String label) { this.millis = millis; this.label = label; }
        static Result pending() { return new Result(-1, "等待测速"); }
        boolean success() { return millis >= 0; }
    }
    private static final java.util.concurrent.Executor CLEANUP = WorkPools.fixed(1,task -> {
        Thread thread = new Thread(task, "source-probe-cleanup"); thread.setDaemon(true); return thread;
    });
    private final java.util.concurrent.Executor cleanup;
    private final OkHttpClient client;
    private final ExecutorService workers = WorkPools.fixed(3);
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    SourceLatency() { this(CLEANUP); }
    SourceLatency(java.util.concurrent.Executor cleanup) {
        this.cleanup = cleanup;
        // Media probes never inherit API login interceptors or stored cookies.
        client = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }
    void start(List<PlaySource> sources, Listener listener) {
        for (int i = 0; i < sources.size(); i++) {
            final int index = i; final PlaySource source = sources.get(i);
            workers.execute(() -> {
                if (closed) return;
                listener.measured(index, new Result(-1, "测速中…"));
                Result result = measure(source);
                if (!closed) listener.measured(index, result);
            });
        }
    }
    Result measure(PlaySource source) {
        Call call;
        try {
        Request.Builder request = new Request.Builder().url(source.url);
        for (java.util.Map.Entry<String, String> header : source.headers.entrySet())
            if (!header.getKey().equalsIgnoreCase("_")) request.header(header.getKey(), header.getValue());
        request.header("Range", "bytes=0-0").header("Accept-Encoding", "identity").header("Cache-Control", "no-cache");
        call = client.newCall(request.build());
        }catch(IllegalArgumentException invalid){return new Result(-1,"线路参数无效");}
        calls.add(call);
        long start = System.nanoTime();
        try {
            if (closed) { call.cancel(); return new Result(-1, "已取消"); }
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) return new Result(-1, "HTTP " + response.code());
                if (response.body() == null || response.body().byteStream().read() == -1)
                    return new Result(-1, "空响应");
                long elapsed = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                return new Result(elapsed, elapsed + " ms");
            }
        } catch (SocketTimeoutException e) { return new Result(-1, "超时"); }
        catch (InterruptedIOException e) { return new Result(-1, closed ? "已取消" : "超时"); }
        catch (IOException e) { return new Result(-1, closed ? "已取消" : "连接失败"); }
        finally { calls.remove(call); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; workers.shutdownNow();
        // Android 8 TLS socket close can perform network I/O. Dialog detach is on
        // the UI thread, so cancellation AND pooled TLS cleanup belong off it.
        cleanup.execute(() -> {
            for (Call call : calls) call.cancel();
            client.connectionPool().evictAll();
        });
    }
}
