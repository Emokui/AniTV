package tv.anitv;

import android.app.Activity;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Assume;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Explicit diagnostic runner; not part of the automated regression test suite. */
public final class DeviceDiagnostics {
    @Test public void probePublicApi() {
        Assume.assumeTrue("explicit diagnostic only", "true".equals(InstrumentationRegistry.getArguments().getString("diagnose")));
        String[] routes = {"/bangumi/list?skip=0", "/bangumi/recommend?skip=0", "/bangumi/detail/33361", "/bangumi/episodes/33361", "/vod/33361/1", "/bangumi/search?keyword=海贼王"};
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
        StringBuilder report = new StringBuilder();
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "api-diagnostics"); directory.mkdirs();
        for (int i = 0; i < routes.length; i++) {
            try (Response r = client.newCall(new Request.Builder().url("https://eo.api.anich.sends.eu.org.cdn.cloudflare.net" + routes[i])
                    .header("User-Agent", "tvx.anich.preview Android 2.0.0-preview.3").build()).execute()) {
                byte[] body = r.body().bytes();
                Files.write(new File(directory, i + ".bin").toPath(), body);
                report.append(routes[i]).append(" -> ").append(r.code()).append(" ").append(r.header("Content-Type"))
                        .append(" bytes=").append(body.length).append("\n");
            } catch (Exception e) { report.append(routes[i]).append(" -> ").append(e.getClass().getSimpleName()).append("\n"); }
        }
        try { Files.write(new File(directory, "report.txt").toPath(), report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        android.util.Log.i("AniTVApiProbe", report.toString());
    }
}
