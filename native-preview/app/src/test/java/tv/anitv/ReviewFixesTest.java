package tv.anitv;

import static org.junit.Assert.*;
import android.app.*;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class ReviewFixesTest {
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    void set(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    void invoke(Object o,String method)throws Exception{Method m=o.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(o);}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!done.getAsBoolean()&&System.nanoTime()<end){idle();Thread.sleep(5);}assertTrue(done.getAsBoolean());}
    void layout(View root,int width,int height){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);idle();}
    void install(AtomicInteger calls,CountDownLatch related,boolean emptyFirst)throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();EpisodeCache.clear();CatalogRepository.clearEpisodeDisk(app.getCacheDir());Field f=AniApp.class.getDeclaredField("http");f.setAccessible(true);
        f.set(app,new OkHttpClient.Builder().addInterceptor(chain->{String path=chain.request().url().encodedPath(),body="[]";
            if(path.equals("/bangumi/detail/42"))body="{\"id\":42,\"title\":\"番剧\",\"overview\":\"简介\"}";
            else if(path.equals("/vod/episode")){int n=calls.incrementAndGet();body=emptyFirst && n==1?"[]":"[\"第1集\"]";}
            else if(path.equals("/bangumi/related/42")){if(related!=null)try{related.await();}catch(InterruptedException e){throw new java.io.IOException(e);}body="[{\"id\":43,\"title\":\"番剧 第二季\",\"type\":\"续作\"}]";}
            return new Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    ActivityController<DetailActivity> detail(){return Robolectric.buildActivity(DetailActivity.class,new Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("id",42L).putExtra("title","番剧")).setup().visible().windowFocusChanged(true);}
    boolean loaded(DetailActivity a){try{return (boolean)field(a,"episodesLoaded");}catch(Exception e){throw new AssertionError(e);}}
    @Test public void emptyEpisodeResponseHasManualRetryInTheSameDetail()throws Exception{
        AtomicInteger calls=new AtomicInteger();install(calls,null,true);
        try(ActivityController<DetailActivity> c=detail()){
            DetailActivity a=c.get();await(()->loaded(a));((View)field(a,"play")).performClick();AlertDialog empty=(AlertDialog)field(a,"episodeDialog");assertTrue(empty.isShowing());assertEquals(1,calls.get());assertNull(empty.getWindow().getDecorView().findViewWithTag("episode-grid"));
            empty.getWindow().getDecorView().findViewWithTag("episode-retry").performClick();
            await(()->{try{return loaded(a)&&((List<?>)field(a,"episodes")).size()==1;}catch(Exception e){throw new AssertionError(e);}});
            AlertDialog populated=(AlertDialog)field(a,"episodeDialog");assertNotSame(empty,populated);assertNotNull(populated.getWindow().getDecorView().findViewWithTag("episode-grid"));assertEquals(2,calls.get());
        }
    }
    @Test public void lateRelatedResultsDoNotStealFocusBehindEpisodeDialog()throws Exception{
        AtomicInteger calls=new AtomicInteger();CountDownLatch related=new CountDownLatch(1);install(calls,related,false);
        try(ActivityController<DetailActivity> c=detail()){
            DetailActivity a=c.get();await(()->loaded(a));layout(a.getWindow().getDecorView(),960,540);
            set(a,"savedRelatedFocus",true);set(a,"restoreDetailPosition",true);((View)field(a,"play")).performClick();assertEquals(false,field(a,"savedRelatedFocus"));assertEquals(false,field(a,"restoreDetailPosition"));
            AlertDialog picker=(AlertDialog)field(a,"episodeDialog");related.countDown();await(()->{try{return ((RecyclerView)field(a,"relatedGrid")).getAdapter().getItemCount()==1;}catch(Exception e){throw new AssertionError(e);}});
            assertTrue(picker.isShowing());assertEquals(0,((ScrollView)field(a,"pageScroll")).getScrollY());assertEquals(-1,field(a,"pendingRelated"));
        }finally{related.countDown();}
    }
    @Test public void directionAndEmptyRelatedResultsCancelSavedFocus()throws Exception{
        install(new AtomicInteger(),null,false);
        try(ActivityController<DetailActivity> c=detail()){
            DetailActivity a=c.get();set(a,"savedRelatedFocus",true);Shadows.shadowOf(a).setCurrentFocus((View)field(a,"play"));a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(false,field(a,"savedRelatedFocus"));
            set(a,"savedRelatedFocus",true);set(a,"relatedCandidates",new ArrayList<Show>());invoke(a,"displayRelated");assertEquals(false,field(a,"savedRelatedFocus"));
        }
    }
    @Test public void detailLabelsOnlyRealHistory()throws Exception{
        install(new AtomicInteger(),null,false);
        for(int history:new int[]{0,1,99}){
            if(history>0)new PlaybackHistory(RuntimeEnvironment.getApplication()).save("42",history,1000,100000);
            try(ActivityController<DetailActivity> c=detail()){
                DetailActivity a=c.get();await(()->loaded(a));((View)field(a,"play")).performClick();AlertDialog d=(AlertDialog)field(a,"episodeDialog");layout(d.getWindow().getDecorView(),920,500);
                RecyclerView grid=d.getWindow().getDecorView().findViewWithTag("episode-grid");String label=((TextView)field(grid.findViewHolderForAdapterPosition(0),"number")).getText().toString();assertEquals(history==1,label.contains("上次看到"));assertFalse(label.contains("上次选择"));
            }
        }
    }
    @Test public void noHistoryDescendingSinglePageStartsAtLatestWhenLeavingSortButton()throws Exception{
        for(int count:new int[]{49,50}){
            AniApp app=(AniApp)RuntimeEnvironment.getApplication();EpisodeCache.clear();CatalogRepository.clearEpisodeDisk(app.getCacheDir());new PlaybackHistory(app).clear();
            StringBuilder body=new StringBuilder("[");for(int i=1;i<=count;i++){if(i>1)body.append(',');body.append('"').append("第").append(i).append("集").append('"');}body.append(']');final String episodes=body.toString();
            Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{String path=chain.request().url().encodedPath();String json=path.equals("/vod/episode")?episodes:path.equals("/bangumi/detail/42")?"{\"id\":42,\"title\":\"番剧\",\"overview\":\"简介\"}":"[]";return new Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create(json,MediaType.get("application/json"))).build();}).build());
            new Library(app).prefs.edit().putBoolean("episode-order:"+new Library(app).endpoint()+"|42",true).commit();
            try(ActivityController<DetailActivity> c=detail()){
                DetailActivity a=c.get();await(()->loaded(a));((View)field(a,"play")).performClick();AlertDialog d=(AlertDialog)field(a,"episodeDialog");layout(d.getWindow().getDecorView(),920,500);
                RecyclerView grid=d.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(-1,field(grid.getAdapter(),"selected"));assertEquals(count,grid.getAdapter().getItemId(0));assertFalse(grid.findViewHolderForAdapterPosition(0).itemView.isSelected());
                View order=d.getWindow().getDecorView().findViewWithTag("episode-order");assertTrue(order.hasFocus());order.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(d.getWindow().getDecorView(),920,500);assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());
            }
        }
    }
    @Test public void visibleWindowOffsetCountsOnlyShownCardsAndSurvivesRestoreAndPrepend()throws Exception{
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);List<CatalogCodec.Page> frames=new ArrayList<>();
        for(int n=0;n<4;n++){List<Show> shows=new ArrayList<>();for(int i=0;i<5;i++)shows.add(new Show(100-n*5-i,"番剧","","",n==0&&i==0));CatalogCodec.Page page=new CatalogCodec.Page(shows,shows.get(4).id);frames.add(page);pages.append(page,true);}
        assertEquals(5,pages.offset());assertEquals(4,pages.offset(false));assertEquals(81,pages.cursor());PageAccumulator restored=new PageAccumulator();restored.restore(pages.save());assertEquals(4,restored.offset(false));restored.prepend(restored.previousCursor(),frames.get(0));assertEquals(0,restored.offset(false));assertEquals(0,restored.offset());
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="w853dp-h480dp-land")
    public void largeFontEpisodeCardsFitAndNoSegmentHintAppearsForFortyNineEpisodes(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            Activity a=c.get();android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());config.fontScale=1.3f;a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());a.getResources().getDisplayMetrics().scaledDensity=a.getResources().getDisplayMetrics().density*1.3f;
            for(int count:new int[]{13,49,50,51}){
                List<Episode> episodes=new ArrayList<>();for(int i=1;i<=count;i++)episodes.add(new Episode(i,"独立的单集标题",false,"",0,true));AlertDialog d=EpisodePicker.create(a,"番剧","size"+count,episodes,0,i->{});d.show();layout(d.getWindow().getDecorView(),805,480);
                RecyclerView grid=d.getWindow().getDecorView().findViewWithTag("episode-grid");RecyclerView.ViewHolder second=grid.findViewHolderForAdapterPosition(7);assertNotNull(second);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(second.itemView.getLocalVisibleRect(visible));assertEquals(second.itemView.getHeight(),visible.height());
                String all=text(d.getWindow().getDecorView());assertEquals(count==13||count==51,all.contains("分段/排序"));assertEquals(0,d.getWindow().getDecorView().getPaddingLeft());assertEquals(Math.min(Ui.dp(a,920),a.getResources().getDisplayMetrics().widthPixels-Ui.dp(a,48)),d.getWindow().getAttributes().width);d.dismiss();
            }
        }
    }
    String text(View v){String result=v instanceof TextView?((TextView)v).getText().toString():"";if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)result+=" "+text(((ViewGroup)v).getChildAt(i));return result;}
    @Test @Config(sdk=26) public void filterWindowClearsDecorPaddingAfterShow(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            AlertDialog d=FilterPicker.create(c.get(),"年份",new String[]{"全部","2026"},0,(dialog,i)->{});d.getWindow().getDecorView().setPadding(19,19,19,19);d.show();idle();assertEquals(0,d.getWindow().getDecorView().getPaddingLeft());assertEquals(0,d.getWindow().getDecorView().getPaddingTop());d.dismiss();
        }
    }
}
