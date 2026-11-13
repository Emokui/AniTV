package tv.anitv;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class CatalogRepository implements AutoCloseable {
    public interface Listener {
        void loaded(byte[] bytes, boolean cached);
        default void loadedCache(byte[] bytes,long remainingMillis){loaded(bytes,true);}
        void failed(String message);
    }
    public static final class Job {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Call call;
        private volatile Future<?> task,cacheTask;
        public void cancel() {
            cancelled.set(true);
            NetworkCleanup.cancel(call);
            if (task != null) task.cancel(true);
            if(cacheTask!=null)cacheTask.cancel(true);
        }
    }
    static final long MAX_CACHE_BYTES=8L*1024*1024;
    private static final Object CACHE_LOCK=new Object();
    private static final java.util.Map<String,Long> CACHE_EPOCHS=new java.util.HashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong WRITE_SEQUENCE=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<String,Long> LATEST_WRITE=new java.util.HashMap<>();
    private final java.util.Set<Job> jobs=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final OkHttpClient http;
    private final File cache;
    private final ExecutorService worker = WorkPools.fixed(2);
    private final ExecutorService network = WorkPools.fixed(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    public CatalogRepository(OkHttpClient http, File cache) { this.http = http; this.cache = cache; }
    public Job get(HttpUrl url, boolean allowCache, Listener listener) {
        return request(new Request.Builder().url(url).build(), allowCache, listener);
    }
    public Job request(Request supplied, boolean allowCache, Listener listener) {
        return request(supplied,allowCache,true,listener);
    }
    public Job request(Request supplied, boolean allowCache, boolean refreshCached, Listener listener) {
        Job job = new Job();final ApiTrace trace=new ApiTrace(supplied.url());
        if(closed){job.cancel();return job;}jobs.add(job);
        if(allowCache)try{job.cacheTask=worker.submit(()->{
            if(!active(job)){jobs.remove(job);return;}
            File file=new File(cache,requestKey(supplied));
            if(file.isFile() && file.length()<=CatalogCodec.MAX_BYTES && System.currentTimeMillis()-file.lastModified()<12L*60*60*1000){
                try{byte[] bytes=Files.readAllBytes(file.toPath());long remaining=Math.max(0,12L*60*60*1000-(System.currentTimeMillis()-file.lastModified()));main.post(()->{if(active(job)){trace.mark("cache_delivered",android.os.SystemClock.elapsedRealtime()-trace.created);listener.loadedCache(bytes,remaining);}});
                    if(!refreshCached){jobs.remove(job);return;}}
                catch(IOException ignored){}
            }
            enqueueNetwork(job,supplied,listener,trace);
        });}catch(java.util.concurrent.RejectedExecutionException ignored){jobs.remove(job);job.cancel();}
        else enqueueNetwork(job,supplied,listener,trace);
        return job;
    }
    private void enqueueNetwork(Job job,Request supplied,Listener listener,ApiTrace trace){
        if(!active(job)){jobs.remove(job);return;}
        try { job.task = network.submit(() -> { try {
            if(!active(job))return;
            try {
                Request request = supplied.newBuilder().tag(ApiTrace.class,trace)
                        .header("User-Agent", supplied.header("User-Agent") != null ? supplied.header("User-Agent") : "Emokui/AniTV/" + BuildConfig.VERSION_NAME + " (https://github.com/Emokui/AniTV)")
                        .header("Accept", "application/x-protobuf, application/json").build();
                job.call = http.newCall(request);
                if (!active(job)) return;
                byte[] bytes;
                try (Response response = job.call.execute()) { bytes = body(response); }
                main.post(() -> { if (active(job)) {trace.mark("delivered",android.os.SystemClock.elapsedRealtime()-trace.created);listener.loaded(bytes, false);} });
            } catch (IOException e) {
                String message = e.getMessage() == null ? "网络连接失败" : e.getMessage();
                main.post(() -> { if (active(job)) listener.failed(message); });
            }
        } finally {jobs.remove(job);} });
        } catch(java.util.concurrent.RejectedExecutionException ignored){jobs.remove(job);job.cancel();}
    }
    private boolean active(Job job){return !closed && !job.cancelled.get();}
    /** Called only after decoding on the worker thread; writes never block UI rendering. */
    public void save(HttpUrl url, byte[] bytes) {
        save(new Request.Builder().url(url).build(), bytes);
    }
    public void save(Request request, byte[] bytes) {
        if (closed) return;
        long sequence=WRITE_SEQUENCE.incrementAndGet();final long epoch;
        synchronized(CACHE_LOCK){epoch=CACHE_EPOCHS.getOrDefault(cache.getAbsolutePath(),0L);}
        try { worker.submit(() -> {
            synchronized(CACHE_LOCK) { try {
                if(closed || epoch!=CACHE_EPOCHS.getOrDefault(cache.getAbsolutePath(),0L))return;
                String path=new File(cache,requestKey(request)).getAbsolutePath();
                if(sequence<LATEST_WRITE.getOrDefault(path,0L))return;
                if (!cache.isDirectory() && !cache.mkdirs()) return;
                File dest = new File(cache, requestKey(request));
                File tmp = new File(cache, dest.getName() + ".tmp");
                Files.write(tmp.toPath(), bytes);
                Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LATEST_WRITE.put(path,sequence);trimCache(cache);
            } catch (IOException ignored) { /* Cache failures must not fail browsing. */ } }

        }); } catch(java.util.concurrent.RejectedExecutionException ignored){/* Activity closed during decode. */}
    }
    static void clearCaches(File root) throws IOException {
        synchronized(CACHE_LOCK){
            EpisodeCache.clear();
            for(String name:new String[]{"catalog","details","episode-metadata"}){
                File directory=new File(root,name);CacheManager.deleteContents(directory);
                CACHE_EPOCHS.merge(directory.getAbsolutePath(),1L,Long::sum);
                String prefix=directory.getAbsolutePath()+File.separator;
                LATEST_WRITE.keySet().removeIf(path->path.startsWith(prefix));
            }
        }
    }
    static void clearEpisodeDisk(File root)throws IOException{
        synchronized(CACHE_LOCK){
            File directory=new File(root,"episode-metadata");CacheManager.deleteContents(directory);
            CACHE_EPOCHS.merge(directory.getAbsolutePath(),1L,Long::sum);
            String prefix=directory.getAbsolutePath()+File.separator;
            LATEST_WRITE.keySet().removeIf(path->path.startsWith(prefix));
        }
    }
    static void trimCache(File directory) throws IOException {
        File[] files=directory.listFiles(file->file.isFile()&&!file.getName().endsWith(".tmp"));
        if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        long bytes=0;for(File file:files)bytes+=file.length();
        int count=files.length;
        for(File file:files) {
            if(count<=24 && bytes<=MAX_CACHE_BYTES)break;
            long size=file.length();if(Files.deleteIfExists(file.toPath())){count--;bytes-=size;LATEST_WRITE.remove(file.getAbsolutePath());}
        }
    }
    public void decode(Runnable runnable) {
        if(closed)return;
        final long queued=android.os.SystemClock.elapsedRealtime();
        try{worker.submit(()->{long start=android.os.SystemClock.elapsedRealtime();try{runnable.run();}finally{ApiTrace.decode(start-queued,android.os.SystemClock.elapsedRealtime()-start);}});}catch(java.util.concurrent.RejectedExecutionException ignored){/* Already closed. */}
    }
    static byte[] body(Response response) throws IOException {
        if (!response.isSuccessful()) {
            if (response.header("Content-Type", "").contains("json")) {
                try {
                    org.json.JSONObject error = new org.json.JSONObject(response.peekBody(4096).string());
                    String message = error.optString("message", "").replaceAll("[\\p{Cntrl}]", " ").trim();
                    if (!message.isEmpty()) throw new IOException("HTTP " + response.code() + "：" + message.substring(0, Math.min(160, message.length())));
                } catch (org.json.JSONException ignored) { /* Fall through to HTTP status. */ }
            }
            if (response.code() == 403) throw new IOException("HTTP 403：接口拒绝访问或需要网页验证");
            if (response.code() >= 500) throw new IOException("HTTP " + response.code() + "：服务暂时不可用");
            throw new IOException("HTTP " + response.code() + "：请求失败");
        }
        ResponseBody body = response.body();
        if (body == null) throw new IOException("接口没有返回内容");
        if (body.contentLength() > CatalogCodec.MAX_BYTES) throw new IOException("接口响应过大");
        try (InputStream in = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > CatalogCodec.MAX_BYTES) throw new IOException("接口响应过大");
                out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            String contentType = response.header("Content-Type", "");
            if (contentType.contains("text/html")) throw new IOException("接口返回了网页，无法作为番剧数据读取");
            return bytes;
        }
    }
    static final class CacheScope {
        final String key;
        CacheScope(String key){this.key=key;}
    }
    static String requestKey(Request request) {
        okio.Buffer body = new okio.Buffer();
        try { if (request.body() != null) request.body().writeTo(body); }
        catch (IOException e) { throw new IllegalArgumentException("无法缓存此请求", e); }
        CacheScope scope=request.tag(CacheScope.class);
        return key(request.method() + "\n" + request.url() + "\n" + body.readUtf8()+(scope==null?"":"\nscope="+scope.key));
    }
    private static String key(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public void close() {
        closed=true;for(Job job:jobs)job.cancel();jobs.clear();worker.shutdownNow();network.shutdownNow();main.removeCallbacksAndMessages(null);
    }
}
