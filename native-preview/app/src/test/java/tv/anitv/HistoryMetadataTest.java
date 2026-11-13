package tv.anitv;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class HistoryMetadataTest {
    private Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(ready.getAsBoolean());}
    private void api(List<String> paths,boolean failure)throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            Request request=chain.request();String path=request.url().encodedPath();paths.add(path);
            String id=path.substring(path.lastIndexOf('/')+1);
            String body=path.startsWith("/bangumi/detail/")?"{\"data\":{\"id\":"+id+",\"title\":\"真实番剧"+id+"\",\"image\":\"https://example.org/"+id+".jpg\",\"titles\":[\"原名"+id+"\"]}}":"[]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(failure?503:200).message("test")
                    .body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    @Test public void legacyIdsAreHydratedPersistentlyWithoutChangingOrderOrProgress()throws Exception{
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);history.save("42",49,87000,1400000);history.save("43",2,123000,1400000);
        String endpoint=new Library(context).endpoint();List<PlaybackHistory.Entry> before=history.entries(endpoint);
        assertEquals("历史番剧 #43",before.get(0).show.title);
        List<String> paths=new CopyOnWriteArrayList<>();api(paths,false);AtomicBoolean done=new AtomicBoolean();
        try(HistoryMetadata resolver=new HistoryMetadata(context,before,new HistoryMetadata.Listener(){public void changed(){}public void complete(int failures){assertEquals(0,failures);done.set(true);}})){
            resolver.start();await(done::get);
            List<Show> shows=new PlaybackHistory(context).shows(endpoint);assertEquals(43,shows.get(0).id);assertEquals(42,shows.get(1).id);
            assertEquals("真实番剧42",shows.get(1).title);assertEquals("https://example.org/42.jpg",shows.get(1).image);
            assertEquals(49,history.lastEpisode("42",1));assertEquals(87000,history.position("42",49));
            assertTrue(paths.contains("/bangumi/detail/42"));assertTrue(paths.contains("/bangumi/detail/43"));
            for(String title:history.find(42,endpoint).titles)assertFalse(title.startsWith("历史番剧 #"));
        }
    }
    @Test public void placeholderPlaybackCannotOverwriteRecoveredTitleCoverAndAliases(){
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);history.clear();String endpoint=new Library(context).endpoint();
        history.remember(new Show(42,"真实标题","https://example.org/p.jpg","正篇"),endpoint,new String[]{"真实标题","原名"});
        history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[]{"历史番剧 #42"});
        PlaybackHistory.Entry entry=history.find(42,endpoint);assertEquals("真实标题",entry.show.title);assertEquals("https://example.org/p.jpg",entry.show.image);assertEquals(2,entry.titles.length);
    }
    @Test public void staleEnrichmentCannotRecreateClearedHistoryAndWrongIdsAreRejected()throws Exception{
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);String endpoint=new Library(context).endpoint();
        history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[0]);long revision=history.revision();
        PlaybackHistory.Entry source=history.find(42,endpoint);history.clear();
        PlaybackHistory.Entry fixed=new PlaybackHistory.Entry(new Show(42,"真实标题","https://example.org/p.jpg",""),endpoint,new String[]{"真实标题"});
        assertFalse(history.enrich(fixed,revision));assertTrue(history.entries(endpoint).isEmpty());
        assertThrows(java.io.IOException.class,()->HistoryMetadata.decode("{\"id\":43,\"title\":\"另一部\"}".getBytes(StandardCharsets.UTF_8),source));
        assertThrows(java.io.IOException.class,()->HistoryMetadata.decode("{\"id\":42}".getBytes(StandardCharsets.UTF_8),source));
    }
    @Test public void failureLeavesResumableRecordAndReportsRetryInsteadOfClearingIt()throws Exception{
        Context context=RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(context);history.save("42",7,45000,100000);
        String endpoint=new Library(context).endpoint();api(new CopyOnWriteArrayList<>(),true);AtomicBoolean done=new AtomicBoolean();
        try(HistoryMetadata resolver=new HistoryMetadata(context,history.entries(endpoint),new HistoryMetadata.Listener(){public void changed(){}public void complete(int failures){assertEquals(1,failures);done.set(true);}})){
            resolver.start();await(done::get);assertEquals(45000,history.position("42",7));assertEquals(1,history.entries(endpoint).size());assertTrue(PlaybackHistory.missingTitle(history.find(42,endpoint).show));
        }
    }
    @Test public void historyPageRepairsAlreadyMigratedPlaceholderAndOpensDetailWithRealTitle()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);String endpoint=new Library(app).endpoint();
        history.clear();history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[]{"历史番剧 #42"});history.save("42",49,99000,1000000);
        api(new CopyOnWriteArrayList<>(),false);
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class,new Intent(app,HomeActivity.class).putExtra("demo",true)).setup().visible()){
            HomeActivity home=controller.get();((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();
            await(()->"真实番剧42".equals(history.find(42,endpoint).show.title));
            RecyclerView grid=(RecyclerView)field(home,"grid");PosterAdapter adapter=(PosterAdapter)grid.getAdapter();
            await(()->adapter.getItemCount()==1 && "真实番剧42".equals(adapter.getCurrentList().get(0).title));
            assertEquals("https://example.org/42.jpg",adapter.getCurrentList().get(0).image);
            PosterAdapter.Holder holder=adapter.onCreateViewHolder(grid,0);adapter.onBindViewHolder(holder,0);holder.itemView.performClick();
            Intent player=Shadows.shadowOf(home).getNextStartedActivity();assertNotNull(player);assertEquals("真实番剧42",player.getStringExtra("title"));assertEquals("https://example.org/42.jpg",player.getStringExtra("image"));assertEquals(DetailActivity.class.getName(),player.getComponent().getClassName());assertEquals(42,player.getLongExtra("id",0));assertEquals(49,history.lastEpisode("42",1));assertEquals(99000,history.position("42",49));
        }
    }
    @Test public void placeholderDetailRecoversMetadataAndEpisodeSelectionKeepsSavedEpisode()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);String endpoint=new Library(app).endpoint();
        history.clear();history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[0]);history.save("42",49,99000,1000000);
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{Request request=chain.request();String body="[]";
            if(request.url().encodedPath().equals("/bangumi/detail/42"))body="{\"id\":42,\"title\":\"真实番剧42\",\"image\":\"https://example.org/42.jpg\",\"overview\":\"简介\"}";
            else if(request.url().encodedPath().equals("/vod/episode") && "真实番剧42".equals(request.url().queryParameter("title")))body="[\"第48集\",\"第49集\"]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        Intent detail=new Intent(app,DetailActivity.class).putExtra("id",42L).putExtra("title","历史番剧 #42");
        try(ActivityController<DetailActivity> controller=Robolectric.buildActivity(DetailActivity.class,detail).setup().visible()){
            DetailActivity activity=controller.get();
            await(()->{try{return ((List<?>)field(activity,"episodes")).size()==2;}catch(Exception error){throw new AssertionError(error);}});
            assertEquals("真实番剧42",((Show)field(activity,"show")).title);assertEquals("https://example.org/42.jpg",((Show)field(activity,"show")).image);
            assertEquals("真实番剧42",history.find(42,endpoint).show.title);assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            ((View)field(activity,"play")).performClick();android.app.AlertDialog dialog=(android.app.AlertDialog)field(activity,"episodeDialog");
            RecyclerView chooser=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(1,field(chooser.getAdapter(),"selected"));
            View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
            RecyclerView.ViewHolder holder=chooser.findViewHolderForAdapterPosition(1);assertNotNull(holder);holder.itemView.performClick();
            Intent player=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(player);assertEquals(49,player.getIntExtra("episode",0));assertEquals("真实番剧42",player.getStringExtra("title"));assertEquals("https://example.org/42.jpg",player.getStringExtra("image"));assertEquals(99000,history.position("42",49));
        }
    }

}
