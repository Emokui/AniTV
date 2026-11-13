package tv.anitv;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;

/** Exercises the UI handoff through the original AniCh directory and title-playback compatibility path. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28, application=AniApp.class, qualifiers="w960dp-h540dp-land")
public class CatalogFlowTest {
    private final java.util.List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicReference<String> episodeRequest = new AtomicReference<>();
    @Before public void network() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        new Library(app).prefs.edit().clear().commit();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request(); requests.add(request); String path = request.url().encodedPath(); String body;
            if (path.equals("/bangumi/recommend")) body = "{\"data\":[{\"title\":\"热门\",\"list\":[{\"id\":42,\"title\":\"推荐示例\"}]}]}";
            else if (path.equals("/bangumi/list")) body = request.url().queryParameter("skip").equals("0") ? "{\"data\":[{\"id\":10380,\"title\":\"测试番剧\",\"image\":\"https://example.org/poster.jpg\"}]}" : "[]";
            else if (path.equals("/bangumi/search")) {
                String skip=request.url().queryParameter("skip");
                int id="0".equals(skip)?10381:"10381".equals(skip)?10380:0;
                org.json.JSONArray wire=new org.json.JSONArray();
                if(id>0)for(byte value:CatalogCodecTest.page(CatalogCodecTest.item(id,"测试搜索结果")))wire.put(value&255);
                body=wire.toString();
            }
            else if (path.equals("/bangumi/detail/10380")) body = "{\"id\":10380,\"title\":\"测试番剧\",\"overview\":\"真实字段的简介\"}";
            else if (path.equals("/vod/episode")) { episodeRequest.set(request.url().queryParameter("title")); body = "[\"第2集\",\"第1集\"]"; }
            else return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("unexpected route").body(ResponseBody.create("", MediaType.get("text/plain"))).build();
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        Field field = AniApp.class.getDeclaredField("http"); field.setAccessible(true); field.set(app, client);
    }
    @Test public void indexCardKeepsNativeIdThroughDetailAndExactNamedEpisodeReachesPlayer() throws Exception {
        Intent detailIntent;
        try (ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)) {
            HomeActivity activity = home.get();
            View root = activity.getWindow().getDecorView(); layout(root);
            await(() -> grid(root).getAdapter().getItemCount() == 1);
            View index = find(root, "索引"); assertNotNull(index); index.performClick();
            await(() -> grid(root).getAdapter().getItemCount() == 1 && grid(root).getAdapter().getItemId(0) == 10380);
            assertNotNull(find(root, "语言"));
            RecyclerView list = grid(root);
            PosterAdapter adapter = (PosterAdapter) list.getAdapter();
            PosterAdapter.Holder card = adapter.onCreateViewHolder(list, 0);
            adapter.onBindViewHolder(card, 0);
            assertTrue(card.itemView.performClick());
            detailIntent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(detailIntent); assertEquals(10380, detailIntent.getLongExtra("id", 0));
        }
        try (ActivityController<DetailActivity> detail = Robolectric.buildActivity(DetailActivity.class, detailIntent).setup().visible().windowFocusChanged(true)) {
            View root = detail.get().getWindow().getDecorView(); layout(root);
            assertNull(find(root,"打开原 AniTV 首页"));
            await(() -> find(root, "真实字段的简介") != null && find(root, "共 2 集") != null);
            assertEquals("测试番剧", episodeRequest.get());
            View play = find(root, "选集播放"); assertNotNull(play); play.performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog);
            RecyclerView chooser = dialog.getWindow().getDecorView().findViewWithTag("episode-grid");
            assertNotNull(chooser); assertEquals(2, chooser.getAdapter().getItemCount());
            layout(dialog.getWindow().getDecorView());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            RecyclerView.ViewHolder first = chooser.findViewHolderForAdapterPosition(0);
            assertNotNull(first);
            assertEquals("第1集", first.itemView.getContentDescription());
            first.itemView.performClick();
            Intent player = Shadows.shadowOf(detail.get()).getNextStartedActivity();
            assertNotNull(player); assertEquals(PlayerActivity.class.getName(), player.getComponent().getClassName());
            assertEquals("测试番剧", player.getStringExtra("title"));
            assertEquals(1, player.getIntExtra("episode", 0)); assertEquals("第1集", player.getStringExtra("episodeTitle"));
        }
    }
    @Test public void searchDialogUsesNativeSearchAndLastIdPagination() throws Exception {
        try(ActivityController<HomeActivity> controller=Robolectric.buildActivity(HomeActivity.class).setup().visible().windowFocusChanged(true)) {
            HomeActivity home=controller.get();View root=home.getWindow().getDecorView();layout(root);
            await(()->grid(root).getAdapter().getItemCount()==1);
            find(root,"搜索").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            android.widget.EditText input=findInput(dialog.getWindow().getDecorView());assertNotNull(input);input.setText("药屋 少女");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            await(()->grid(root).getAdapter().getItemCount()>0 && grid(root).getAdapter().getItemId(0)==10381);
            Field loading=HomeActivity.class.getDeclaredField("loading");loading.setAccessible(true);
            await(()->{try{return !(Boolean)loading.get(home);}catch(Exception e){throw new AssertionError(e);}});
            if(grid(root).getAdapter().getItemCount()==1) {
                java.lang.reflect.Method more=HomeActivity.class.getDeclaredMethod("more");more.setAccessible(true);more.invoke(home);
            }
            await(()->grid(root).getAdapter().getItemCount()==2);
            assertEquals(10380,grid(root).getAdapter().getItemId(1));
            assertTrue(requests.stream().anyMatch(r->r.url().encodedPath().equals("/bangumi/search") && "药屋 少女".equals(r.url().queryParameter("keyword")) && "10381".equals(r.url().queryParameter("skip"))));
            for(Request request:requests)assertFalse(request.url().host().equals("api.bgm.tv"));
        }
    }
    private android.widget.EditText findInput(View view) {
        if(view instanceof android.widget.EditText)return (android.widget.EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            android.widget.EditText input=findInput(((ViewGroup)view).getChildAt(i));if(input!=null)return input;
        }return null;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(10);
        }
        assertTrue("UI callback did not arrive", condition.getAsBoolean());
    }
    private void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 960, 540); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static RecyclerView grid(View view) {
        if (view instanceof RecyclerView) return (RecyclerView) view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            RecyclerView found=grid(((ViewGroup)view).getChildAt(i)); if(found!=null)return found;
        }
        return null;
    }
    private static View find(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()) ||
                (view instanceof TextView && text.contentEquals(((TextView)view).getText()))) return view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=find(((ViewGroup)view).getChildAt(i),text); if(found!=null)return found;
        }
        return null;
    }
}
