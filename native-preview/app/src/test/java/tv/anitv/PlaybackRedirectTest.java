package tv.anitv;

import static org.junit.Assert.*;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class PlaybackRedirectTest {
    @Test public void mediaFollowsBothProtocolDirectionsAndPreservesRangeAndProviderHeaders()throws Exception{
        HeldCertificate certificate=new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        SSLSocketFactory previous=HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory());
        try(MockWebServer plain=new MockWebServer();MockWebServer secure=new MockWebServer()){
            secure.useHttps(serverTls.sslSocketFactory(),false);plain.start();secure.start();
            for(boolean upgrade:new boolean[]{true,false}){
                MockWebServer origin=upgrade?plain:secure,target=upgrade?secure:plain;
                origin.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",target.url("/video.mp4")));
                target.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 2-5/8").setBody("cdef"));
                PlaySource source=new PlaySource(origin.url("/redirect.mp4").toString(),"","mp4",Collections.singletonMap("Referer","https://provider.example/"));
                HttpDataSource media=NativePlayback.httpDataSource(source).createDataSource();
                try {
                    assertEquals(4,media.open(new DataSpec.Builder().setUri(source.url).setPosition(2).setLength(4).build()));
                    byte[] body=new byte[4];int total=0;
                    while(total<body.length){int n=media.read(body,total,body.length-total);assertTrue(n>0);total+=n;}
                    assertArrayEquals(new byte[]{'c','d','e','f'},body);
                    assertEquals(target.url("/video.mp4").toString(),media.getUri().toString());
                } finally {media.close();}
                RecordedRequest before=origin.takeRequest(2,TimeUnit.SECONDS),after=target.takeRequest(2,TimeUnit.SECONDS);
                assertNotNull(before);assertNotNull(after);assertEquals("bytes=2-5",after.getHeader("Range"));
                assertEquals("https://provider.example/",after.getHeader("Referer"));assertEquals(PlaySource.USER_AGENT,after.getHeader("User-Agent"));
            }
        } finally {HttpsURLConnection.setDefaultSSLSocketFactory(previous);}
    }
    @Test public void customUserAgentMatchesLatencyProbeAndMediaForAllHeaderCasings()throws Exception{
        try(MockWebServer server=new MockWebServer();org.robolectric.android.controller.ActivityController<android.app.Activity> c=org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup()){
            for(String key:new String[]{"User-Agent","user-agent","USER-AGENT"}){
                String agent="ProviderClient/1.0";PlaySource source=new PlaySource(server.url("/signed").toString(),"","",Collections.singletonMap(key,agent));
                assertEquals(1,source.headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("User-Agent")).count());
                server.enqueue(new MockResponse().setBody("probe"));
                try(SourceLatency latency=new SourceLatency()){assertTrue(latency.measure(source).success());}
                assertEquals(agent,server.takeRequest(2,TimeUnit.SECONDS).getHeader("User-Agent"));
                server.enqueue(new MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST\n"));
                try(NativePlayback engine=new NativePlayback(c.get(),new android.view.SurfaceView(c.get()),new okhttp3.OkHttpClient(),new NativePlayback.Listener(){public void ready(){}public void buffering(boolean v){}public void firstFrame(int w,int h){}public void ended(){}public void error(String m){}public void changed(){}})){
                    engine.open(source,0,false);assertEquals(agent,server.takeRequest(2,TimeUnit.SECONDS).getHeader("User-Agent"));
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                server.enqueue(new MockResponse().setBody("media"));HttpDataSource media=NativePlayback.httpDataSource(source).createDataSource();
                try{media.open(new DataSpec.Builder().setUri(source.url).build());assertTrue(media.read(new byte[5],0,5)>0);}finally{media.close();}
                assertEquals(agent,server.takeRequest(2,TimeUnit.SECONDS).getHeader("User-Agent"));
            }
        }
    }

    @Test public void missingOrBlankUserAgentUsesDefault(){
        for(String value:new String[]{null,"","   "}){
            PlaySource source=new PlaySource("https://example.org/video.mp4","","mp4",Collections.singletonMap("user-agent",value));
            assertEquals(PlaySource.USER_AGENT,source.headers.get("User-Agent"));assertEquals(1,source.headers.size());
        }
    }

}
