package tv.anitv;

import static org.junit.Assert.*;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28, manifest = org.robolectric.annotation.Config.NONE)
public class NetworkTest {
    @Test public void challengeIsAnActionableError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>challenge</html>"));
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(server.url("/bangumi/list")).build()).execute()) {
                IOException error = assertThrows(IOException.class, () -> CatalogRepository.body(response));
                assertTrue(error.getMessage().contains("403"));
            }
        }
    }
    @Test public void successfulHtmlCannotBecomeEmptyCatalog() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>unavailable</html>"));
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                assertThrows(IOException.class, () -> CatalogRepository.body(response));
            }
        }
    }
    @Test public void oversizedChunkedResponseIsBounded() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            okio.Buffer buffer = new okio.Buffer().write(new byte[CatalogCodec.MAX_BYTES + 1]);
            server.enqueue(new MockResponse().setChunkedBody(buffer, 8192));
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                assertThrows(IOException.class, () -> CatalogRepository.body(response));
            }
        }
    }
    @Test public void unauthorizedJsonIsNotReportedAsServiceOutage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":true,\"message\":\"unauthorized\",\"body\":{}}"));
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                IOException error = assertThrows(IOException.class, () -> CatalogRepository.body(response));
                assertTrue(error.getMessage().contains("unauthorized"));
            }
        }
    }
    @Test public void serverValidationMessageSurvivesHttp500() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                    .setBody("{\"error\":true,\"message\":\"缺少标题\"}"));
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(server.url("/vod/get")).build()).execute()) {
                IOException error = assertThrows(IOException.class, () -> CatalogRepository.body(response));
                assertTrue(error.getMessage().contains("缺少标题"));
            }
        }
    }

}
