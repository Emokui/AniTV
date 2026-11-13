package tv.anitv;

import static org.junit.Assert.*;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class,qualifiers="w960dp-h540dp-land")
public class IndexFocusTest {
    @Test public void sidebarRightReachesFiltersWithoutMenuAndDownRestoresDeepPosition()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);RecyclerView grid=(RecyclerView)field(home,"grid");int position=(Integer)field(home,"focusedPosition");
            invoke(home,"rail");home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
            assertTrue(((View)field(home,"typeButton")).hasFocus());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));for(int i=0;i<4;i++)layout(home);
            assertEquals(position,field(home,"focusedPosition"));assertTrue(grid.findViewHolderForAdapterPosition(position).itemView.hasFocus());
        }
    }
    @Test public void processRecreationRestoresWindowShowAndPreviousCursors()throws Exception{
        android.os.Bundle state=new android.os.Bundle();long id,offset;
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);invoke(home,"more");await(()->settled(home));for(int i=0;i<5;i++)layout(home);
            int position=(Integer)field(home,"focusedPosition");RecyclerView grid=(RecyclerView)field(home,"grid");id=grid.getAdapter().getItemId(position);offset=((PageAccumulator)field(home,"pages")).offset();assertTrue(offset>0);
            controller.saveInstanceState(state);
        }
        android.os.Parcel parcel=android.os.Parcel.obtain();parcel.writeBundle(state);byte[] bytes=parcel.marshall();parcel.recycle();
        android.os.Parcel copy=android.os.Parcel.obtain();copy.unmarshall(bytes,0,bytes.length);copy.setDataPosition(0);android.os.Bundle restored=copy.readBundle(HomeActivity.class.getClassLoader());copy.recycle();
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).create(restored).start().resume().visible()){
            HomeActivity home=controller.get();for(int i=0;i<6;i++)layout(home);RecyclerView grid=(RecyclerView)field(home,"grid");
            assertEquals(offset,((PageAccumulator)field(home,"pages")).offset());assertTrue(((PageAccumulator)field(home,"pages")).hasPrevious());
            assertEquals(id,grid.getAdapter().getItemId((Integer)field(home,"focusedPosition")));assertTrue(grid.findViewHolderForAdapterPosition((Integer)field(home,"focusedPosition")).itemView.hasFocus());
        }
    }
    static class Queue implements Executor{final ConcurrentLinkedQueue<Runnable> tasks=new ConcurrentLinkedQueue<>();public void execute(Runnable task){tasks.add(task);}void commit(){Runnable task=tasks.poll();assertNotNull(task);task.run();}}
    private Object field(Object value,String name)throws Exception{Class<?> type=value.getClass();while(type!=null)try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException absent){type=type.getSuperclass();}throw new NoSuchFieldException(name);}
    private void invoke(Object value,String name)throws Exception{Method m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(value);}
    private void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+5_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}assertTrue(condition.getAsBoolean());}
    private boolean settled(HomeActivity home){try{return !(Boolean)field(home,"loading")&&!(Boolean)field(home,"listApplying");}catch(Exception e){throw new AssertionError(e);}}
    private void layout(HomeActivity home){View root=home.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));}
    @Before public void network()throws Exception{
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);http.set(RuntimeEnvironment.getApplication(),new OkHttpClient.Builder().addInterceptor(chain->{Request request=chain.request();String body="[]";
            if(request.url().encodedPath().equals("/bangumi/list")){
                long cursor=Long.parseLong(request.url().queryParameter("skip"));int page=cursor==0?0:(int)((10000-cursor+1)/40);
                org.json.JSONArray array=new org.json.JSONArray();try{for(Show show:CatalogWindowTest.page(page).shows)array.put(new org.json.JSONObject().put("id",show.id).put("title",show.title));}catch(org.json.JSONException e){throw new java.io.IOException(e);}body=array.toString();
            }
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    private HomeActivity prepare(ActivityController<HomeActivity> controller)throws Exception{
        HomeActivity home=controller.get();
        // Robolectric's Activity focus shadow otherwise retains the manually selected old holder.
        home.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->Shadows.shadowOf(home).setCurrentFocus(newFocus));
        ((List<View>)field(home,"rail")).get(1).performClick();await(()->settled(home));
        for(int i=0;i<2;i++){invoke(home,"more");await(()->settled(home));}
        RecyclerView grid=(RecyclerView)field(home,"grid");assertEquals(120,grid.getAdapter().getItemCount());
        grid.scrollToPosition(100);for(int i=0;i<4;i++)layout(home);
        RecyclerView.ViewHolder target=grid.findViewHolderForAdapterPosition(100);assertNotNull(target);target.itemView.requestFocus();Shadows.shadowOf(home).setCurrentFocus(target.itemView);
        assertEquals(100,field(home,"focusedPosition"));return home;
    }
    private Queue pauseCommits(HomeActivity home)throws Exception{
        Object differ=field(field(home,"adapter"),"mDiffer");Field executor=differ.getClass().getDeclaredField("mMainThreadExecutor");executor.setAccessible(true);Queue queue=new Queue();executor.set(differ,queue);return queue;
    }
    @Test public void directionDuringTrimAppliesToSameShowAfterDiffAndKeepsItsColumn()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);RecyclerView grid=(RecyclerView)field(home,"grid");Queue queue=pauseCommits(home);
            int span=((androidx.recyclerview.widget.GridLayoutManager)grid.getLayoutManager()).getSpanCount();assertEquals(6,span);
            invoke(home,"more");await(()->!queue.tasks.isEmpty());assertEquals(true,field(home,"listApplying"));
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));assertEquals(120,grid.getAdapter().getItemCount());
            queue.commit();for(int i=0;i<5;i++)layout(home);
            int expected=100-40+4+span;assertEquals(4,field(home,"leadingSlots"));assertEquals(124,grid.getAdapter().getItemCount());
            assertEquals(expected,field(home,"focusedPosition"));RecyclerView.ViewHolder target=grid.findViewHolderForAdapterPosition(expected);assertNotNull(target);assertTrue("focus="+grid.findFocus()+" pending="+field(home,"pendingFocus")+" pos="+field(home,"focusedPosition"),target.itemView.hasFocus());
            assertEquals(10000-100-span,target.getItemId());assertEquals(100%span,expected%span);
            PosterAdapter adapter=(PosterAdapter)grid.getAdapter();PosterAdapter.Holder spacer=adapter.onCreateViewHolder(grid,adapter.getItemViewType(0));
            assertFalse(spacer.itemView.isFocusable());assertFalse(spacer.itemView.performClick());assertNull(Shadows.shadowOf(home).getNextStartedActivity());
        }
    }
    @Test public void backToSidebarDuringDiffCannotBeOverriddenByItsLateCommit()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);Queue queue=pauseCommits(home);invoke(home,"more");await(()->!queue.tasks.isEmpty());
            home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));home.onBackPressed();
            View rail=((List<View>)field(home,"rail")).get(1);assertTrue(rail.hasFocus());queue.commit();for(int i=0;i<4;i++)layout(home);
            assertTrue(rail.hasFocus());assertEquals(false,field(home,"needsFocus"));assertEquals(false,field(home,"listApplying"));assertEquals(-1,field(home,"pendingFocus"));
        }
    }
    private void down(HomeActivity home,int repeat,long time){
        home.dispatchKeyEvent(new KeyEvent(1000,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN,repeat));
    }
    @Test public void heldDirectionDuringDiffDoesNotReplayMultipleRowsAtCommit()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);Queue queue=pauseCommits(home);
            invoke(home,"more");await(()->!queue.tasks.isEmpty());
            down(home,0,1000);for(int i=1;i<=7;i++)down(home,i,1000+i*100);
            queue.commit();for(int i=0;i<5;i++)layout(home);
            assertEquals("one requested row, without replaying held repeats",70,field(home,"focusedPosition"));
        }
    }
    @Test public void heldDirectionWaitsForOffscreenTargetToAttach()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);RecyclerView grid=(RecyclerView)field(home,"grid");
            grid.requestLayout();down(home,0,1000);
            assertEquals(106,field(home,"pendingFocus"));
            for(int i=1;i<=6;i++)down(home,i,1000+i*100);
            assertEquals("held repeats must wait for the requested card",106,field(home,"pendingFocus"));
            for(int i=0;i<5;i++)layout(home);
            assertEquals(106,field(home,"focusedPosition"));
            assertEquals(-1,field(home,"pendingFocus"));
            View target=grid.findViewHolderForAdapterPosition(106).itemView;assertTrue(target.hasFocus());
            Shadows.shadowOf(home).setCurrentFocus(target);
            down(home,7,1800);for(int i=0;i<5;i++)layout(home);
            int position=(Integer)field(home,"focusedPosition");
            assertEquals("holding continues by one row, even if prefetch trims the list",9888,grid.getAdapter().getItemId(position));
        }
    }
    @Test public void returnFromDetailUsesShowIdentityAfterBackgroundTrim()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);RecyclerView grid=(RecyclerView)field(home,"grid");
            home.clicked(((PosterAdapter)grid.getAdapter()).getCurrentList().get(100));controller.pause();
            invoke(home,"more");await(()->settled(home));for(int i=0;i<4;i++)layout(home);
            // A framework focus callback after the window changed must not replace the return anchor.
            home.focused(90);
            controller.resume();for(int i=0;i<5;i++)layout(home);
            assertEquals(64,field(home,"focusedPosition"));
            assertTrue(grid.findViewHolderForAdapterPosition(64).itemView.hasFocus());
            assertEquals(9900,grid.findViewHolderForAdapterPosition(64).getItemId());
        }
    }
    @Test public void menuJumpsToFiltersAndLateDiffCannotPullFocusBack()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);Queue queue=pauseCommits(home);
            invoke(home,"more");await(()->!queue.tasks.isEmpty());down(home,0,1000);
            assertTrue(home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU)));
            assertTrue(home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU)));
            View type=(View)field(home,"typeButton");assertTrue(type.hasFocus());
            queue.commit();for(int i=0;i<5;i++)layout(home);
            assertTrue(type.hasFocus());assertEquals(-1,field(home,"pendingFocus"));
            assertTrue(((java.util.ArrayDeque<?>)field(home,"queuedDirections")).isEmpty());
            assertEquals(64,field(home,"focusedPosition"));
        }
    }
    @Test public void returningToAlreadyFocusedCardDoesNotLeaveNavigationPending()throws Exception{
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=prepare(controller);RecyclerView grid=(RecyclerView)field(home,"grid");
            home.clicked(((PosterAdapter)grid.getAdapter()).getCurrentList().get(100));controller.pause().resume();
            for(int i=0;i<5;i++)layout(home);
            assertEquals(100,field(home,"focusedPosition"));assertEquals(-1,field(home,"pendingFocus"));
            down(home,1,2000);for(int i=0;i<5;i++)layout(home);
            assertEquals(106,field(home,"focusedPosition"));
            assertEquals("ordinary navigation must not fetch and trim another page",0,((PageAccumulator)field(home,"pages")).offset());
        }
    }
    @Test public void returningToHistoryFollowsWatchedShowAfterItMovesToTop()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();
        String endpoint=new Library(app).endpoint();
        Show watched=new Show(43,"第二部","https://example.org/43.jpg",""),other=new Show(42,"第一部","https://example.org/42.jpg","");
        history.remember(watched,endpoint,new String[]{watched.title});history.remember(other,endpoint,new String[]{other.title});
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=controller.get();((List<View>)field(home,"rail")).get(HomeActivity.HISTORY).performClick();
            RecyclerView grid=(RecyclerView)field(home,"grid");await(()->grid.getAdapter().getItemCount()==2);layout(home);
            home.clicked(watched);controller.pause();history.remember(watched,endpoint,new String[]{watched.title});
            controller.resume();await(()->grid.getAdapter().getItemId(0)==43);for(int i=0;i<5;i++)layout(home);
            int position=(Integer)field(home,"focusedPosition");assertEquals(43,grid.getAdapter().getItemId(position));
            assertTrue(grid.findViewHolderForAdapterPosition(position).itemView.hasFocus());
        }
    }
    @Test public void backDuringHistoryReturnDiffKeepsSidebarFocus()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);history.clear();
        String endpoint=new Library(app).endpoint();Show watched=new Show(43,"第二部","https://example.org/43.jpg","");
        history.remember(watched,endpoint,new String[]{watched.title});history.remember(new Show(42,"第一部","https://example.org/42.jpg",""),endpoint,new String[]{"第一部"});
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible()){
            HomeActivity home=controller.get();View rail=((List<View>)field(home,"rail")).get(HomeActivity.HISTORY);rail.performClick();
            RecyclerView grid=(RecyclerView)field(home,"grid");await(()->grid.getAdapter().getItemCount()==2);layout(home);
            grid.findViewHolderForAdapterPosition(1).itemView.requestFocus();home.clicked(watched);controller.pause();
            Queue queue=pauseCommits(home);history.remember(watched,endpoint,new String[]{watched.title});controller.resume();await(()->!queue.tasks.isEmpty());
            home.onBackPressed();assertTrue(rail.hasFocus());queue.commit();for(int i=0;i<5;i++)layout(home);
            assertTrue(rail.hasFocus());assertEquals(-1,field(home,"pendingFocus"));
        }
    }
}
