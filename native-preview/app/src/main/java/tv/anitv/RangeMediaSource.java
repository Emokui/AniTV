package tv.anitv;

import android.media.MediaDataSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Seekable NuPlayer input: 2 MiB request windows, four on-demand 512 KiB cache blocks. */
public final class RangeMediaSource extends MediaDataSource {
    static final int BLOCK = 512 * 1024;
    private static final int WINDOW = BLOCK * 4;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");
    private final OkHttpClient client;
    private final PlaySource source;
    private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<Long, byte[]>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> entry) { return size() > 4; }
    };
    private volatile long total = -1;
    private volatile boolean closed;
    private volatile Call active;
    private Response response;
    private InputStream stream;
    private long streamPosition, streamEnd = -1;
    private boolean rangeResponse;
    public RangeMediaSource(OkHttpClient client, PlaySource source) {
        // A continuous HTTP 200 body may last the whole episode; keep connect/read timeouts.
        this.client = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build(); this.source = source;
    }
    public boolean probeIsHls() throws IOException {
        byte[] first = block(0);
        return new String(first, 0, Math.min(first.length, 128), java.nio.charset.StandardCharsets.UTF_8).trim().startsWith("#EXTM3U");
    }
    private void open(long start) throws IOException {
        closeStream();
        Request.Builder request = new Request.Builder().url(source.url);
        for (Map.Entry<String, String> h : source.headers.entrySet()) request.header(h.getKey(), h.getValue());
        request.header("Accept-Encoding", "identity").header("Range", "bytes=" + start + "-" + (start + WINDOW - 1));
        Call call = client.newCall(request.build()); active = call;
        if (closed) { call.cancel(); throw new IOException("播放已停止"); }
        response = call.execute();
        if (closed) throw new IOException("播放已停止");
        if (response.code() == 416) {
            String value=response.header("Content-Range","");
            if(value.startsWith("bytes */"))try {total=Long.parseLong(value.substring(8));}catch(NumberFormatException e){throw new IOException("视频长度不正确",e);}
            if(total>=0 && start>=total){closeStream();return;}
        }
        if (response.code() != 200 && response.code() != 206) throw new IOException("视频 HTTP " + response.code());
        if (response.body() == null) throw new IOException("视频响应为空");
        rangeResponse = response.code() == 206;
        if (rangeResponse) {
            Matcher range = CONTENT_RANGE.matcher(response.header("Content-Range", ""));
            try {
                if (!range.matches() || Long.parseLong(range.group(1)) != start) throw new IOException("视频分段位置不正确");
                streamPosition=start;streamEnd=Long.parseLong(range.group(2))+1;
                if(streamEnd<=start)throw new IOException("视频分段长度不正确");
                if (!range.group(3).equals("*")) total = Long.parseLong(range.group(3));
            } catch(NumberFormatException error){throw new IOException("视频分段格式不正确",error);}
        } else {
            // Servers ignoring Range remain one sequential stream; seeking must skip bytes.
            streamPosition=0;total=response.body().contentLength();streamEnd=total;
        }
        stream=response.body().byteStream();
    }
    private synchronized void closeStream() {
        if(response!=null)response.close();
        response=null;stream=null;active=null;
    }
    private synchronized byte[] block(long start) throws IOException {
        if (closed) throw new IOException("播放已停止");
        if (total >= 0 && start >= total) return new byte[0];
        byte[] cached = blocks.get(start); if (cached != null) return cached;
        try {
            if(stream==null || start<streamPosition || (streamEnd>=0 && start>=streamEnd))open(start);
            if(total>=0 && start>=total)return new byte[0];
            while(streamPosition<start){
                if(closed)throw new IOException("播放已停止");
                long skipped=stream.skip(Math.min(32768,start-streamPosition));
                if(skipped==0){if(stream.read()==-1)throw new EOFException("视频分段不完整");skipped=1;}
                streamPosition+=skipped;
            }
            int wanted=total>=0?(int)Math.min(BLOCK,total-start):BLOCK;
            byte[] bytes=new byte[wanted];int count=0;
            while(count<wanted){
                if(closed)throw new IOException("播放已停止");
                if(streamEnd>=0 && streamPosition>=streamEnd){
                    if(total>=0 && streamPosition>=total)break;
                    open(streamPosition);if(stream==null)break;
                }
                int remaining=streamEnd>=0?(int)Math.min(wanted-count,streamEnd-streamPosition):wanted-count;
                int n=stream.read(bytes,count,remaining);
                if(n==-1){
                    if(rangeResponse || (total>=0 && streamPosition<total))throw new EOFException("视频分段不完整");
                    total=streamPosition;break;
                }
                count+=n;streamPosition+=n;
            }
            if(closed)throw new IOException("播放已停止");
            if(total>=0 && count!=Math.min(BLOCK,total-start))throw new EOFException("视频分段不完整");
            if(count!=bytes.length)bytes=Arrays.copyOf(bytes,count);
            if(streamEnd>=0 && streamPosition>=streamEnd)closeStream();
            blocks.put(start,bytes);return bytes;
        } catch(IOException error){closeStream();throw error;}
    }
    @Override public synchronized int readAt(long position, byte[] target, int offset, int size) throws IOException {
        if (closed) throw new IOException("播放已停止");
        if (offset < 0 || size < 0 || offset > target.length - size) throw new IndexOutOfBoundsException();
        if (size == 0) return 0;
        if (position < 0 || (total >= 0 && position >= total)) return -1;
        byte[] bytes = block(position / BLOCK * BLOCK); int inside = (int) (position % BLOCK);
        if (inside >= bytes.length) return -1;
        int count = Math.min(size, bytes.length - inside); System.arraycopy(bytes, inside, target, offset, count); return count;
    }
    @Override public long getSize() { return total; }
    @Override public void close() {
        closed=true;NetworkCleanup.release(active,()->{synchronized(this){closeStream();blocks.clear();}});
    }
}
