package tv.anitv;

import android.content.res.AssetManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** Local HTTP fixture, no dependency on AniCh's live service or third-party media. */
final class PlaybackFixture implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();
    private final AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    private final byte[] video;
    PlaybackFixture() throws IOException {
        video = asset("sample.mp4");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (path.startsWith("/bangumi/episodes/")) return json("{\"data\":[{\"sort\":1,\"title\":\"测试第一集\"},{\"sort\":2,\"title\":\"测试第二集\"}]}");
                if (path.startsWith("/bangumi/detail/")) return json("{\"id\":42,\"title\":\"原生播放测试\",\"overview\":\"本机生成的测试片段\"}");
                if (path.startsWith("/vod/42/")) return json("{\"data\":[{\"url\":\"" + url("/sample.mp4") + "\",\"caption\":\"MP4 线路\"},{\"url\":\"" + url("/sample.m3u8") + "\",\"caption\":\"HLS 线路\"}]}");
                if (path.equals("/sample.mp4")) {
                    String range = request.getHeader("Range");
                    if (range == null) return new MockResponse().setHeader("Content-Type", "video/mp4").setBody(new Buffer().write(video));
                    Matcher m = Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
                    if (!m.matches()) return new MockResponse().setResponseCode(400);
                    int start = Integer.parseInt(m.group(1));
                    if (start >= video.length) return new MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */" + video.length);
                    int end = m.group(2).isEmpty() ? video.length - 1 : Math.min(video.length - 1, Integer.parseInt(m.group(2)));
                    return new MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                            .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + video.length)
                            .setBody(new Buffer().write(Arrays.copyOfRange(video, start, end + 1)));
                }
                if (path.equals("/sample.m3u8") || path.matches("/segment\\d+\\.ts")) {
                    try { return new MockResponse().setHeader("Content-Type", path.endsWith("m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t")
                            .setBody(new Buffer().write(asset(path.substring(1)))); }
                    catch (IOException e) { return new MockResponse().setResponseCode(404); }
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"), 0);
    }
    String url(String path) { return server.url(path).toString(); }
    private MockResponse json(String value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(value); }
    private byte[] asset(String name) throws IOException {
        try (InputStream in = assets.open("playback/" + name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return out.toByteArray();
        }
    }
    @Override public void close() throws IOException { server.shutdown(); }
}
