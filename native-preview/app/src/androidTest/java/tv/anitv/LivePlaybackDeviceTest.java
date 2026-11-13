package tv.anitv;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit live-service test. No credentials or media URLs are logged. */
@RunWith(AndroidJUnit4.class)
public class LivePlaybackDeviceTest {
    private ActivityScenario<HomeActivity> home;
    private String originalEndpoint;
    private ActivityScenario<DetailActivity> detail;
    @After public void close() {
        if (home != null || detail != null) {
            main(() -> {
                java.util.Set<Activity> activities = new HashSet<>();
                for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED})
                    activities.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
                for (Activity a : activities) if (a instanceof HomeActivity || a instanceof DetailActivity || a instanceof PlayerActivity) a.finish();
            });
            if (home != null) home.close();
            if (detail != null) detail.close();
        }
        if (originalEndpoint != null) new Library(InstrumentationRegistry.getInstrumentation().getTargetContext()).prefs.edit().putString("endpoint", originalEndpoint).commit();
    }
    @Test public void realIndexSearchDetailPlaybackAndRemoteControls() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        Library library = new Library(InstrumentationRegistry.getInstrumentation().getTargetContext());
        originalEndpoint = library.endpoint(); library.prefs.edit().putString("endpoint", Library.DEFAULT_ENDPOINT).commit();
        home = ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), HomeActivity.class)
                .putExtra("demo", false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("real recommendations", 35000, () -> onHomeCount() > 0);
        home.onActivity(a -> find(a.getWindow().getDecorView(), "索引").performClick());
        await("original AniCh index", 35000, () -> {
            AtomicBoolean okay = new AtomicBoolean(); home.onActivity(a -> {
                RecyclerView grid = grid(a.getWindow().getDecorView());
                okay.set(grid.getAdapter().getItemCount() > 0 && grid.getAdapter().getItemId(0) > 0 && grid.getAdapter().getItemId(0) < Show.BANGUMI_OFFSET);
            }); return okay.get();
        });
        home.onActivity(a -> find(a.getWindow().getDecorView(), "索引").requestFocus());
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await("remote enters real index", 5000, () -> position() == 0);
        for (int i = 0; i < 3; i++) { key(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(150); }
        assertTrue("index remote moves down", position() > 0); screenshot("live-index.png");
        home.onActivity(a -> find(a.getWindow().getDecorView(), "搜索").performClick());
        await("search input", 5000, () -> {
            AccessibilityNodeInfo input = classNode(root(), "android.widget.EditText");
            if (input == null) return false;
            Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "间谍过家家");
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
        AccessibilityNodeInfo button = classNode(root(), "android.widget.Button", "搜索"); assertNotNull(button); assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        await("search results focused", 35000, () -> {
            AtomicBoolean okay = new AtomicBoolean(); home.onActivity(a -> {
                RecyclerView grid = grid(a.getWindow().getDecorView());
                if (grid.getAdapter().getItemCount() > 0) {
                    PosterAdapter adapter = (PosterAdapter) grid.getAdapter();
                    okay.set(adapter.getCurrentList().get(0).title.contains("间谍") && position(a) == 0);
                }
            }); return okay.get();
        }); screenshot("live-search.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitActivity(DetailActivity.class, 10000);
        await("real episodes visible", 65000, () -> onResumed(a -> a instanceof DetailActivity && ((java.util.List<?>) field(a, "episodes")).size() > 0));
        screenshot("live-detail.png"); key(KeyEvent.KEYCODE_DPAD_CENTER);
        await("episode chooser", 5000, () -> episodeChooserShowing());
        key(KeyEvent.KEYCODE_DPAD_CENTER); awaitActivity(PlayerActivity.class, 10000);
        await("real video frame and audio track", 100000, () -> player(p -> p.videoRendered() && p.playing() && p.audioTracks() > 0));
        await("real playback clock", 10000, () -> player(p -> p.position() > 1500)); screenshot("live-first-frame.png");
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("pause", 5000, () -> player(p -> !p.wantsPlay() && !p.playing()));
        long paused = clock(); SystemClock.sleep(500); assertTrue(Math.abs(clock() - paused) < 400);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); await("seek paused", 20000, () -> player(p -> !p.playing() && p.position() >= paused + 6000));
        key(KeyEvent.KEYCODE_DPAD_CENTER); await("resume", 10000, () -> player(NativePlayback::playing));
        long saved = clock(); key(KeyEvent.KEYCODE_DPAD_DOWN);
        await("source chooser", 5000, () -> classNode(root(), "android.widget.ListView") != null);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        await("real source switch retains clock", 60000, () -> player(p -> p.videoRendered() && p.playing() && p.position() >= saved - 2500));
        assertTrue(onResumed(a -> a instanceof PlayerActivity && ((Integer) field(a, "sourceIndex")) == 1)); screenshot("live-switched.png");
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_BACK); await("cancel episode resumes", 5000, () -> player(NativePlayback::playing));
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
        await("next real episode", 100000, () -> onResumed(a -> a instanceof PlayerActivity && ((Integer) field(a, "number")) == 2 && engine((PlayerActivity)a).videoRendered() && engine((PlayerActivity)a).playing()));
        screenshot("live-next-episode.png"); key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK); awaitActivity(DetailActivity.class, 10000);
        key(KeyEvent.KEYCODE_BACK); awaitActivity(HomeActivity.class, 10000);
        await("return restores result focus", 5000, () -> position() == 0);
        android.util.Log.i("AniTVLiveTest", "PASS index search detail real_frame audio pause seek source_switch next_episode return_focus");
    }
    @Test public void affectedChineseTitlesUseOriginalNames() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        long[] ids={39528,39419};String[] titles={"药屋少女的呢喃 第三季","学生会也有洞！"};
        for(int i=0;i<ids.length;i++) {
            detail=ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),DetailActivity.class)
                    .putExtra("id",ids[i]).putExtra("title",titles[i]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await("metadata names and episodes",65000,() -> onResumed(a -> a instanceof DetailActivity &&
                    ((String[])field(a,"playbackTitles")).length>1 && !((java.util.List<?>)field(a,"episodes")).isEmpty()));
            screenshot("affected-detail-"+i+".png"); key(KeyEvent.KEYCODE_DPAD_CENTER);
            await("episode list",5000,() -> episodeChooserShowing());
            key(KeyEvent.KEYCODE_DPAD_CENTER); awaitActivity(PlayerActivity.class,10000);
            await("original-name resource renders",100000,() -> player(p -> p.videoRendered()&&p.playing()&&p.audioTracks()>0));
            await("real video clock",10000,() -> player(p -> p.position()>1000));
            screenshot("affected-playing-"+i+".png");
            android.util.Log.i("AniTVLiveTest","PASS affected_title="+titles[i]+" frame audio clock");
            key(KeyEvent.KEYCODE_DPAD_CENTER);await("real pause",5000,() -> player(p -> !p.wantsPlay()&&!p.playing()));
            key(KeyEvent.KEYCODE_DPAD_RIGHT);await("real paused seek",20000,() -> player(p -> !p.playing()&&p.position()>6000));
            key(KeyEvent.KEYCODE_DPAD_CENTER);await("resume real stream",15000,() -> player(NativePlayback::playing));
            long saved=clock();key(KeyEvent.KEYCODE_DPAD_DOWN);
            await("real source list",5000,() -> classNode(root(),"android.widget.ListView")!=null);
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);
            await("switch actual source",60000,() -> player(p -> p.videoRendered()&&p.playing()&&p.position()>=saved-2500));
            assertTrue(onResumed(a -> a instanceof PlayerActivity&&((Integer)field(a,"sourceIndex"))==1));
            android.util.Log.i("AniTVLiveTest","PASS affected_title="+titles[i]+" source_switch retained_clock");
            key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);awaitActivity(DetailActivity.class,10000);detail.close();detail=null;
        }
    }
    private boolean episodeChooserShowing() {
        return onResumed(a -> {
            if (!(a instanceof DetailActivity)) return false;
            android.app.AlertDialog chooser = (android.app.AlertDialog)field(a,"episodeDialog");
            return chooser != null && chooser.isShowing();
        });
    }
    private static boolean loadedCover(View view) {
        if(view instanceof android.widget.ImageView && ((android.widget.ImageView)view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(loadedCover(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    @Test public void authenticatedFullSources() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Login required",new AccountSession(context).available(okhttp3.HttpUrl.get(new Library(context).endpoint())));
        long[] ids={39419,39528};String[] titles={"学生会也有洞！","药屋少女的呢喃 第三季"};StringBuilder report=new StringBuilder();
        for(int i=0;i<ids.length;i++) {
            detail=ActivityScenario.launch(new Intent(context,DetailActivity.class).putExtra("id",ids[i]).putExtra("title",titles[i])
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await("episodes",65000,()->onResumed(a->a instanceof DetailActivity&&!((java.util.List<?>)field(a,"episodes")).isEmpty()));
            assertTrue(onResumed(a -> a instanceof DetailActivity && !((java.util.List<Episode>)field(a,"episodes")).get(0).image.isEmpty()));
            key(KeyEvent.KEYCODE_DPAD_CENTER);await("episode list",5000,()->episodeChooserShowing());
            await("episode cover loaded",20000,()->onResumed(a->{if(!(a instanceof DetailActivity))return false;
                android.app.AlertDialog chooser=(android.app.AlertDialog)field(a,"episodeDialog");
                return loadedCover(chooser.getWindow().getDecorView());}));
            screenshot("episode-covers-"+i+".png");
            onResumed(a->{if(a instanceof DetailActivity){android.app.AlertDialog dialog=(android.app.AlertDialog)field(a,"episodeDialog");androidx.recyclerview.widget.RecyclerView grid=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
                grid.scrollToPosition(0); grid.postDelayed(()->grid.findViewHolderForAdapterPosition(0).itemView.performClick(),200);}return false;});
            awaitActivity(PlayerActivity.class,10000);
            final int expectedEpisode=ids[i]==39528 ? 49 : 1;
            assertTrue(onResumed(a->a instanceof PlayerActivity&&((Integer)field(a,"number"))==expectedEpisode));
            await("full source response",160000,()->onResumed(a->a instanceof PlayerActivity&&!((java.util.List<?>)field(a,"sources")).isEmpty()));
            final int which=i;
            onResumed(a->{if(a instanceof PlayerActivity){java.util.List<PlaySource> sources=(java.util.List<PlaySource>)field(a,"sources");
                report.append(titles[which]).append(" episode=").append(expectedEpisode).append(" sources=").append(sources.size()).append("\n");
                for(int j=0;j<sources.size();j++)report.append(j+1).append(" ").append(sources.get(j).caption).append("\n");
                assertTrue("Full source count",sources.size()>20);
            }return false;});
            saveReport(context, report);
            await("real full-source playback",90000,()->player(p->p.videoRendered()&&p.playing()&&p.audioTracks()>0));
            long saved=clock();boolean switched=false;
            for(int candidate=1;candidate<=3&&!switched;candidate++) {
                key(KeyEvent.KEYCODE_DPAD_DOWN);await("source list",5000,()->classNode(root(),"android.widget.ListView")!=null);
                final int target=candidate;
                onResumed(a->{if(a instanceof PlayerActivity){android.app.AlertDialog dialog=(android.app.AlertDialog)field(a,"dialog");
                    dialog.getListView().performItemClick(null,target,target);}return false;});
                long end=SystemClock.elapsedRealtime()+25000;
                while(SystemClock.elapsedRealtime()<end) {
                    if(player(p->p.videoRendered()&&p.playing()&&p.position()>=saved-2500)){switched=true;break;}
                    if(onResumed(a->a instanceof PlayerActivity&&(Boolean)field(a,"failed")))break;
                    SystemClock.sleep(150);
                }
                report.append("switch candidate=").append(candidate+1).append(switched?" PASS":" unavailable").append("\n");saveReport(context,report);
            }
            assertTrue("At least one alternate real source plays",switched);
            report.append("frame audio switch retained_clock PASS\n");saveReport(context,report);
            key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK);awaitActivity(DetailActivity.class,10000);detail.close();detail=null;
        }
        File directory=new File(context.getExternalFilesDir(null),"live-evidence");directory.mkdirs();
        java.nio.file.Files.write(new File(directory,"authenticated-sources.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        android.util.Log.i("AniTVLiveTest",report.toString());
    }
    @Test public void fullSourceDiagnostics() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        okhttp3.HttpUrl base=okhttp3.HttpUrl.get(new Library(context).endpoint());
        okhttp3.OkHttpClient client=((AniApp)context.getApplicationContext()).http().newBuilder().readTimeout(50,java.util.concurrent.TimeUnit.SECONDS).callTimeout(60,java.util.concurrent.TimeUnit.SECONDS).build();
        StringBuilder report=new StringBuilder();
        for(long id:new long[]{39419,39528}) {
            try(okhttp3.Response response=client.newCall(new okhttp3.Request.Builder().url(base.newBuilder().addPathSegment("vod").addPathSegment(Long.toString(id)).addPathSegment("1").build()).build()).execute()) {
                byte[] bytes=CatalogRepository.body(response);
                report.append(id).append(" HTTP ").append(response.code()).append(" bytes=").append(bytes.length).append("\n");
                byte[] wire=WirePayload.unpack(bytes);com.google.protobuf.CodedInputStream input=com.google.protobuf.CodedInputStream.newInstance(wire);int total=0,valid=0;
                java.util.List<byte[]> records=new java.util.ArrayList<>();
                while(!input.isAtEnd()){int tag=input.readTag();if(tag==10)records.add(input.readByteArray());else if(!input.skipField(tag))break;}
                for(byte[] record:records){total++;try {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();com.google.protobuf.CodedOutputStream coded=com.google.protobuf.CodedOutputStream.newInstance(out);coded.writeByteArray(1,record);coded.flush();PlaybackCodec.sources(out.toByteArray());valid++;}
                    catch(Exception error){report.append("record ").append(total).append(" error=").append(error.getMessage()).append("\n");}}
                report.append("records=").append(total).append(" valid=").append(valid).append("\n");
            }catch(Exception error){report.append(id).append(" error=").append(error.getMessage()).append("\n");}
        }
        File directory=new File(context.getExternalFilesDir(null),"live-evidence");directory.mkdirs();
        java.nio.file.Files.write(new File(directory,"full-source-diagnostics.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        android.util.Log.i("AniTVLiveTest",report.toString());
    }
    @Test public void comparePublishedServiceHosts() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        okhttp3.HttpUrl current=okhttp3.HttpUrl.get(new Library(context).endpoint());
        String session=new AccountSession(context).header(current);
        okhttp3.OkHttpClient client=new okhttp3.OkHttpClient.Builder().followRedirects(false).connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(18,java.util.concurrent.TimeUnit.SECONDS).callTimeout(25,java.util.concurrent.TimeUnit.SECONDS).build();
        StringBuilder report=new StringBuilder();
        // Public AniCh api.json lists these service origins; do not follow redirects carrying session headers.
        for(String host:new String[]{"https://ani.emmmm.eu.org","https://api.500403.xyz",Library.DEFAULT_ENDPOINT}) {
            for(String route:new String[]{"/bangumi/detail/39528","/vod/39528/1"}) {
                try(okhttp3.Response response=client.newCall(new okhttp3.Request.Builder().url(host+route).header("_",session)
                        .header("User-Agent","Emokui/AniTV/2.0.0-preview.8 (https://github.com/Emokui/AniTV)").build()).execute()) {
                    report.append(host).append(route).append(" HTTP ").append(response.code());
                    byte[] bytes=CatalogRepository.body(response);
                    if(route.contains("detail")){org.json.JSONObject json=new org.json.JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));report.append(" title=").append(json.optString("title"));}
                    else report.append(" sources=").append(PlaybackCodec.sources(bytes).size());
                }catch(Exception error){report.append(" error=").append(error.getMessage());}
                report.append("\n");
            }
        }
        File directory=new File(context.getExternalFilesDir(null),"live-evidence");directory.mkdirs();
        java.nio.file.Files.write(new File(directory,"service-host-comparison.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        android.util.Log.i("AniTVLiveTest",report.toString());
    }
    @Test public void inspectCanonicalEpisodeNumbers() throws Exception {
        Assume.assumeTrue("Explicit live test only", "true".equals(InstrumentationRegistry.getArguments().getString("live")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        okhttp3.OkHttpClient client=((AniApp)context.getApplicationContext()).http();String base=new Library(context).endpoint();StringBuilder report=new StringBuilder();
        for(long id:new long[]{39419,39528}) {
            try(okhttp3.Response response=client.newCall(new okhttp3.Request.Builder().url(base+"/bangumi/episodes/"+id).build()).execute()) {
                java.util.List<Episode> episodes=PlaybackCodec.episodes(CatalogRepository.body(response));
                report.append(id).append(" count=").append(episodes.size()).append("\n");
                for(Episode ep:episodes)report.append(ep.number).append(" ").append(ep.title).append("\n");
                if(!episodes.isEmpty())try(okhttp3.Response vod=client.newCall(new okhttp3.Request.Builder().url(base+"/vod/"+id+"/"+episodes.get(0).number).build()).execute()) {
                    report.append("canonical first sources=").append(PlaybackCodec.sources(CatalogRepository.body(vod)).size()).append("\n");
                }
            }catch(Exception e){report.append(id).append(" error=").append(e.getMessage()).append("\n");}
        }
        File dir=new File(context.getExternalFilesDir(null),"live-evidence");dir.mkdirs();java.nio.file.Files.write(new File(dir,"canonical-episodes.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));android.util.Log.i("AniTVLiveTest",report.toString());
    }
    private void saveReport(android.content.Context context,StringBuilder report) throws Exception {
        File directory=new File(context.getExternalFilesDir(null),"live-evidence");directory.mkdirs();
        java.nio.file.Files.write(new File(directory,"authenticated-sources.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void main(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private boolean onResumed(java.util.function.Predicate<Activity> check) {
        AtomicBoolean result = new AtomicBoolean(); main(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) { if (check.test(a)) result.set(true); }
        }); return result.get();
    }
    private void awaitActivity(Class<?> type, int ms) { await(type.getSimpleName(), ms, () -> onResumed(type::isInstance)); }
    private boolean player(java.util.function.Predicate<NativePlayback> check) { return onResumed(a -> a instanceof PlayerActivity && check.test(engine((PlayerActivity)a))); }
    private NativePlayback engine(PlayerActivity a) { return (NativePlayback) field(a, "engine"); }
    private Object field(Object a, String name) { try { java.lang.reflect.Field f=a.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(a); } catch(Exception e) { throw new AssertionError(e); } }
    private long clock() { AtomicLong value=new AtomicLong(); onResumed(a -> { if(a instanceof PlayerActivity)value.set(engine((PlayerActivity)a).position());return false; });return value.get(); }
    private int onHomeCount() { AtomicLong count=new AtomicLong();home.onActivity(a -> count.set(grid(a.getWindow().getDecorView()).getAdapter().getItemCount()));return (int)count.get(); }
    private int position() { AtomicLong position=new AtomicLong(-1);home.onActivity(a -> position.set(position(a)));return (int)position.get(); }
    private int position(Activity a) { View focus=a.getCurrentFocus(); if(focus==null)return -1;RecyclerView.ViewHolder h=grid(a.getWindow().getDecorView()).findContainingViewHolder(focus);return h==null?-1:h.getBindingAdapterPosition(); }
    private void key(int code) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(120); }
    private void await(String what,int ms,BooleanSupplier condition) { long end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(150);}fail(what); }
    private AccessibilityNodeInfo root() { return InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(); }
    private AccessibilityNodeInfo classNode(AccessibilityNodeInfo n,String cls) { return classNode(n,cls,null); }
    private AccessibilityNodeInfo classNode(AccessibilityNodeInfo n,String cls,String text) { if(n==null)return null;if(cls.contentEquals(n.getClassName())&&(text==null||text.contentEquals(n.getText()==null?"":n.getText())))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=classNode(n.getChild(i),cls,text);if(found!=null)return found;}return null; }
    private static View find(View v,String text) { if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null; }
    private static RecyclerView grid(View v) { if(v instanceof RecyclerView)return (RecyclerView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){RecyclerView found=grid(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null; }
    private void screenshot(String name) throws Exception { Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"live-evidence");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle(); }
}
