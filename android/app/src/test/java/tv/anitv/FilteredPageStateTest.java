package tv.anitv;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class FilteredPageStateTest {
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void invoke(HomeActivity h,String name,Class<?> type,Object value)throws Exception{Method m=HomeActivity.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(h,value);}
    private void http(Interceptor interceptor)throws Exception{Field f=AniApp.class.getDeclaredField("http");f.setAccessible(true);f.set(RuntimeEnvironment.getApplication(),new OkHttpClient.Builder().addInterceptor(interceptor).build());}
    private Response response(Request r,String body,int status){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("test").body(ResponseBody.create(body,MediaType.get("application/json"))).build();}
    private String page(int id,boolean adult){return "[{\"id\":"+id+",\"title\":\"番剧\",\"r18\":"+adult+"}]";}
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertTrue(ready.getAsBoolean());}
    private boolean settled(HomeActivity h){try{return !(boolean)field(h,"loading") && !(boolean)field(h,"listApplying");}catch(Exception e){throw new AssertionError(e);}}
    private boolean failed(HomeActivity h){try{return settled(h) && field(h,"requestFailure")!=null;}catch(Exception e){throw new AssertionError(e);}}
    private PosterAdapter adapter(HomeActivity h)throws Exception{return (PosterAdapter)field(h,"adapter");}
    private PageAccumulator pages(HomeActivity h)throws Exception{return (PageAccumulator)field(h,"pages");}
    private TextView next(HomeActivity h)throws Exception{return (TextView)field(h,"retryMore");}
    private View emptyAction(HomeActivity h)throws Exception{return ((List<View>)field(h,"emptyActions")).get(0);}
    private void layout(HomeActivity h){View root=h.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));}
    private void focusTracking(HomeActivity h){h.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->Shadows.shadowOf(h).setCurrentFocus(newFocus));}
    private void press(HomeActivity h,int key){h.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));h.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}
    private void block(CountDownLatch latch)throws java.io.IOException{try{if(!latch.await(4,TimeUnit.SECONDS))throw new java.io.IOException("test timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}}
    private void index(HomeActivity h)throws Exception{((List<View>)field(h,"rail")).get(1).performClick();await(()->settled(h));layout(h);}
    private void focusNext(HomeActivity h)throws Exception{assertEquals(View.VISIBLE,next(h).getVisibility());assertTrue(next(h).requestFocus());Shadows.shadowOf(h).setCurrentFocus(next(h));}

    @Test public void refreshingFullyFilteredPageKeepsLoadingAndFailureActionsVisibleAndRetainsCursor()throws Exception{
        AtomicBoolean fail=new AtomicBoolean();CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        http(chain->{Request r=chain.request();if(!r.url().encodedPath().equals("/bangumi/list"))return response(r,"[]",200);
            if(fail.get()){entered.countDown();block(release);return response(r,"unavailable",503);}return response(r,page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity h=c.get();index(h);assertEquals(0,adapter(h).getItemCount());fail.set(true);invoke(h,"reload",boolean.class,true);
            assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();await(()->failed(h));
            assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());assertTrue(emptyAction(h).isShown());assertTrue(emptyAction(h).requestFocus());
            assertEquals(100,pages(h).cursor());assertEquals(1,pages(h).snapshot().size());assertEquals(0,adapter(h).getItemCount());
        }finally{release.countDown();}
    }
    @Test public void cachedFilteredSearchThenNetworkFailureKeepsEmptyPanelAndReloadReachable()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();String keyword="测试";CountDownLatch release=new CountDownLatch(1);
        Request request=new Request.Builder().url(CatalogApi.url(HttpUrl.get(new Library(app).endpoint()),3,0,keyword,"","","",false)).build();
        File dir=new File(app.getCacheDir(),"catalog");dir.mkdirs();Files.write(new File(dir,CatalogRepository.requestKey(request)).toPath(),page(100,true).getBytes(StandardCharsets.UTF_8));
        http(chain->{block(release);return response(chain.request(),"unavailable",503);});Bundle state=new Bundle();state.putInt("section",HomeActivity.SEARCH);state.putString("keyword",keyword);
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup(state).visible()){
            HomeActivity h=c.get();await(()->{try{return pages(h).snapshot().size()==1;}catch(Exception e){throw new AssertionError(e);}});
            assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());release.countDown();await(()->failed(h));layout(h);
            assertEquals(0,adapter(h).getItemCount());assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());assertTrue(emptyAction(h).requestFocus());assertTrue(emptyAction(h).isShown());
        }finally{release.countDown();}
    }
    @Test public void failedHiddenNextPageKeepsRetryFocusAndCanNavigateBackToReload()throws Exception{
        CountDownLatch release=new CountDownLatch(1);AtomicInteger nextCalls=new AtomicInteger();
        http(chain->{Request r=chain.request();if(!r.url().encodedPath().equals("/bangumi/list"))return response(r,"[]",200);
            if("100".equals(r.url().queryParameter("skip"))){nextCalls.incrementAndGet();block(release);return response(r,"unavailable",503);}return response(r,page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)){
            HomeActivity h=c.get();focusTracking(h);index(h);focusNext(h);press(h,KeyEvent.KEYCODE_DPAD_CENTER);await(()->nextCalls.get()==1);
            assertTrue(next(h).hasFocus());assertEquals("正在加载…",next(h).getText().toString());next(h).performClick();assertEquals(1,nextCalls.get());
            release.countDown();await(()->failed(h));layout(h);assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());assertEquals("重试下一页",next(h).getText().toString());assertTrue(next(h).hasFocus());
            press(h,KeyEvent.KEYCODE_DPAD_UP);assertTrue(emptyAction(h).hasFocus());press(h,KeyEvent.KEYCODE_DPAD_DOWN);assertTrue(next(h).hasFocus());assertEquals(100,pages(h).cursor());
        }finally{release.countDown();}
    }
    @Test public void consecutiveFilteredPagesKeepConfirmFocusUntilVisibleCardsArrive()throws Exception{
        CountDownLatch release=new CountDownLatch(1);AtomicInteger nextCalls=new AtomicInteger();
        http(chain->{Request r=chain.request();if(!r.url().encodedPath().equals("/bangumi/list"))return response(r,"[]",200);String skip=r.url().queryParameter("skip");
            if("100".equals(skip)){nextCalls.incrementAndGet();block(release);return response(r,page(90,true),200);}
            if("90".equals(skip)){nextCalls.incrementAndGet();return response(r,"{\"data\":[{\"id\":80,\"title\":\"可见番剧\"}],\"next\":-1}",200);}return response(r,page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)){
            HomeActivity h=c.get();focusTracking(h);index(h);focusNext(h);press(h,KeyEvent.KEYCODE_DPAD_CENTER);await(()->nextCalls.get()==1);
            assertTrue(next(h).hasFocus());press(h,KeyEvent.KEYCODE_DPAD_CENTER);assertEquals(1,nextCalls.get());release.countDown();await(()->settled(h));layout(h);
            assertTrue(next(h).hasFocus());assertEquals("继续加载下一页",next(h).getText().toString());assertEquals(90,pages(h).cursor());assertEquals(1,nextCalls.get());
            press(h,KeyEvent.KEYCODE_DPAD_CENTER);await(()->settled(h));for(int i=0;i<6;i++)layout(h);
            RecyclerView grid=(RecyclerView)field(h,"grid");assertEquals(1,adapter(h).getItemCount());assertEquals(80,adapter(h).getItemId(0));assertTrue(grid.hasFocus());assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS,grid.getDescendantFocusability());assertEquals(2,nextCalls.get());
        }finally{release.countDown();}
    }
    @Test public void successfulPreviousWindowClearsOldErrorAndReleasesFocusBlockWhenAllCardsBecomeHidden()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();Library library=new Library(app);PageAccumulator pages=new PageAccumulator();pages.windowed(true);
        for(int n=0;n<4;n++)pages.append(new CatalogCodec.Page(Arrays.asList(new Show(100-n*10,"番剧","","",n<3)),100-n*10),true);
        Bundle state=new Bundle();state.putInt("section",1);state.putSerializable("catalog-window",pages.save());state.putString("catalog-endpoint",library.endpoint());state.putLong("catalog-account",new AccountSession(app).revision());state.putLong("catalog-at",System.currentTimeMillis());
        state.putString("catalog-query",CatalogRepository.requestKey(new Request.Builder().url(CatalogApi.url(HttpUrl.get(library.endpoint()),1,0,"","","","",false)).build()));
        http(chain->{Request r=chain.request();return "70".equals(r.url().queryParameter("skip"))?response(r,"unavailable",503):response(r,page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup(state).visible().windowFocusChanged(true)){
            HomeActivity h=c.get();focusTracking(h);for(int i=0;i<3;i++)layout(h);RecyclerView grid=(RecyclerView)field(h,"grid");
            assertEquals(0,field(h,"leadingSlots"));View card=grid.findViewHolderForAdapterPosition(0).itemView;card.requestFocus();Shadows.shadowOf(h).setCurrentFocus(card);await(()->failed(h));assertTrue(((TextView)field(h,"message")).getText().toString().contains("暂时无法"));
            press(h,KeyEvent.KEYCODE_DPAD_UP);await(()->settled(h) && grid.getAdapter().getItemCount()==0);for(int i=0;i<3;i++)layout(h);
            assertNull(field(h,"requestFailure"));assertTrue(((TextView)field(h,"message")).getText().toString().startsWith("没有可显示"));assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());
            assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS,grid.getDescendantFocusability());assertTrue(emptyAction(h).hasFocus());assertEquals(View.VISIBLE,next(h).getVisibility());
        }
    }
    @Test public void endingWithoutVisibleCardsReturnsFooterFocusToReload()throws Exception{
        http(chain->{Request r=chain.request();return response(r,!r.url().encodedPath().equals("/bangumi/list") || "100".equals(r.url().queryParameter("skip"))?"[]":page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)){
            HomeActivity h=c.get();focusTracking(h);index(h);focusNext(h);press(h,KeyEvent.KEYCODE_DPAD_CENTER);await(()->settled(h));layout(h);
            assertTrue(pages(h).ended());assertEquals(View.GONE,next(h).getVisibility());assertEquals(View.VISIBLE,((View)field(h,"empty")).getVisibility());assertTrue(emptyAction(h).hasFocus());
        }
    }

    @Test public void reloadFocusMovesToVisibleCardsWhenAsyncPageFinishes()throws Exception{
        CountDownLatch release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
        http(chain->{Request r=chain.request();if(!r.url().encodedPath().equals("/bangumi/list"))return response(r,"[]",200);
            if("100".equals(r.url().queryParameter("skip"))){requests.incrementAndGet();block(release);return response(r,"{\"data\":[{\"id\":90,\"title\":\"可见番剧\"}],\"next\":-1}",200);}return response(r,page(100,true),200);});
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)){
            HomeActivity h=c.get();focusTracking(h);index(h);focusNext(h);press(h,KeyEvent.KEYCODE_DPAD_CENTER);await(()->requests.get()==1);
            press(h,KeyEvent.KEYCODE_DPAD_UP);assertTrue(emptyAction(h).hasFocus());release.countDown();await(()->settled(h));for(int n=0;n<6;n++)layout(h);
            RecyclerView grid=(RecyclerView)field(h,"grid");assertEquals(1,adapter(h).getItemCount());assertTrue(grid.findViewHolderForAdapterPosition(0).itemView.hasFocus());assertEquals(View.GONE,((View)field(h,"empty")).getVisibility());
        }finally{release.countDown();}
    }
    @Test public void footerVisibilityDoesNotChangeGridViewport()throws Exception{
        http(chain->response(chain.request(),"[]",200));
        try(ActivityController<HomeActivity> c=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity h=c.get();await(()->settled(h));layout(h);View grid=(View)field(h,"grid");int height=grid.getHeight(),top=grid.getTop();
            next(h).setVisibility(View.VISIBLE);layout(h);assertEquals(height,grid.getHeight());assertEquals(top,grid.getTop());assertTrue(next(h).getHeight()>0);
            next(h).setVisibility(View.GONE);layout(h);assertEquals(height,grid.getHeight());
        }
    }

}
