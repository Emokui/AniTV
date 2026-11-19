package tv.anitv;

import android.app.Application;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public final class AniApp extends Application {
    private OkHttpClient http;
    public OkHttpClient http() { return http; }
    @Override public void onCreate() {
        super.onCreate();
        AccountSession session=new AccountSession(this);
        http = new OkHttpClient.Builder().eventListenerFactory(call->{ApiTrace trace=call.request().tag(ApiTrace.class);return trace==null?okhttp3.EventListener.NONE:trace.listener();}).addNetworkInterceptor(chain -> {
            okhttp3.Request request=chain.request();okhttp3.Request.Builder builder=request.newBuilder().removeHeader("_");
            String header=request.url().encodedPath().equals("/login") ? "" : session.header(request.url());if(!header.isEmpty())builder.header("_",header);
            return chain.proceed(builder.build());
        }).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build();
    }
}
