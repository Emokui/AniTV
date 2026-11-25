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
            assertArrayEquals(new String[]{"真实番剧42", "原名42"}, player.getStringArrayExtra("playbackTitles"));
        }
    }
    @Test public void placeholderDetailRecoversMetadataAndEpisodeSelectionKeepsSavedEpisode()throws Exception{
        AniApp app=(AniApp)RuntimeEnvironment.getApplication();PlaybackHistory history=new PlaybackHistory(app);String endpoint=new Library(app).endpoint();
        history.clear();history.remember(new Show(42,"历史番剧 #42","",""),endpoint,new String[0]);history.save("42",49,99000,1000000);
        java.util.concurrent.CountDownLatch detailReady = new java.util.concurrent.CountDownLatch(1);
        Field http=AniApp.class.getDeclaredField("http");http.setAccessible(true);
        http.set(app,new OkHttpClient.Builder().addInterceptor(chain->{Request request=chain.request();String body="[]";
            if (request.url().encodedPath().equals("/bangumi/detail/42")) {
                try {
                    if (!detailReady.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("等待初始空剧集超时");
                } catch (InterruptedException e) { throw new java.io.IOException(e); }
                body="{\"id\":42,\"title\":\"真实番剧42\",\"image\":\"https://example.org/42.jpg\",\"overview\":\"简介\"}";
            }
            else if(request.url().encodedPath().equals("/vod/episode") && "真实番剧42".equals(request.url().queryParameter("title")))body="[\"第48集\",\"第49集\"]";
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
        Intent detail=new Intent(app,DetailActivity.class).putExtra("id",42L).putExtra("title","历史番剧 #42");
        try(ActivityController<DetailActivity> controller=Robolectric.buildActivity(DetailActivity.class,detail).setup().visible()){
            DetailActivity activity=controller.get();
            await(() -> {
                try { return (boolean) field(activity, "episodesLoaded"); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            ((View)field(activity,"play")).performClick();
            android.app.AlertDialog empty = (android.app.AlertDialog) field(activity, "episodeDialog");
            assertTrue(empty.isShowing());
            detailReady.countDown();
            await(()->{try{return ((List<?>)field(activity,"episodes")).size()==2;}catch(Exception error){throw new AssertionError(error);}});
            assertEquals("真实番剧42",((Show)field(activity,"show")).title);assertEquals("https://example.org/42.jpg",((Show)field(activity,"show")).image);
            assertEquals("真实番剧42",history.find(42,endpoint).show.title);assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            assertFalse(empty.isShowing());
            android.app.AlertDialog dialog=(android.app.AlertDialog)field(activity,"episodeDialog");
            RecyclerView chooser=dialog.getWindow().getDecorView().findViewWithTag("episode-grid");assertEquals(1,field(chooser.getAdapter(),"selected"));
            View root=dialog.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);Shadows.shadowOf(Looper.getMainLooper()).idle();
            RecyclerView.ViewHolder holder=chooser.findViewHolderForAdapterPosition(1);assertNotNull(holder);holder.itemView.performClick();
            Intent player=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(player);assertEquals(49,player.getIntExtra("episode",0));assertEquals("真实番剧42",player.getStringExtra("title"));assertEquals("https://example.org/42.jpg",player.getStringExtra("image"));assertEquals(99000,history.position("42",49));
        } finally { detailReady.countDown(); }
    }

    @Test public void detailAndHistoryMetadataHonorOuterAndNestedErrorEnvelopes() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        String endpoint = new Library(app).endpoint();
        PlaybackHistory.Entry source = new PlaybackHistory.Entry(new Show(42, "历史番剧 #42", "", ""), endpoint, new String[0]);
        java.io.IOException outer = assertThrows(java.io.IOException.class,
                () -> HistoryMetadata.decode("{\"error\":true,\"message\":\"服务繁忙\",\"id\":42,\"title\":\"误读\"}".getBytes(StandardCharsets.UTF_8), source));
        assertEquals("服务繁忙", outer.getMessage());
        java.io.IOException inner = assertThrows(java.io.IOException.class,
                () -> HistoryMetadata.decode("{\"data\":{\"error\":401,\"message\":\"请先登录\",\"id\":42,\"title\":\"误读\"}}".getBytes(StandardCharsets.UTF_8), source));
        assertTrue(AccountSession.requiresLogin(inner.getMessage()));
        java.io.IOException doubleInner = assertThrows(java.io.IOException.class,
                () -> HistoryMetadata.decode("{\"error\":false,\"data\":{\"data\":{\"error\":401,\"message\":\"请先登录\"}}}".getBytes(StandardCharsets.UTF_8), source));
        assertTrue(AccountSession.requiresLogin(doubleInner.getMessage()));
        PlaybackHistory.Entry doubleValid = HistoryMetadata.decode(
                "{\"error\":false,\"data\":{\"error\":false,\"data\":{\"id\":42,\"title\":\"双层详情\",\"image\":\"\"}}}".getBytes(StandardCharsets.UTF_8), source);
        assertEquals("双层详情", doubleValid.show.title);
        assertTrue(doubleValid.hydrated);

        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            String body = path.equals("/bangumi/detail/42") ? "{\"data\":{\"error\":401,\"message\":\"unauthorized\"}}" : "[]";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build());
        Intent detail = new Intent(app, DetailActivity.class).putExtra("id", 42L).putExtra("title", "测试番剧");
        try (ActivityController<DetailActivity> controller = Robolectric.buildActivity(DetailActivity.class, detail).setup().visible()) {
            DetailActivity activity = controller.get();
            await(() -> {
                try { return AccountSession.LOGIN_REQUIRED.equals(field(activity, "detailFailure")); }
                catch (Exception e) { throw new AssertionError(e); }
            });
        }
    }

    @Test public void offlineFastFailureWaitsForCachedMetadataAndCoverlessShowsStopRefetching() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        PlaybackHistory history = new PlaybackHistory(app);
        history.clear();
        String endpoint = new Library(app).endpoint();
        history.remember(new Show(42, "历史番剧 #42", "", ""), endpoint, new String[0]);

        HttpUrl detailUrl = HttpUrl.get(endpoint).newBuilder()
                .addPathSegment("bangumi").addPathSegment("detail").addPathSegment("42").build();
        java.io.File detailsDir = new java.io.File(app.getCacheDir(), "details");
        detailsDir.mkdirs();
        java.nio.file.Files.write(
                new java.io.File(detailsDir, CatalogRepository.requestKey(new Request.Builder().url(detailUrl).build())).toPath(),
                "{\"id\":42,\"title\":\"无封面番剧\",\"image\":\"\",\"titles\":[\"Alias42\"]}".getBytes(StandardCharsets.UTF_8));

        java.util.concurrent.atomic.AtomicInteger networkCalls = new java.util.concurrent.atomic.AtomicInteger();
        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            networkCalls.incrementAndGet();
            throw new java.net.ConnectException("offline");
        }).build());

        java.util.concurrent.atomic.AtomicInteger reportedFailures = new java.util.concurrent.atomic.AtomicInteger(-1);
        try (HistoryMetadata resolver = new HistoryMetadata(app, history.entries(endpoint), new HistoryMetadata.Listener() {
            public void changed() {}
            public void complete(int failures) { reportedFailures.set(failures); }
        })) {
            resolver.start();
            await(() -> reportedFailures.get() >= 0);
            assertEquals(0, reportedFailures.get());
            assertEquals(1, networkCalls.get());
            PlaybackHistory.Entry hydrated = history.find(42, endpoint);
            assertNotNull(hydrated);
            assertEquals("无封面番剧", hydrated.show.title);
            assertEquals("", hydrated.show.image);
            assertFalse(PlaybackHistory.incomplete(hydrated));
        }

        // Playing another episode without cover keeps hydrated status and avoids redundant detail fetches.
        history.remember(new Show(42, "无封面番剧", "", "正篇"), endpoint, new String[]{"无封面番剧"});
        assertFalse(PlaybackHistory.incomplete(history.find(42, endpoint)));
        try (ActivityController<HomeActivity> controller = Robolectric.buildActivity(
                HomeActivity.class, new Intent(app, HomeActivity.class).putExtra("demo", true)).setup().visible()) {
            HomeActivity home = controller.get();
            ((List<View>) field(home, "rail")).get(HomeActivity.HISTORY).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(field(home, "historyMetadata"));
            assertEquals(1, networkCalls.get());
        }
    }

    @Test public void midFlightCloseAndRapidHistoryTabSwitchesSettleCleanly() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        PlaybackHistory history = new PlaybackHistory(app);
        history.clear();
        String endpoint = new Library(app).endpoint();
        history.remember(new Show(42, "历史番剧 #42", "", ""), endpoint, new String[0]);

        HttpUrl detailUrl = HttpUrl.get(endpoint).newBuilder()
                .addPathSegment("bangumi").addPathSegment("detail").addPathSegment("42").build();
        java.io.File detailsDir = new java.io.File(app.getCacheDir(), "details");
        detailsDir.mkdirs();
        java.nio.file.Files.write(
                new java.io.File(detailsDir, CatalogRepository.requestKey(new Request.Builder().url(detailUrl).build())).toPath(),
                "{\"id\":42,\"title\":\"快速切页番剧\",\"image\":\"https://example.org/42.jpg\"}".getBytes(StandardCharsets.UTF_8));

        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            try { gate.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{\"id\":42,\"title\":\"快速切页番剧\",\"image\":\"https://example.org/42.jpg\"}", MediaType.get("application/json"))).build();
        }).build());

        AtomicBoolean cancelledCallbackFired = new AtomicBoolean();
        HistoryMetadata cancelled = new HistoryMetadata(app, history.entries(endpoint), new HistoryMetadata.Listener() {
            public void changed() { cancelledCallbackFired.set(true); }
            public void complete(int failures) { cancelledCallbackFired.set(true); }
        });
        cancelled.start();
        cancelled.close();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(cancelledCallbackFired.get());

        try (ActivityController<HomeActivity> controller = Robolectric.buildActivity(
                HomeActivity.class, new Intent(app, HomeActivity.class).putExtra("demo", true)).setup().visible()) {
            HomeActivity home = controller.get();
            List<View> rail = (List<View>) field(home, "rail");
            rail.get(HomeActivity.HISTORY).performClick();
            rail.get(HomeActivity.FAVORITES).performClick();
            rail.get(HomeActivity.HISTORY).performClick();
            gate.countDown();
            await(() -> "快速切页番剧".equals(history.find(42, endpoint).show.title));
            RecyclerView grid = (RecyclerView) field(home, "grid");
            PosterAdapter adapter = (PosterAdapter) grid.getAdapter();
            await(() -> adapter.getItemCount() == 1 && "快速切页番剧".equals(adapter.getCurrentList().get(0).title));
        } finally {
            gate.countDown();
        }
    }

    @Test public void historyDetailPreservesShowSubtitleAndSyncsFavoritedPlaceholderOnHydration() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        Library library = new Library(app);
        library.prefs.edit().remove("favorites").commit();
        PlaybackHistory history = new PlaybackHistory(app);
        history.clear();
        String endpoint = library.endpoint();

        // 1. Opening Detail from History must pass entry.show.subtitle instead of formatted progress.
        history.remember(new Show(42, "葬送的芙莉莲", "https://example.org/42.jpg", "全 28 集"), endpoint, new String[]{"葬送的芙莉莲"});
        history.save("42", 7, 90000, 1440000);
        assertEquals("第 7 集 · 1:30", history.shows(endpoint).get(0).subtitle);
        try (ActivityController<HomeActivity> homeCtrl = Robolectric.buildActivity(
                HomeActivity.class, new Intent(app, HomeActivity.class).putExtra("demo", true)).setup().visible()) {
            HomeActivity home = homeCtrl.get();
            ((List<View>) field(home, "rail")).get(HomeActivity.HISTORY).performClick();
            RecyclerView grid = (RecyclerView) field(home, "grid");
            PosterAdapter adapter = (PosterAdapter) grid.getAdapter();
            await(() -> adapter.getItemCount() == 1);
            home.clicked(adapter.getCurrentList().get(0));
            Intent detailIntent = Shadows.shadowOf(home).getNextStartedActivity();
            assertNotNull(detailIntent);
            assertEquals("全 28 集", detailIntent.getStringExtra("subtitle"));

            Field http = AniApp.class.getDeclaredField("http");
            http.setAccessible(true);
            http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
                String path = chain.request().url().encodedPath();
                String body = path.equals("/bangumi/detail/42")
                        ? "{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"overview\":\"简介\"}"
                        : path.equals("/vod/episode") ? "[\"第7集\"]" : "[]";
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
            }).build());
            try (ActivityController<DetailActivity> detailCtrl = Robolectric.buildActivity(DetailActivity.class, detailIntent).setup().visible()) {
                DetailActivity detail = detailCtrl.get();
                ((View) field(detail, "favorite")).performClick();
                assertEquals("全 28 集", library.favorites().get(0).subtitle);
                await(() -> {
                    try { return ((List<?>) field(detail, "episodes")).size() == 1; }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                ((View) field(detail, "play")).performClick();
                android.app.AlertDialog picker = (android.app.AlertDialog) field(detail, "episodeDialog");
                View root = picker.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 960, 540);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                RecyclerView epGrid = root.findViewWithTag("episode-grid");
                epGrid.findViewHolderForAdapterPosition(0).itemView.performClick();
                Intent playerIntent = Shadows.shadowOf(detail).getNextStartedActivity();
                assertEquals("全 28 集", playerIntent.getStringExtra("subtitle"));
                try (ActivityController<PlayerActivity> playerCtrl = Robolectric.buildActivity(PlayerActivity.class, playerIntent).setup().visible()) {
                    java.lang.reflect.Method remember = PlayerActivity.class.getDeclaredMethod("rememberHistory");
                    remember.setAccessible(true);
                    remember.invoke(playerCtrl.get());
                }
                assertEquals("全 28 集", history.find(42, endpoint).show.subtitle);
            }
        }

        // 2. Favoriting an un-hydrated History placeholder before detail completes syncs back to favorites.
        history.remember(new Show(99, "历史番剧 #99", "", ""), endpoint, new String[0]);
        java.util.concurrent.CountDownLatch releaseDetail = new java.util.concurrent.CountDownLatch(1);
        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().equals("/bangumi/detail/99")) {
                try { releaseDetail.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{\"id\":99,\"title\":\"补全番剧99\",\"image\":\"https://example.org/99.jpg\",\"tagline\":\"2025 · TV\",\"nsfw\":true,\"overview\":\"简介\"}",
                                MediaType.get("application/json"))).build();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("[]", MediaType.get("application/json"))).build();
        }).build());
        Intent placeholderIntent = new Intent(app, DetailActivity.class).putExtra("id", 99L).putExtra("title", "历史番剧 #99");
        try (ActivityController<DetailActivity> detailCtrl = Robolectric.buildActivity(DetailActivity.class, placeholderIntent).setup().visible()) {
            DetailActivity detail = detailCtrl.get();
            ((View) field(detail, "favorite")).performClick();
            assertEquals("历史番剧 #99", library.favorites().get(0).title);
            releaseDetail.countDown();
            await(() -> "补全番剧99".equals(library.favorites().get(0).title));
            Show synced = library.favorites().get(0);
            assertEquals("https://example.org/99.jpg", synced.image);
            assertEquals("2025 · TV", synced.subtitle);
            assertTrue(synced.r18);
        } finally {
            releaseDetail.countDown();
        }
    }

    @Test public void cachedThenFreshDetailAndUnhydratedAliasFavoriteSyncCleanly() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        Library library = new Library(app);
        library.prefs.edit().remove("favorites").commit();
        PlaybackHistory history = new PlaybackHistory(app);
        history.clear();
        String endpoint = library.endpoint();

        // 1. Cached detail followed by fresh network detail updates empty-subtitle Show & recreated favorite.
        java.io.File detailCacheDir = new java.io.File(app.getCacheDir(), "details");
        okhttp3.HttpUrl endpointUrl = okhttp3.HttpUrl.get(endpoint);
        okhttp3.HttpUrl detailUrl = endpointUrl.newBuilder().addPathSegment("bangumi").addPathSegment("detail").addPathSegment("105").build();
        okhttp3.Request detailReq = new AccountSession(app).scopedRequest(detailUrl, endpointUrl);
        detailCacheDir.mkdirs();
        java.nio.file.Files.write(new java.io.File(detailCacheDir, CatalogRepository.requestKey(detailReq)).toPath(),
                "{\"id\":105,\"title\":\"缓存旧名\",\"image\":\"https://example.org/old.jpg\",\"tagline\":\"更新至 3 / 12 集\",\"overview\":\"旧\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        java.util.concurrent.CountDownLatch releaseFresh = new java.util.concurrent.CountDownLatch(1);
        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().equals("/bangumi/detail/105")) {
                try { releaseFresh.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{\"id\":105,\"title\":\"网络新名\",\"titles\":[\"简称105\"],\"image\":\"https://example.org/new.jpg\",\"tagline\":\"全 12 集\",\"nsfw\":true,\"overview\":\"新\"}",
                                MediaType.get("application/json"))).build();
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("[]", MediaType.get("application/json"))).build();
        }).build());

        Intent intent105 = new Intent(app, DetailActivity.class).putExtra("id", 105L).putExtra("title", "简称105");
        android.os.Bundle savedState = new android.os.Bundle();
        try (ActivityController<DetailActivity> c1 = Robolectric.buildActivity(DetailActivity.class, intent105).setup().visible()) {
            DetailActivity d1 = c1.get();
            ((View) field(d1, "favorite")).performClick();
            c1.saveInstanceState(savedState);
        }
        try (ActivityController<DetailActivity> c2 = Robolectric.buildActivity(DetailActivity.class, intent105).create(savedState).start().resume().visible()) {
            await(() -> "缓存旧名".equals(library.favorites().get(0).title));
            assertEquals("更新至 3 / 12 集", library.favorites().get(0).subtitle);
            releaseFresh.countDown();
            await(() -> "网络新名".equals(library.favorites().get(0).title) && "全 12 集".equals(library.favorites().get(0).subtitle));
            Show synced = library.favorites().get(0);
            assertEquals("https://example.org/new.jpg", synced.image);
            assertTrue(synced.r18);
            assertEquals("全 12 集", ((Show) field(c2.get(), "show")).subtitle);
        } finally {
            releaseFresh.countDown();
        }

        // 2. Un-hydrated alias favorite synced by background HistoryMetadata when canonical title differs.
        library.prefs.edit().remove("favorites").commit();
        history.clear();
        Show aliasShow = new Show(108, "某简称", "", "");
        history.remember(aliasShow, endpoint, new String[]{"某简称"});
        library.toggle(aliasShow);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("{\"id\":108,\"title\":\"某简称 完整版\",\"titles\":[\"某简称\"],\"image\":\"https://example.org/108.jpg\",\"tagline\":\"全 24 集\",\"nsfw\":true}",
                        MediaType.get("application/json"))).build()).build());
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        try (HistoryMetadata hm = new HistoryMetadata(app, history.entries(endpoint), new HistoryMetadata.Listener() {
            public void changed() {}
            public void complete(int failures) { done.set(true); }
        })) {
            hm.start();
            await(done::get);
        }
        Show fav108 = library.favorites().get(0);
        assertEquals("某简称 完整版", fav108.title);
        assertEquals("https://example.org/108.jpg", fav108.image);
        assertEquals("全 24 集", fav108.subtitle);
        assertTrue(fav108.r18);
    }

    @Test public void previouslyPersistedProgressSubtitlesSelfHealAcrossReadRememberAndHydration() throws Exception {
        AniApp app = (AniApp) RuntimeEnvironment.getApplication();
        Library library = new Library(app);
        PlaybackHistory history = new PlaybackHistory(app);
        history.clear();
        String endpoint = library.endpoint();

        // 1. Pattern matching: progress formats match while genuine catalog taglines and malformed times do not.
        for (String progress : new String[]{"第 1 集 · 0:00", "第 49 集 · 24:05", "特别篇 · 0:30", "OVA 01 · 125:59", "剧场版 · 前篇 · 9:08"}) {
            assertTrue(progress, PlaybackHistory.isProgressSubtitle(progress));
            assertEquals("", PlaybackHistory.cleanSubtitle(progress));
        }
        for (String genuine : new String[]{"", "全 28 集", "更新至 3 / 12 集", "更新至 5 集", "番剧", "2025 · TV",
                "2023-09-29 · 剧场版", "续集 · 第二季", "界面示例 · 本地卡片", "Re:Zero · 新编辑版", "86 · 第二期",
                " · 1:30", "1:30", "番剧 · 1:2", "番剧 · 1:234", "番剧 · :30", "番剧 · 1:2a", "番剧 · 1:60", "比例 · 16:90"}) {
            assertFalse(genuine, PlaybackHistory.isProgressSubtitle(genuine));
            assertEquals(genuine, PlaybackHistory.cleanSubtitle(genuine));
        }

        // 2. Self-healing on read: raw SharedPreferences polluted by an earlier build sanitize and rewrite prefs,
        // without touching genuine taglines or PlaybackHistory.shows() live progress cards.
        String rawHistory = "[{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"subtitle\":\"第 7 集 · 1:30\",\"endpoint\":\"" + endpoint + "\"},"
                + "{\"id\":43,\"title\":\"迷宫饭\",\"image\":\"https://example.org/43.jpg\",\"subtitle\":\"全 24 集\",\"endpoint\":\"" + endpoint + "\"}]";
        app.getSharedPreferences("native-history-list", 0).edit().putBoolean("migrated", true).putString("entries", rawHistory).commit();
        history.save("42", 7, 90000, 1440000);
        String rawFavorites = "[{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"subtitle\":\"OVA 01 · 0:30\",\"r18\":false},"
                + "{\"id\":43,\"title\":\"迷宫饭\",\"image\":\"https://example.org/43.jpg\",\"subtitle\":\"2024 · TV\",\"r18\":false}]";
        library.prefs.edit().putString("favorites", rawFavorites).commit();

        assertEquals("", history.find(42, endpoint).show.subtitle);
        assertEquals("全 24 集", history.find(43, endpoint).show.subtitle);
        assertEquals("第 7 集 · 1:30", history.shows(endpoint).get(0).subtitle);
        assertFalse(app.getSharedPreferences("native-history-list", 0).getString("entries", "").contains("第 7 集 · 1:30"));
        assertTrue(app.getSharedPreferences("native-history-list", 0).getString("entries", "").contains("全 24 集"));

        List<Show> favs = library.favorites();
        assertEquals("", favs.get(0).subtitle);
        assertEquals("2024 · TV", favs.get(1).subtitle);
        assertFalse(library.prefs.getString("favorites", "").contains("OVA 01 · 0:30"));
        assertTrue(library.prefs.getString("favorites", "").contains("2024 · TV"));

        // 3. Remember & toggle sanitize incoming progress strings while keeping genuine taglines.
        history.remember(new Show(43, "迷宫饭", "https://example.org/43.jpg", "第 8 集 · 2:10"), endpoint, new String[]{"迷宫饭"});
        assertEquals("全 24 集", history.find(43, endpoint).show.subtitle);
        library.toggle(new Show(44, "药屋少女的呢喃", "https://example.org/44.jpg", "特别篇 · 3:45"));
        assertEquals("", library.favorites().get(0).subtitle);

        // 4. Direct syncFavorite & HistoryMetadata hydration replace previously polluted subtitles with genuine taglines.
        library.prefs.edit().putString("favorites",
                "[{\"id\":77,\"title\":\"番剧77\",\"image\":\"https://example.org/77.jpg\",\"subtitle\":\"特别篇 · 0:45\",\"r18\":false},"
                + "{\"id\":78,\"title\":\"自定义标题\",\"image\":\"https://example.org/78.jpg\",\"subtitle\":\"第 2 集 · 1:05\",\"r18\":false}]").commit();
        assertFalse(library.syncFavorite(new Show(78, "完全不同标题", "https://example.org/78.jpg", "全 12 集"), false, "完全不同标题"));
        assertFalse(library.prefs.getString("favorites", "").contains("第 2 集 · 1:05"));
        assertEquals("自定义标题", library.favorites().get(1).title);
        assertEquals("", library.favorites().get(1).subtitle);

        app.getSharedPreferences("native-history-list", 0).edit().putBoolean("migrated", true).putString("entries",
                "[{\"id\":77,\"title\":\"历史番剧 #77\",\"image\":\"\",\"subtitle\":\"特别篇 · 0:45\",\"endpoint\":\"" + endpoint + "\"}]").commit();
        library.prefs.edit().putString("favorites",
                "[{\"id\":77,\"title\":\"番剧77\",\"image\":\"https://example.org/77.jpg\",\"subtitle\":\"特别篇 · 0:45\",\"r18\":false}]").commit();
        PlaybackHistory.Entry decodedFromPolluted = HistoryMetadata.decode(
                "{\"id\":77,\"title\":\"番剧77\",\"image\":\"https://example.org/77.jpg\",\"tagline\":\"2025 · TV\"}".getBytes(StandardCharsets.UTF_8),
                new PlaybackHistory.Entry(new Show(77, "番剧77", "https://example.org/77.jpg", "第 3 集 · 4:20"), endpoint, new String[]{"番剧77"}));
        assertEquals("2025 · TV", decodedFromPolluted.show.subtitle);

        Field http = AniApp.class.getDeclaredField("http");
        http.setAccessible(true);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            String body = path.equals("/bangumi/detail/77")
                    ? "{\"id\":77,\"title\":\"番剧77\",\"image\":\"https://example.org/77.jpg\",\"tagline\":\"2025 · TV\",\"overview\":\"简介\"}"
                    : path.equals("/bangumi/detail/42")
                    ? "{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"tagline\":\"全 28 集\",\"overview\":\"简介\"}"
                    : "[]";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build());
        AtomicBoolean done = new AtomicBoolean();
        try (HistoryMetadata hm = new HistoryMetadata(app, history.entries(endpoint), new HistoryMetadata.Listener() {
            public void changed() {}
            public void complete(int failures) { assertEquals(0, failures); done.set(true); }
        })) {
            hm.start();
            await(done::get);
        }
        assertEquals("2025 · TV", history.find(77, endpoint).show.subtitle);
        assertEquals("2025 · TV", library.favorites().get(0).subtitle);

        // 5. DetailActivity heals polluted history, polluted favorite, and even a stale polluted Intent extra.
        app.getSharedPreferences("native-history-list", 0).edit().putBoolean("migrated", true).putString("entries",
                "[{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"subtitle\":\"第 7 集 · 1:30\",\"endpoint\":\"" + endpoint + "\"}]").commit();
        library.prefs.edit().putString("favorites",
                "[{\"id\":42,\"title\":\"葬送的芙莉莲\",\"image\":\"https://example.org/42.jpg\",\"subtitle\":\"第 7 集 · 1:30\",\"r18\":false}]").commit();
        Intent pollutedDetail = new Intent(app, DetailActivity.class)
                .putExtra("id", 42L)
                .putExtra("title", "葬送的芙莉莲")
                .putExtra("image", "https://example.org/42.jpg")
                .putExtra("subtitle", "第 7 集 · 1:30");
        try (ActivityController<DetailActivity> detailCtrl = Robolectric.buildActivity(DetailActivity.class, pollutedDetail).setup().visible()) {
            DetailActivity detail = detailCtrl.get();
            assertNotEquals("第 7 集 · 1:30", ((Show) field(detail, "show")).subtitle);
            await(() -> "全 28 集".equals(library.favorites().get(0).subtitle));
            assertEquals("全 28 集", ((Show) field(detail, "show")).subtitle);
            assertEquals("全 28 集", history.find(42, endpoint).show.subtitle);
        }

        // 6. Two-stage cached -> fresh DetailActivity & HistoryMetadata on polluted favorites:
        //    - DetailActivity heals polluted subtitle across cache -> fresh without clobbering existing alias title/cover.
        //    - HistoryMetadata upgrades cached favorite hydration to fresh network metadata.
        java.io.File detailCacheDir = new java.io.File(app.getCacheDir(), "details");
        detailCacheDir.mkdirs();
        okhttp3.HttpUrl endpointUrl = okhttp3.HttpUrl.get(endpoint);
        okhttp3.Request req55 = new AccountSession(app).scopedRequest(
                endpointUrl.newBuilder().addPathSegment("bangumi").addPathSegment("detail").addPathSegment("55").build(), endpointUrl);
        java.nio.file.Files.write(new java.io.File(detailCacheDir, CatalogRepository.requestKey(req55)).toPath(),
                "{\"id\":55,\"title\":\"缓存译名55\",\"titles\":[\"旧译名55\"],\"image\":\"https://example.org/cache55.jpg\",\"tagline\":\"更新至 10 集\",\"overview\":\"旧\"}".getBytes(StandardCharsets.UTF_8));
        okhttp3.Request req56 = new AccountSession(app).scopedRequest(
                endpointUrl.newBuilder().addPathSegment("bangumi").addPathSegment("detail").addPathSegment("56").build(), endpointUrl);
        java.nio.file.Files.write(new java.io.File(detailCacheDir, CatalogRepository.requestKey(req56)).toPath(),
                "{\"id\":56,\"title\":\"缓存旧名56\",\"titles\":[\"简称56\"],\"image\":\"https://example.org/cache56.jpg\",\"tagline\":\"更新至 3 集\"}".getBytes(StandardCharsets.UTF_8));

        java.util.concurrent.CountDownLatch releaseFresh55 = new java.util.concurrent.CountDownLatch(1);
        http.set(app, new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            String body = "[]";
            if (path.equals("/bangumi/detail/55")) {
                try { releaseFresh55.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                body = "{\"id\":55,\"title\":\"网络新译名55\",\"titles\":[\"旧译名55\"],\"image\":\"https://example.org/fresh55.jpg\",\"tagline\":\"全 28 集\",\"overview\":\"新\"}";
            } else if (path.equals("/bangumi/detail/56")) {
                body = "{\"id\":56,\"title\":\"网络新名56\",\"titles\":[\"简称56\"],\"image\":\"https://example.org/fresh56.jpg\",\"tagline\":\"全 24 集\"}";
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build());

        library.prefs.edit().putString("favorites",
                "[{\"id\":55,\"title\":\"旧译名55\",\"image\":\"https://example.org/custom55.jpg\",\"subtitle\":\"第 7 集 · 1:30\",\"r18\":false}]").commit();
        Intent detail55 = new Intent(app, DetailActivity.class)
                .putExtra("id", 55L).putExtra("title", "旧译名55")
                .putExtra("image", "https://example.org/custom55.jpg").putExtra("subtitle", "第 7 集 · 1:30");
        try (ActivityController<DetailActivity> c55 = Robolectric.buildActivity(DetailActivity.class, detail55).setup().visible()) {
            await(() -> "更新至 10 集".equals(library.favorites().get(0).subtitle));
            assertEquals("旧译名55", library.favorites().get(0).title);
            assertEquals("https://example.org/custom55.jpg", library.favorites().get(0).image);
            releaseFresh55.countDown();
            await(() -> "全 28 集".equals(library.favorites().get(0).subtitle));
            Show healed55 = library.favorites().get(0);
            assertEquals("旧译名55", healed55.title);
            assertEquals("https://example.org/custom55.jpg", healed55.image);
            assertEquals("全 28 集", healed55.subtitle);
        } finally {
            releaseFresh55.countDown();
        }

        app.getSharedPreferences("native-history-list", 0).edit().putBoolean("migrated", true).putString("entries",
                "[{\"id\":56,\"title\":\"简称56\",\"image\":\"\",\"subtitle\":\"第 1 集 · 0:20\",\"endpoint\":\"" + endpoint + "\"}]").commit();
        library.prefs.edit().putString("favorites",
                "[{\"id\":56,\"title\":\"简称56\",\"image\":\"\",\"subtitle\":\"第 1 集 · 0:20\",\"r18\":false}]").commit();
        AtomicBoolean done56 = new AtomicBoolean();
        try (HistoryMetadata hm56 = new HistoryMetadata(app, history.entries(endpoint), new HistoryMetadata.Listener() {
            public void changed() {}
            public void complete(int failures) { assertEquals(0, failures); done56.set(true); }
        })) {
            hm56.start();
            await(done56::get);
        }
        Show fav56 = library.favorites().get(0);
        assertEquals("网络新名56", fav56.title);
        assertEquals("https://example.org/fresh56.jpg", fav56.image);
        assertEquals("全 24 集", fav56.subtitle);
        assertEquals("全 24 集", history.find(56, endpoint).show.subtitle);
    }

}
