package tv.anitv;

import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.Protocol;
import okhttp3.Response;

/** Timing-only diagnostics: no URLs, query strings, payloads, accounts, or headers. */
final class ApiTrace {
    private static final AtomicLong IDS=new AtomicLong();
    final long id=IDS.incrementAndGet(),created=SystemClock.elapsedRealtime();
    final String kind;
    private long dnsAt,connectAt,tlsAt,sent,headers,networkAt,dns,connect,tls;
    private int code;
    ApiTrace(HttpUrl url){
        String path=url.encodedPath();
        kind=path.contains("/episodes/") || path.equals("/vod/episode")?"episodes":
                path.contains("/recommend")?"recommend":path.contains("/related/")?"related":
                path.contains("/detail/") || path.startsWith("/v0/subjects/")?"detail":
                path.contains("/search")?"search":path.contains("/list")?"index":path.startsWith("/vod/")?"sources":"other";
    }
    void mark(String stage,long millis){Log.i("AniTVNet","id="+id+" kind="+kind+" stage="+stage+" ms="+millis);}
    EventListener listener(){return new EventListener(){
        @Override public void callStart(Call call){networkAt=SystemClock.elapsedRealtime();mark("queue",networkAt-created);}
        @Override public void dnsStart(Call call,String domain){dnsAt=SystemClock.elapsedRealtime();}
        @Override public void dnsEnd(Call call,String domain,List<java.net.InetAddress> addresses){dns+=SystemClock.elapsedRealtime()-dnsAt;}
        @Override public void connectStart(Call call,InetSocketAddress address,Proxy proxy){connectAt=SystemClock.elapsedRealtime();}
        @Override public void secureConnectStart(Call call){tlsAt=SystemClock.elapsedRealtime();}
        @Override public void secureConnectEnd(Call call,Handshake handshake){tls+=SystemClock.elapsedRealtime()-tlsAt;}
        @Override public void connectEnd(Call call,InetSocketAddress address,Proxy proxy,Protocol protocol){connect+=SystemClock.elapsedRealtime()-connectAt;}
        @Override public void requestHeadersEnd(Call call,okhttp3.Request request){sent=SystemClock.elapsedRealtime();}
        @Override public void responseHeadersStart(Call call){headers=SystemClock.elapsedRealtime();}
        @Override public void responseHeadersEnd(Call call,Response response){code=response.code();}
        private void end(String state){long now=SystemClock.elapsedRealtime();Log.i("AniTVNet","id="+id+" kind="+kind+" stage="+state+" total="+(now-created)+" dns="+dns+" connect="+connect+" tls="+tls+" first="+(headers>0&&sent>0?headers-sent:-1)+" body="+(headers>0?now-headers:-1)+" code="+code);}
        @Override public void callEnd(Call call){end("network_done");}
        @Override public void callFailed(Call call,IOException error){end(call.isCanceled()?"cancelled":"network_failed");}
    };}
    static void decode(long wait,long work){Log.i("AniTVNet","stage=decode queue="+wait+" work="+work);}
}
