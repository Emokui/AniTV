package tv.anitv;

import static org.junit.Assert.*;
import android.app.*;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import com.google.protobuf.CodedOutputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class Ui6BehaviorTest {
    Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    void layout(View root){root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    byte[] message(boolean catalog,int ep,String status)throws Exception{ByteArrayOutputStream inner=new ByteArrayOutputStream();CodedOutputStream out=CodedOutputStream.newInstance(inner);if(catalog){out.writeUInt32(1,42);out.writeString(2,"待播番剧");out.writeUInt32(3,ep);if(status!=null)out.writeString(5,status);}else{out.writeUInt32(2,ep);out.writeString(8,"第一话");}out.flush();ByteArrayOutputStream all=new ByteArrayOutputStream();out=CodedOutputStream.newInstance(all);out.writeByteArray(1,inner.toByteArray());out.flush();return all.toByteArray();}
    @Test public void omittedProtobufFalseDoesNotTurnUnavailableEpisodeIntoUnknown()throws Exception{
        List<Episode> episodes=PlaybackCodec.episodes(message(false,1,null));assertEquals(Boolean.FALSE,episodes.get(0).available);assertEquals(Boolean.FALSE,PlaybackCodec.episodes("[{\"sort\":1}]".getBytes(StandardCharsets.UTF_8)).get(0).available);assertNull(PlaybackCodec.episodes("[\"第1集\"]".getBytes(StandardCharsets.UTF_8)).get(0).available);
    }
    @Test public void upcomingAndReleasedCardsAreRetainedAndCursorIsUnchanged()throws Exception{
        Library library=new Library(RuntimeEnvironment.getApplication());
        for(byte[] data:new byte[][]{message(true,0,null),message(true,0,"released"),"[{\"id\":42,\"title\":\"待播番剧\",\"episode\":0,\"status\":\"\"}]".getBytes(StandardCharsets.UTF_8)}){
            CatalogCodec.Page page=CatalogApi.decode(data);assertEquals(1,library.visible(page.shows).size());
            PageAccumulator pages=new PageAccumulator();pages.append(page,true);assertEquals(42,pages.cursor());
        }
    }
    @Test public void cachedEpisodeStatusDoesNotHideCatalogItems(){
        Library library=new Library(RuntimeEnvironment.getApplication());EpisodeCache.clear();
        List<Show> shows=Collections.singletonList(new Show(42,"测试","",""));
        EpisodeCache.put("example",Collections.singletonList(new Episode(1,"未播",false,"",0,false)),EpisodeCache.epoch());assertEquals(Boolean.FALSE,EpisodeCache.get("example").get(0).available);assertEquals(1,library.visible(shows).size());
    }
    @Test public void episodeStatusIsInformationalAndDoesNotBlockSelection(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            AtomicInteger chosen=new AtomicInteger(-1);List<Episode> episodes=Collections.singletonList(new Episode(1,"第一集",false,"",0,false));
            AlertDialog d=EpisodePicker.create(c.get(),"测试","42",episodes,0,chosen::set);d.show();layout(d.getWindow().getDecorView());RecyclerView grid=d.getWindow().getDecorView().findViewWithTag("episode-grid");grid.findViewHolderForAdapterPosition(0).itemView.performClick();assertEquals(0,chosen.get());assertFalse(d.isShowing());
        }
    }
    @Test public void emptyPickerKeepsDefaultCoverButNeverInventsFirstEpisode(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            AtomicInteger chosen=new AtomicInteger(-1);AlertDialog d=EpisodePicker.create(c.get(),"暂无剧集","42",Collections.emptyList(),0,chosen::set);d.show();layout(d.getWindow().getDecorView());
            assertNull(d.getWindow().getDecorView().findViewWithTag("episode-grid"));assertTrue(hasDefaultCover(d.getWindow().getDecorView()));assertEquals(-1,chosen.get());d.dismiss();
        }
    }
    boolean hasDefaultCover(View v){if(v instanceof ImageView && ((ImageView)v).getDrawable() instanceof PosterDrawable)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasDefaultCover(((ViewGroup)v).getChildAt(i)))return true;return false;}
    @Test public void playbackChooserStartsOnCurrentEpisodeEvenWithSortingTabs(){
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()){
            List<Episode> episodes=new ArrayList<>();for(int i=1;i<=81;i++)episodes.add(new Episode(i,"第"+i+"集"));
            AlertDialog d=EpisodePicker.create(c.get(),"长篇","current",episodes,64,true,i->{});d.show();layout(d.getWindow().getDecorView());RecyclerView grid=d.getWindow().getDecorView().findViewWithTag("episode-grid");layout(d.getWindow().getDecorView());
            assertNotNull(grid.findViewHolderForAdapterPosition(14));assertTrue(grid.findViewHolderForAdapterPosition(14).itemView.hasFocus());assertTrue(hasDefaultCover(grid));d.dismiss();
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void overviewNeverExpandsPastFourLines()throws Exception{
        try(ActivityController<DetailActivity> c=Robolectric.buildActivity(DetailActivity.class,new Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("demo",true)).setup().visible()){
            DetailActivity a=c.get();TextView overview=(TextView)field(a,"overview");
            for(int lines:new int[]{1,4,5,2}){
                overview.setText(String.join("\n",Collections.nCopies(lines,"一行简介")));layout(a.getWindow().getDecorView());
                assertEquals(Math.min(lines,4),overview.getLayout().getLineCount());assertEquals(lines>4,overview.getLayout().getEllipsisCount(overview.getLayout().getLineCount()-1)>0);
            }
        }
    }
}
