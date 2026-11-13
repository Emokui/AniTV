package tv.anitv;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class DetailRelatedTest {
    @Test public void relatedNavigationReleasesHiddenPageAndRebuildsItOnReturn()throws Exception{
        installApi("[{\"id\":43,\"title\":\"当前番剧 第二季\"}]");
        try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();RecyclerView grid=(RecyclerView)field(activity,"relatedGrid");await(()->grid.getAdapter().getItemCount()==1);layout(activity.getWindow().getDecorView());
            assertFalse(grid.getClipChildren());View card=grid.findViewHolderForAdapterPosition(0).itemView;
            android.view.ViewGroup.MarginLayoutParams margins=(android.view.ViewGroup.MarginLayoutParams)card.getLayoutParams();
            assertTrue(grid.getHeight()>=card.getHeight()+margins.topMargin+margins.bottomMargin+grid.getPaddingTop()+grid.getPaddingBottom());
            grid.findViewHolderForAdapterPosition(0).itemView.performClick();controller.pause().stop();
            assertNull(field(activity,"relatedGrid"));assertTrue(((java.util.List<?>)field(activity,"episodes")).isEmpty());assertEquals(true,field(activity,"contentReleased"));
            controller.restart().start().resume().visible();assertNotNull(field(activity,"relatedGrid"));assertEquals(false,field(activity,"contentReleased"));
            await(()->{try{return ((RecyclerView)field(activity,"relatedGrid")).getAdapter().getItemCount()==1;}catch(Exception e){throw new AssertionError(e);}});
        }
    }
    private View findText(View v,String text){if(v instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)v).getText()))return v;if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){View result=findText(((android.view.ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}return null;}
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void installApi(String related)throws Exception {
        installApi(related,"{\"id\":42,\"title\":\"当前番剧\",\"overview\":\"介绍内容\"}");
    }
    private void installApi(String related,String detailBody)throws Exception {
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{
            Request request=chain.request();String path=request.url().encodedPath();String body;
            if(path.equals("/bangumi/detail/42"))body=detailBody;
            else if(path.equals("/bangumi/related/42")){assertTrue(request.url().queryParameterNames().isEmpty());body=related;}
            else if(path.equals("/vod/episode"))body="[\"第1集\"]";
            else body="[]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    private ActivityController<DetailActivity> detail(){return Robolectric.buildActivity(DetailActivity.class,new Intent(RuntimeEnvironment.getApplication(),DetailActivity.class).putExtra("id",42L).putExtra("title","当前番剧")).setup().visible().windowFocusChanged(true);}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(condition.getAsBoolean());}
    private void layout(View root){root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void relatedCardOpensCorrectSeasonAndRemoteReturnsToPlay()throws Exception {
        installApi("{\"data\":[{\"id\":42,\"title\":\"当前\"},{\"id\":43,\"title\":\"当前番剧 第二季\",\"type\":\"续作\"},{\"id\":44,\"title\":\"当前番剧 剧场版\",\"type\":\"剧场版\"}]}");
        try(ActivityController<DetailActivity> controller=detail()) {
            DetailActivity activity=controller.get();RecyclerView grid=(RecyclerView)field(activity,"relatedGrid");
            await(()->grid.getAdapter().getItemCount()==2);layout(activity.getWindow().getDecorView());
            assertEquals(View.VISIBLE,((View)field(activity,"relatedBlock")).getVisibility());
            View play=(View)field(activity,"play");Shadows.shadowOf(activity).setCurrentFocus(play);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));layout(activity.getWindow().getDecorView());
            assertNull(findText(activity.getWindow().getDecorView(),"查看完整简介"));
            RecyclerView.ViewHolder first=grid.findViewHolderForAdapterPosition(0);assertNotNull(first);assertTrue(first.itemView.hasFocus());
            Shadows.shadowOf(activity).setCurrentFocus(first.itemView);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));assertTrue(play.hasFocus());
            first.itemView.performClick();Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);assertEquals(DetailActivity.class.getName(),intent.getComponent().getClassName());assertEquals(43,intent.getLongExtra("id",0));assertEquals("当前番剧 第二季",intent.getStringExtra("title"));
        }
    }
    @Test public void emptyRelatedResponseDoesNotReserveSpace()throws Exception {
        installApi("[]");try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();await(()->{try{return (Integer)field(activity,"relatedRevision")>0;}catch(Exception e){throw new AssertionError(e);}});
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,((View)field(activity,"relatedBlock")).getVisibility());
            assertEquals(0,((RecyclerView)field(activity,"relatedGrid")).getAdapter().getItemCount());
        }
    }
    @Test public void detailAliasesRefilterRelatedResultsWithoutAnotherRequest()throws Exception{
        installApi("[{\"id\":43,\"title\":\"呪術廻戦 第2期\"},{\"id\":44,\"title\":\"被流放的开挂魔术师\"}]",
                "{\"id\":42,\"title\":\"咒术回战\",\"name\":\"呪術廻戦\",\"overview\":\"介绍\"}");
        try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();RecyclerView grid=(RecyclerView)field(activity,"relatedGrid");
            await(()->grid.getAdapter().getItemCount()==1);
            assertEquals(43,((PosterAdapter)grid.getAdapter()).getCurrentList().get(0).id);
        }
    }
    @Test public void unrelatedNonemptyResponseHidesEntireBlock()throws Exception{
        installApi("[{\"id\":43,\"title\":\"命运石之门0特别篇\"}]");
        try(ActivityController<DetailActivity> controller=detail()){
            DetailActivity activity=controller.get();
            await(()->{try{return !((java.util.List<?>)field(activity,"relatedCandidates")).isEmpty();}catch(Exception e){throw new AssertionError(e);}});
            assertEquals(View.GONE,((View)field(activity,"relatedBlock")).getVisibility());
            assertEquals(0,((RecyclerView)field(activity,"relatedGrid")).getAdapter().getItemCount());
        }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void longOverviewIsEllipsizedAtFourLinesAndExpiredRelatedFocusIsReleased()throws Exception{
        String text="很长的简介内容。".repeat(200);installApi("[]","{\"id\":42,\"title\":\"当前番剧\",\"overview\":\""+text+"\"}");
        try(ActivityController<DetailActivity> c=detail()){
            DetailActivity a=c.get();await(()->{try{return text.contentEquals(((android.widget.TextView)field(a,"overview")).getText());}catch(Exception e){throw new AssertionError(e);}});
            android.widget.TextView overview=(android.widget.TextView)field(a,"overview");layout(a.getWindow().getDecorView());assertEquals(4,overview.getMaxLines());assertEquals(android.text.TextUtils.TruncateAt.END,overview.getEllipsize());
            assertEquals(4,overview.getLayout().getLineCount());assertTrue(overview.getLayout().getEllipsisCount(3)>0);assertNull(findText(a.getWindow().getDecorView(),"查看完整简介"));
            java.lang.reflect.Field pending=DetailActivity.class.getDeclaredField("pendingRelated");pending.setAccessible(true);pending.setInt(a,4);
            java.lang.reflect.Method focus=DetailActivity.class.getDeclaredMethod("focusRelated",int.class,int.class);focus.setAccessible(true);focus.invoke(a,4,13);assertEquals(-1,field(a,"pendingRelated"));
        }
    }

}
