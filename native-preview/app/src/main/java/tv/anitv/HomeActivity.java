package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import okhttp3.HttpUrl;

public final class HomeActivity extends Activity implements PosterAdapter.Events {
    static final int HISTORY=2, FAVORITES=3, SEARCH=4, SETTINGS=5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PageAccumulator pages = new PageAccumulator();
    private final List<View> rail = new ArrayList<>();
    private final List<View> emptyActions = new ArrayList<>();
    private RecyclerView grid;
    private GridLayoutManager layout;
    private PosterAdapter adapter;
    private CatalogRepository repository;
    private CatalogRepository.Job job;
    private Library library;
    private TextView heading, caption, clock, footer, retryMore;
    private LinearLayout filters, empty;
    private TextView typeButton, langButton, yearButton, message;
    private ProgressBar spinner;
    private AlertDialog activeDialog;
    private int settingsPosition;private boolean returnToSettings;
    private int section, generation, requestSequence, focusedPosition, pendingFocus = -1, lastFilter;
    private boolean historySelection;private final java.util.Set<Long> selectedHistory=new java.util.HashSet<>();private LinearLayout historyActions;private TextView deleteSelected;
    private boolean demo, loading, destroyed, needsFocus, returningFromDetail, resumed;
    private String keyword = "", type = "", lang = "", year = "";
    private long lastBack, returningShowId;
    private long lastDirectionTime;
    private View refreshTarget;
    private boolean refreshHeld;
    private CharSequence refreshFooter;
    private CacheManager cacheManager;
    private final Runnable refreshHold=()->{
        if(refreshTarget==null || !refreshTarget.hasFocus() || !resumed)return;
        refreshHeld=true;
        cacheManager.clearEpisodes();
        int target=rail.indexOf(refreshTarget);
        if(target!=section){focusedPosition=0;select(target,false,true);rail();}
        else{rail();reload(true);}
    };
    private String requestFailure, queryKey;
    private boolean forceRefresh,loadingFirst;
    private boolean listApplying,listRestoreFocus;
    private int listRevision,focusEpoch,leadingSlots;
    private long listAnchor=-1;
    private int listAnchorTop, returningTop;
    private final java.util.ArrayDeque<Integer> queuedDirections=new java.util.ArrayDeque<>();
    private HistoryMetadata historyMetadata;
    private int historyFailures;
    private View historyPressTarget;
    private long historyPressId;
    private boolean historyConsumeRelease;
    private final Runnable historyHold=()->{
        View target=historyPressTarget;long id=historyPressId;
        cancelHistoryPress();
        if(destroyed || section!=HISTORY || activeDialog!=null || target==null || !target.hasFocus())return;
        PlaybackHistory.Entry entry=new PlaybackHistory(this).find(id,library.endpoint());
        if(entry!=null){historyConsumeRelease=true;deleteHistory(entry.show);}
    };
    private final Runnable historyUpdate=()->{
        if(destroyed || section!=HISTORY)return;
        List<Show> shows=library.visible(new PlaybackHistory(this).shows(library.endpoint()));
        adapter.submitList(shows,()->{if(!destroyed && section==HISTORY)restoreFocus();});
        footer.setText(shows.size()+" 部 · "+(historyMetadata!=null && historyMetadata.running()?"正在补全标题与封面…":
                historyFailures>0?"部分信息读取失败 · 侧栏确认重试":"确认查看 / 菜单批量选择 / 长按删除"));
    };
    private long accountRevision;
    private boolean accountAvailable;
    private Parcelable restoredScroll;
    private PageAccumulator.State restoredPages;private String restoredQuery;private long restoredShowId;private boolean restoredGridFocus;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            main.postDelayed(this, 30_000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui.fullscreen(this);
        library = new Library(this);
        repository = new CatalogRepository(((AniApp) getApplication()).http(), new File(getCacheDir(), "catalog"));
        demo = BuildConfig.DEBUG && getIntent().getBooleanExtra("demo", false);
        section = demo ? 1 : 0;
        if (state != null) {
            section = state.getInt("section"); demo = BuildConfig.DEBUG && state.getBoolean("demo");
            keyword = state.getString("keyword", ""); type = state.getString("type", "");
            lang = state.getString("lang", ""); year = state.getString("year", "");
            focusedPosition = state.getInt("focused"); restoredScroll = state.getParcelable("scroll");
            returnToSettings=state.getBoolean("settings-return");settingsPosition=state.getInt("settings-position");
        }
        if ("日本".equals(lang)) lang="ja";
        else if ("中国".equals(lang)) lang="zh";
        else if ("欧美".equals(lang)) lang="en";
        else if ("韩国".equals(lang)) lang="ko";
        AccountSession account=new AccountSession(this);accountRevision=account.revision();accountAvailable=account.available(HttpUrl.get(library.endpoint()));
        cacheManager = new CacheManager(this);
        if(state!=null && library.endpoint().equals(state.getString("catalog-endpoint")) && accountRevision==state.getLong("catalog-account",-1) && System.currentTimeMillis()-state.getLong("catalog-at",0)<12L*60*60*1000){
            restoredPages=(PageAccumulator.State)state.getSerializable("catalog-window");restoredQuery=state.getString("catalog-query");restoredShowId=state.getLong("catalog-focus-id",-1);restoredGridFocus=state.getBoolean("catalog-grid-focus");
        }
        build();
        if (!type.isEmpty()) typeButton.setText("tv".equals(type)?"正篇":"movie".equals(type)?"剧场版":"特别篇");
        if (!lang.isEmpty()) langButton.setText("ja".equals(lang)?"日语":"zh".equals(lang)?"国语":"en".equals(lang)?"英语":"ko".equals(lang)?"韩语":"其他");
        if (!year.isEmpty()) yearButton.setText(year);
        select(section, false);
    }
    private void build() {
        LinearLayout root = new LinearLayout(this); root.setBackgroundColor(Ui.BG); root.setFitsSystemWindows(true);
        root.setClipChildren(false); setContentView(root);
        LinearLayout sidebar = new LinearLayout(this); sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_HORIZONTAL); sidebar.setBackgroundColor(0xff17171a);
        android.graphics.drawable.LayerDrawable sideBg=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(0xff17171a),new android.graphics.drawable.ColorDrawable(0x14ffffff)});
        sideBg.setLayerWidth(1,dp(1));sideBg.setLayerGravity(1,Gravity.RIGHT);sidebar.setBackground(sideBg);
        sidebar.setPadding(dp(7), dp(18), dp(7), dp(12)); root.addView(sidebar, new LinearLayout.LayoutParams(dp(78), -1));
        TextView brand = Ui.text(this, "AniTV", 17, Ui.TEXT); Ui.bold(brand); brand.setGravity(Gravity.CENTER);
        sidebar.addView(brand, new LinearLayout.LayoutParams(-1, dp(42)));
        String[] labels = {"推荐", "索引", "历史", "收藏", "搜索", "设置"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout button = new LinearLayout(this); button.setOrientation(LinearLayout.VERTICAL);
            button.setGravity(Gravity.CENTER); button.setFocusable(true); button.setClickable(true);
            button.setFocusableInTouchMode(true);
            button.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            button.setId(View.generateViewId()); button.setBackground(Ui.railBackground(this));
            RailIcon icon = new RailIcon(this, i); button.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView label = Ui.text(this, labels[i], 12, Ui.MUTED); label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(dp(18),Math.round(label.getTextSize()*1.35f))); lp.topMargin = dp(5); button.addView(label, lp);
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(55)); row.topMargin = dp(3); sidebar.addView(button, row);
            button.setContentDescription(labels[i]);
            button.setOnClickListener(v -> {
                if (index == SETTINGS) settings();
                else if (index == SEARCH) search();
                else if (index == section) {
                    if(index==0 || index==1)return;
                    focusedPosition = 0; needsFocus = false; restoredScroll = null;
                    rail(); reload(true);
                } else {
                    focusedPosition = 0; restoredScroll = null;
                    select(index, false); rail();
                }
            });
            rail.add(button);
        }
        View spacer = new View(this); sidebar.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView badge = Ui.text(this, BuildConfig.UI_BADGE, 10, Ui.MUTED); sidebar.addView(badge);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(12), dp(18), dp(4)); body.setClipChildren(false);
        root.addView(body, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        heading = Ui.text(this, "", 22, Ui.TEXT); Ui.bold(heading); titles.addView(heading);
        caption = Ui.text(this, "", 12, Ui.MUTED);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(4); titles.addView(caption, cp);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        clock = Ui.text(this, "", 17, Ui.MUTED); header.addView(clock); body.addView(header);
        filters = new LinearLayout(this); filters.setGravity(Gravity.CENTER_VERTICAL); filters.setBackgroundColor(Ui.BG);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(43)); fp.topMargin = dp(6); body.addView(filters, fp);
        typeButton = chip("类型", v -> choose("类型", new String[]{"全部", "正篇", "剧场版", "特别篇"}, new String[]{"", "tv", "movie", "ova"}, 0));
        langButton = chip("语言", v -> choose("语言", new String[]{"全部", "日语", "国语", "英语", "韩语", "其他"}, new String[]{"", "ja", "zh", "en", "ko", "other"}, 1));
        yearButton = chip("年份", v -> years());
        FrameLayout content = new FrameLayout(this);
        // Keep recycled/animated cards inside this viewport, below the fixed filters.
        content.setClipChildren(true); content.setClipToPadding(true);
        body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new RecyclerView(this); grid.setId(View.generateViewId()); grid.setClipToPadding(false); grid.setClipChildren(false);
        grid.setPadding(dp(2), dp(6), dp(2), dp(4)); grid.setHasFixedSize(true); grid.setItemAnimator(null);
        grid.setItemViewCacheSize(8); grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Home owns focus by show ID; RecyclerView must not restore a second target after trimming.
        grid.setPreserveFocusAfterLayout(false);grid.setFocusable(true);
        int available = getResources().getDisplayMetrics().widthPixels - dp(126);
        int span = Math.max(4, Math.min(7, available / dp(135)));
        layout = new GridLayoutManager(this, span); layout.setInitialPrefetchItemCount(span);
        grid.setLayoutManager(layout); adapter = new PosterAdapter(this, this); adapter.size(available / span - dp(10));
        grid.setAdapter(adapter); content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView view, int state) { adapter.pauseImages(state != RecyclerView.SCROLL_STATE_IDLE); }
            @Override public void onScrolled(RecyclerView view, int x, int y) {
                if (view.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE && y > 0 && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - span * 2) more();
            }
        });
        empty = new LinearLayout(this); empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(10), dp(20), dp(10));
        spinner = new ProgressBar(this); empty.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        message = Ui.text(this, "", 14, Ui.MUTED); message.setGravity(Gravity.CENTER); message.setLineSpacing(dp(5), 1);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = dp(18); empty.addView(message, mp);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = dp(20); empty.addView(actions, ap);
        TextView retry = Ui.button(this, "重新加载", v -> reload(true)); actions.addView(retry); emptyActions.add(retry);
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL);
        footer = Ui.text(this, "", 11, Ui.MUTED);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); footer.setMaxLines(1);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.addView(footer, new LinearLayout.LayoutParams(0, dp(18), 1));
        retryMore = Ui.button(this, "重试下一页", v -> load(false)); retryMore.setVisibility(View.GONE);
        retryMore.setMinHeight(0);retryMore.setMinimumHeight(0);retryMore.setTextSize(12);
        retryMore.setPadding(dp(12),0,dp(12),0);
        bottom.addView(retryMore,new LinearLayout.LayoutParams(-2,-1));
        historyActions=new LinearLayout(this);historyActions.setVisibility(View.GONE);
        TextView all=Ui.button(this,"全选",v->{for(Show show:adapter.getCurrentList())if(show.id>0)selectedHistory.add(show.id);updateHistorySelection();});
        deleteSelected=Ui.button(this,"删除已选",v->confirmHistorySelection());TextView cancel=Ui.button(this,"取消",v->endHistorySelection());
        for(TextView button:new TextView[]{all,deleteSelected,cancel}){button.setMinHeight(0);button.setPadding(dp(8),0,dp(8),0);button.setTextSize(12);historyActions.addView(button,new LinearLayout.LayoutParams(-2,-1));}
        bottom.addView(historyActions,new LinearLayout.LayoutParams(-2,-1));
        body.addView(bottom,new LinearLayout.LayoutParams(-1,dp(28)));
        rail.get(section).requestFocus();
    }
    private TextView chip(String text, View.OnClickListener action) {
        TextView v = Ui.button(this, text, action);v.setBackground(Ui.emphasis(this,18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(35)); lp.rightMargin = dp(8); filters.addView(v, lp); return v;
    }
    private int dp(float v) { return Ui.dp(this, v); }
    private void select(int index, boolean focusGrid) {
        select(index,focusGrid,false);
    }
    private void select(int index, boolean focusGrid,boolean force) {
        typeButton.setSelected(!type.isEmpty());langButton.setSelected(!lang.isEmpty());yearButton.setSelected(!year.isEmpty());
        if(index!=HISTORY && historySelection)endHistorySelection();
        section = index; focusedPosition = focusGrid ? 0 : focusedPosition; needsFocus = focusGrid;
        for (int i = 0; i < rail.size(); i++) {
            LinearLayout row = (LinearLayout) rail.get(i); row.setSelected(i == section);
            row.getChildAt(0).setSelected(i == section);
            ((TextView) row.getChildAt(1)).setTextColor(i == section ? Ui.ACCENT : Ui.MUTED);
        }
        heading.setText(index == 0 ? "为你推荐" : index == 1 ? "番剧索引" : index == HISTORY ? "观看历史" : index == FAVORITES ? "我的收藏" : "搜索 · " + keyword);
        caption.setText(demo ? "界面演示 · 本地示例，不是在线番剧" : index == HISTORY ? "最近观看 · 菜单批量选择 / 长按确认删除" : index == FAVORITES ? "保存在本机的收藏" : (index == 1 ? "类型 / 语言 / 年份 · 侧栏右键或菜单键返回筛选" : index == SEARCH ? "AniCh 搜索结果" : "发现故事，轻松浏览"));
        caption.setVisibility(demo || index == 1 || index == HISTORY || index == FAVORITES || index == SEARCH ? View.VISIBLE : View.GONE);
        filters.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        typeButton.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        langButton.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        yearButton.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        reload(force);
    }
    private void reload() { reload(false); }
    private void reload(boolean force) {
        String nextKey=demo || isLocalSection() ? section+":"+keyword+":"+type+":"+lang+":"+year : CatalogRepository.requestKey(new okhttp3.Request.Builder().url(url(0)).build());
        if(restoredPages!=null){
            PageAccumulator.State saved=restoredPages;restoredPages=null;
            if(!force && nextKey.equals(restoredQuery) && !isLocalSection()){
                pages.restore(saved);queryKey=nextKey;List<Show> shows=displayPages();leadingSlots=shows.isEmpty()?0:(int)(pages.offset(library.showR18())%layout.getSpanCount());
                int mapped=indexOf(shows,restoredShowId);focusedPosition=mapped>=0?mapped:Math.max(leadingSlots,Math.min(focusedPosition,shows.size()-1));
                needsFocus=restoredGridFocus;empty.setVisibility(shows.isEmpty()?View.VISIBLE:View.GONE);spinner.setVisibility(View.GONE);footer.setText(library.visible(pages.snapshot()).size()+" 部 · 已恢复浏览位置");
                message.setText("没有可显示的番剧\n可以修改筛选条件或内容显示设置");
                updateFilteredPageAction(shows);
                adapter.submitList(shows,()->{if(!restoredGridFocus && restoredScroll!=null){layout.onRestoreInstanceState(restoredScroll);restoredScroll=null;}restoreFocus();});return;
            }
        }
        if(force && forceRefresh && loading && loadingFirst && nextKey.equals(queryKey)){
            Toast.makeText(this,"正在读取当前页面，请稍候",Toast.LENGTH_SHORT).show();return;
        }
        listRevision++;listApplying=false;listRestoreFocus=false;queuedDirections.clear();leadingSlots=0;focusEpoch++;
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cancelHistoryPress();stopHistoryMetadata();
        if (job != null) job.cancel(); generation++; requestSequence++; loading = false;forceRefresh=force;
        for (View action : emptyActions) action.setVisibility(isLocalSection() ? View.GONE : View.VISIBLE);
        boolean preserve=nextKey.equals(queryKey) && !pages.snapshot().isEmpty();queryKey=nextKey;
        boolean hasVisibleData=preserve && !library.visible(pages.snapshot()).isEmpty();
        if(!preserve){pages.reset();adapter.submitList(new ArrayList<>());}
        pages.windowed(section==1 || section==SEARCH);
        pendingFocus = -1; requestFailure = null; retryMore.setVisibility(View.GONE);
        if (section == HISTORY) { local(new PlaybackHistory(this).shows(library.endpoint()), "暂无观看历史\n播放番剧后会自动记录，可在这里继续观看"); startHistoryMetadata(); return; }
        if (section == FAVORITES) { local(library.favorites(), "暂无收藏\n在番剧详情中按「收藏」添加"); return; }
        if (demo) {
            List<Show> shows = Library.demo();
            if (!keyword.isEmpty() && section == SEARCH) shows.removeIf(s -> !s.title.contains(keyword));
            local(shows, "没有匹配的界面示例"); return;
        }
        empty.setVisibility(hasVisibleData?View.GONE:View.VISIBLE); spinner.setVisibility(View.VISIBLE); message.setText("正在加载番剧…");
        footer.setText(hasVisibleData?"正在刷新 · 保留当前列表":"网络请求有超时限制，失败后可重试"); load(true);
    }
    private void stopHistoryMetadata(){
        if(historyMetadata!=null){historyMetadata.close();historyMetadata=null;}
        main.removeCallbacks(historyUpdate);
    }
    private void startHistoryMetadata(){
        stopHistoryMetadata();historyFailures=0;
        List<PlaybackHistory.Entry> entries=new PlaybackHistory(this).entries(library.endpoint());
        boolean missing=false;for(PlaybackHistory.Entry entry:entries)if(PlaybackHistory.incomplete(entry)){missing=true;break;}
        if(!missing)return;
        final int token=generation;
        historyMetadata=new HistoryMetadata(this,entries,new HistoryMetadata.Listener(){
            public void changed(){if(!destroyed && section==HISTORY && token==generation){main.removeCallbacks(historyUpdate);main.postDelayed(historyUpdate,150);}}
            public void complete(int failures){if(!destroyed && section==HISTORY && token==generation){historyFailures=failures;main.removeCallbacks(historyUpdate);main.post(historyUpdate);}}
        });
        footer.setText("正在补全历史标题与封面…");historyMetadata.start();
    }
    private void local(List<Show> shows, String emptyText) {
        shows=library.visible(shows);
        leadingSlots=0;
        loading = false; spinner.setVisibility(View.GONE); message.setText(emptyText);
        empty.setVisibility(shows.isEmpty() ? View.VISIBLE : View.GONE);
        footer.setText(shows.size() + (section==HISTORY?" 部 · 菜单批量选择 / 长按确认删除":" 部 · 方向键浏览 / 确认查看 / 返回侧栏"));
        adapter.submitList(shows, () -> restoreFocus());
    }
    private HttpUrl url(long cursor) {
        return CatalogApi.url(HttpUrl.get(library.endpoint()), section==SEARCH?3:section, cursor, keyword, type, lang, year,library.showR18());
    }
    private void load(boolean first) {
        if (loading || demo || isLocalSection() || destroyed) return;
        loading = true;loadingFirst=first;
        if(!first && retryMore.getVisibility()==View.VISIBLE)retryMore.setText("正在加载…");
        else retryMore.setVisibility(View.GONE);
        requestFailure = null;
        final int token = generation, requestId=++requestSequence; final boolean[] freshApplied={false}; final long cursor = first ? 0 : pages.cursor();
        okhttp3.Request.Builder builder=new okhttp3.Request.Builder().url(url(cursor));
        if(first && forceRefresh)builder.header("Cache-Control","no-cache");
        final okhttp3.Request request=builder.build();
        footer.setText(first ? "正在连接番剧接口…" : "正在加载下一页…");
        final boolean reuseCache=first && !forceRefresh && (section==0 || section==1);
        job = repository.request(request, first && !forceRefresh, !reuseCache, new CatalogRepository.Listener() {
            @Override public void loaded(byte[] bytes, boolean cached) {
                if (!activeRequest(token,requestId)) return;
                repository.decode(() -> {
                    try {
                        CatalogCodec.Page page = CatalogApi.decode(bytes);
                        main.post(() -> {
                            if (!activeRequest(token,requestId) || cached && freshApplied[0]) return;
                            if(!cached){freshApplied[0]=true;repository.save(request,bytes);}
                            if (!cached || reuseCache) loading = false;
                            if (first) pages.reset(); pages.append(page, section != 0);
                            List<Show> shows = displayPages();
                            spinner.setVisibility(View.GONE); message.setText("没有可显示的番剧\n可以修改筛选条件或内容显示设置");
                            footer.setText(library.visible(pages.snapshot()).size() + (pages.hasPrevious()?" 部 · 上键回看前段":" 部") + (cached ? loading ? " · 本地缓存，正在更新…" : requestFailure==null?" · 本地缓存":" · 缓存数据 · " + requestFailure : pages.ended() ? " · 当前结果已加载完" : " · 下移继续加载"));
                            submitPages(shows,token,requestId,false);
                            empty.setVisibility(shows.isEmpty() ? View.VISIBLE : View.GONE);
                            updateFilteredPageAction(shows);
                        });
                    } catch (IOException e) {
                        if(cached && reuseCache)main.post(()->{
                            if(!activeRequest(token,requestId))return;
                            loading=false;forceRefresh=true;load(true);
                        });
                        else if (!cached) main.post(() -> failure(token, requestId, e.getMessage(), first));
                    }
                });
            }
            @Override public void failed(String error) { failure(token, requestId, error, first); }
        });
    }
    private boolean activeRequest(int token,int requestId){return !destroyed && token==generation && requestId==requestSequence;}
    private void failure(int token, int requestId, String error, boolean first) {
        if (!activeRequest(token,requestId)) return;
        loading = false; spinner.setVisibility(View.GONE);
        if (error != null && error.toLowerCase(Locale.ROOT).contains("unauthorized"))
            error = "目录接口需要有效登录，请到设置 → 账号管理登录或重新登录";
        requestFailure = error;
        boolean hasData = !library.visible(pages.snapshot()).isEmpty();
        empty.setVisibility(hasData ? View.GONE : View.VISIBLE);
        message.setText("暂时无法加载番剧\n" + error + "\n可在设置中检查接口地址");
        footer.setText((hasData ? "已保留当前列表 · " : "") + error);
        retryMore.setText("重试下一页");
        boolean pageButtonFocused=retryMore.hasFocus();
        retryMore.setVisibility(!first && !pages.ended() ? View.VISIBLE : View.GONE);
        if(pageButtonFocused && retryMore.getVisibility()!=View.VISIBLE && !hasData && resumed)focusEmptyAction();
    }
    private boolean isLocalSection(){return section==FAVORITES || section==HISTORY;}
    private static int indexOf(List<Show> shows,long id){for(int i=0;i<shows.size();i++)if(shows.get(i).id==id)return i;return -1;}
    private void loadPrevious() {
        loadPrevious(true);
    }
    private void loadPrevious(boolean allowCache) {
        if(loading || listApplying || !pages.hasPrevious() || destroyed)return;
        loading=true;loadingFirst=false;requestFailure=null;retryMore.setVisibility(View.GONE);final int token=generation,requestId=++requestSequence;
        final long cursor=pages.previousCursor();
        footer.setText("正在读取上一段…");
        job=repository.request(new okhttp3.Request.Builder().url(url(cursor)).build(),allowCache,false,new CatalogRepository.Listener(){
            public void loaded(byte[] bytes,boolean cached){repository.decode(()->{
                try{CatalogCodec.Page page=CatalogApi.decode(bytes);main.post(()->{
                    if(!activeRequest(token,requestId))return;
                    try{
                        pages.prepend(cursor,page);loading=false;
                        if(!cached)repository.save(url(cursor),bytes);
                        List<Show> shows=displayPages();
                        footer.setText(library.visible(pages.snapshot()).size()+" 部 · 当前浏览段"+(pages.hasPrevious()?" · 上键回看前段":""));
                        message.setText("没有可显示的番剧\n可以修改筛选条件或内容显示设置");
                        submitPages(shows,token,requestId,true);
                        empty.setVisibility(shows.isEmpty()?View.VISIBLE:View.GONE);
                        updateFilteredPageAction(shows);
                    }catch(IOException error){previousFailure(token,requestId,error.getMessage());}
                });}catch(IOException error){main.post(()->{
                    if(cached && activeRequest(token,requestId)){loading=false;loadPrevious(false);}
                    else previousFailure(token,requestId,error.getMessage());
                });}
            });}
            public void failed(String error){previousFailure(token,requestId,error);}
        });
    }
    private void previousFailure(int token,int requestId,String error){
        if(!activeRequest(token,requestId))return;loading=false;
        footer.setText("上一段读取失败 · "+error+" · 上键重试");
    }
    private void more() { if (!pages.ended() && !loading && !listApplying && pendingFocus<0 && grid.getDescendantFocusability()!=ViewGroup.FOCUS_BLOCK_DESCENDANTS && retryMore.getVisibility() != View.VISIBLE) load(false); }
    private List<Show> displayPages(){
        List<Show> visible=library.visible(pages.snapshot());
        List<Show> values=new ArrayList<>();
        int padding=visible.isEmpty()?0:(int)(pages.offset(library.showR18())%layout.getSpanCount());
        for(int i=0;i<padding;i++)values.add(new Show(Long.MIN_VALUE+i,"","",""));
        values.addAll(visible);return values;
    }
    /** Freeze navigation only across the asynchronous diff, then map its target by ID. */
    private void submitPages(List<Show> values,int token,int requestId,boolean backwards){
        if(!listApplying){
            int position=pendingFocus>=0?pendingFocus:focusedPosition;
            View focus=getCurrentFocus();RecyclerView.ViewHolder current=focus==null?null:grid.findContainingViewHolder(focus);
            if(pendingFocus<0 && current!=null && current.getBindingAdapterPosition()!=RecyclerView.NO_POSITION)position=current.getBindingAdapterPosition();
            listAnchor=returningFromDetail?returningShowId:position>=0 && position<adapter.getItemCount()?adapter.getItemId(position):-1;
            View anchor=layout.findViewByPosition(position);
            listAnchorTop=anchor==null?0:layout.getDecoratedTop(anchor)-grid.getPaddingTop();
            listRestoreFocus=resumed && (grid.hasFocus() || pendingFocus>=0 || (retryMore.hasFocus() || empty.hasFocus()) && !values.isEmpty());
        }
        final int revision=++listRevision;listApplying=true;pendingFocus=-1;focusEpoch++;
        if(listRestoreFocus){grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);grid.requestFocus();}
        adapter.submitList(values,()->{
            if(destroyed || revision!=listRevision)return;
            leadingSlots=0;while(leadingSlots<values.size() && values.get(leadingSlots).id<0)leadingSlots++;
            listApplying=false;
            if(!activeRequest(token,requestId)){
                listRestoreFocus=false;queuedDirections.clear();pendingFocus=-1;focusEpoch++;
                grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);return;
            }
            if(!listRestoreFocus || values.isEmpty())grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            int mapped=indexOf(values,listAnchor);
            if(listAnchor>0 && mapped>=0){
                focusedPosition=backwards?Math.max(leadingSlots,mapped-layout.getSpanCount()):mapped;
                if(listRestoreFocus){
                    layout.scrollToPositionWithOffset(focusedPosition,listAnchorTop);
                    needsFocus=true;
                }
            }else if(listRestoreFocus){focusedPosition=Math.max(leadingSlots,Math.min(focusedPosition,values.size()-1));needsFocus=true;}
            boolean positioned=listRestoreFocus && listAnchor>0 && mapped>=0;listRestoreFocus=false;restoreFocus(positioned);
            while(!queuedDirections.isEmpty() && !listApplying)navigate(queuedDirections.removeFirst());
        });
    }
    private void restoreFocus(){restoreFocus(false);}
    private void restoreFocus(boolean positioned) {
        // History can reorder after playback. Resolve only after its new list is committed.
        if(returningFromDetail && resumed && isLocalSection()){
            returningFromDetail=false;
            int mapped=indexOf(adapter.getCurrentList(),returningShowId);
            focusedPosition=mapped>=0?mapped:Math.max(0,Math.min(focusedPosition,adapter.getItemCount()-1));
            layout.scrollToPositionWithOffset(focusedPosition,returningTop);
            needsFocus=true;positioned=true;
        }
        if (restoredScroll != null) { layout.onRestoreInstanceState(restoredScroll); restoredScroll = null; needsFocus = true; }
        if (needsFocus && adapter.getItemCount() > 0) { needsFocus = false; focusCard(focusedPosition,positioned); }
        else if (needsFocus && empty.getVisibility() == View.VISIBLE) { needsFocus = false; focusEmptyAction(); }
    }
    private void focusCard(int p){focusCard(p,false);}
    private void focusCard(int p,boolean positioned) {
        if (adapter.getItemCount() == 0) return;
        int index = Math.max(leadingSlots, Math.min(p, adapter.getItemCount() - 1));
        final int epoch=++focusEpoch;
        pendingFocus = index;
        RecyclerView.ViewHolder ready=grid.findViewHolderForAdapterPosition(index);
        View attached=ready!=null && ready.getItemId()==adapter.getItemId(index) && !grid.hasPendingAdapterUpdates() && !grid.isLayoutRequested()?ready.itemView:null;
        if (attached != null) {
            grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            keepCardVisible(attached);
            if (attached.requestFocus()) {
                if(pendingFocus==index)focused(index); // An already-focused view emits no focus-change callback.
                return;
            }
        }
        // Keep automatic child focus out of the layout pass while the target is offscreen.
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);grid.requestFocus();
        if(!positioned)grid.scrollToPosition(index);
        awaitCardFocus(index, 0,epoch);
    }
    private void awaitCardFocus(int index, int attempt,int epoch) {
        grid.postOnAnimation(() -> {
            if (destroyed || pendingFocus != index || epoch!=focusEpoch || listApplying) return;
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(index);
            if (holder != null && holder.getItemId()==adapter.getItemId(index) && !grid.hasPendingAdapterUpdates() && !grid.isLayoutRequested()) {
                grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                keepCardVisible(holder.itemView);
                if (holder.itemView.requestFocus()) {
                    if(pendingFocus==index)focused(index);
                    return;
                }
            }
            if (attempt < 12) awaitCardFocus(index, attempt + 1,epoch);
            else {pendingFocus = -1;grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);}
        });
    }
    private void keepCardVisible(View card) {
        if (grid.isComputingLayout()) return;
        android.graphics.Rect bounds = new android.graphics.Rect();
        layout.getDecoratedBoundsWithMargins(card, bounds);
        int top = grid.getPaddingTop(), bottom = grid.getHeight() - grid.getPaddingBottom();
        if (bounds.bottom > bottom) grid.scrollBy(0, bounds.bottom - bottom);
        else if (bounds.top < top) grid.scrollBy(0, bounds.top - top);
    }
    @Override public void focused(int position) {
        if(!resumed || listApplying || pendingFocus>=0 && pendingFocus!=position)return;
        focusedPosition = position;
        if (pendingFocus == position) pendingFocus = -1;
        if (position >= adapter.getItemCount() - layout.getSpanCount() * 2) more();
    }
    private void rail() { returningFromDetail=false;listRestoreFocus=false;queuedDirections.clear();focusEpoch++;pendingFocus = -1; needsFocus = false; restoredScroll = null; rail.get(section).requestFocus(); }
    private void filters() {
        listRestoreFocus=false;queuedDirections.clear();focusEpoch++;pendingFocus=-1;needsFocus=false;restoredScroll=null;
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (filters.getVisibility() == View.VISIBLE) focusFilter(lastFilter); else rail();
    }
    private void updateHistorySelection(){adapter.selection(historySelection,selectedHistory);deleteSelected.setText("删除("+selectedHistory.size()+")");footer.setText("已选 "+selectedHistory.size()+" 条 · 确认选择 · 下键操作 · 返回取消");}
    private void endHistorySelection(){boolean restore=historyActions.hasFocus();historySelection=false;selectedHistory.clear();historyActions.setVisibility(View.GONE);adapter.selection(false,selectedHistory);footer.setText("确认查看 / 菜单批量选择 / 长按删除");if(restore){if(adapter.getItemCount()>0)focusCard(focusedPosition);else rail();}}
    private void confirmHistorySelection(){
        if(selectedHistory.isEmpty()){Toast.makeText(this,"请先选择记录",Toast.LENGTH_SHORT).show();return;}
        final java.util.Set<Long> ids=new java.util.HashSet<>(selectedHistory);
        showDialog(TvDialogs.builder(this,"删除 "+ids.size()+" 条观看记录？").setMessage("同时清除这些番剧的续播进度。收藏和记忆线路保留。")
            .setPositiveButton("删除",(d,w)->{stopHistoryMetadata();new PlaybackHistory(this).removeAll(ids,library.endpoint());endHistorySelection();rail();reload();})
            .setNegativeButton("取消",null).create(),false);
    }
    @Override public void clicked(Show show) {
        if(historySelection && section==HISTORY){if(!selectedHistory.remove(show.id))selectedHistory.add(show.id);updateHistorySelection();return;}
        returningFromDetail = true;returningShowId=show.id;
        int position=indexOf(adapter.getCurrentList(),show.id);
        if(position>=0)focusedPosition=position;
        View anchor=layout.findViewByPosition(focusedPosition);
        returningTop=anchor==null?0:layout.getDecoratedTop(anchor)-grid.getPaddingTop();
        Intent intent = new Intent(this, DetailActivity.class).putExtra("id", show.id)
                .putExtra("title", show.title).putExtra("image", show.image).putExtra("subtitle", show.subtitle).putExtra("r18",show.r18)
                .putExtra("demo", demo && section!=HISTORY); startActivity(intent);
    }
    private void choose(String title, String[] names, String[] values, int which) {
        showDialog(FilterPicker.create(this, title, names, java.util.Arrays.asList(values).indexOf(which==0?type:which==1?lang:year), (d, index) -> {
            if (which == 0) { type = values[index]; typeButton.setText(index == 0 ? "类型" : names[index]); }
            else if (which == 1) { lang = values[index]; langButton.setText(index == 0 ? "语言" : names[index]); }
            else { year = values[index]; yearButton.setText(index == 0 ? "年份" : names[index]); }
            typeButton.setSelected(!type.isEmpty());langButton.setSelected(!lang.isEmpty());yearButton.setSelected(!year.isEmpty());
            focusedPosition = 0; needsFocus = true; reload();
        }), false);
    }
    private void years() {
        int now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String[] names = new String[37], values = new String[37]; names[0] = "全部"; values[0] = "";
        for (int i = 1; i < names.length; i++) names[i] = values[i] = String.valueOf(now + 1 - i);
        choose("年份", names, values, 2);
    }
    private void search() {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(22),dp(22),dp(12));
        TextView title=Ui.text(this,"搜索番剧",22,Ui.TEXT);Ui.bold(title);panel.addView(title);
        EditText input=TvDialogs.input(this,"","");input.setTag("search-input");input.setBackground(Ui.formBackground(this));input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,-2);inputParams.topMargin=dp(16);panel.addView(input,inputParams);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setFillViewport(false);scroll.addView(panel);
        AlertDialog dialog=new AlertDialog.Builder(this).setNegativeButton("取消",null).setPositiveButton("搜索",null).create();dialog.setView(scroll,0,0,0,0);
        input.setOnEditorActionListener((v,action,event)->{
            boolean enter=event!=null && (event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER || event.getKeyCode()==android.view.KeyEvent.KEYCODE_NUMPAD_ENTER);
            if(action==EditorInfo.IME_ACTION_SEARCH || enter){if(!enter || event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled())dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return true;}return false;
        });
        showDialog(dialog,false);dialog.getWindow().setLayout(Math.min(dp(520),getResources().getDisplayMetrics().widthPixels-dp(48)),-2);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackground(Ui.emphasis(this,8));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String text=input.getText().toString().trim();if(text.isEmpty()){input.requestFocus();return;}keyword=text;dialog.dismiss();select(SEARCH,true);});input.requestFocus();
    }
    private void settings(){settings(0);}
    private String[] settingsItems(){
        boolean loggedIn=new AccountSession(this).available(HttpUrl.get(library.endpoint()));
        return new String[]{"修改番剧接口","播放视频链接","自动下一集："+(library.prefs.getBoolean("auto-next",false)?"开":"关"),loggedIn?"已登录 · 账号管理":"未登录 · 登录获取完整线路","缓存与记录管理","安全模式："+(library.safeMode()?"开":"关"),"关于 AniTV"};
    }
    private void settings(int position) {
        settingsPosition=position;
        boolean loggedIn=new AccountSession(this).available(HttpUrl.get(library.endpoint()));
        AlertDialog menu=TvDialogs.menu(this,loggedIn?"设置 · 已登录":"设置 · 未登录",settingsItems(),(d,index)->{});
        showDialog(menu,true);menu.getListView().setSelection(position);
        menu.getListView().setOnItemClickListener((parent,view,index,id)->{
            settingsPosition=index;
            if(index==0)endpoint();
            else if(index==1)videoLink();
            else if(index==2){
                library.prefs.edit().putBoolean("auto-next",!library.prefs.getBoolean("auto-next",false)).apply();
                updateSettingsMenu(menu,index);
            }else if(index==3){returnToSettings=true;menu.dismiss();startActivity(new Intent(this,LoginActivity.class));}
            else if(index==4)cacheSettings();
            else if(index==5){contentVisibility();updateSettingsMenu(menu,index);}
            else showSettingsDialog(TvDialogs.builder(this,"AniTV "+BuildConfig.VERSION_NAME)
                    .setMessage("原生电视首页、封面选集、播放、换源和观看进度。\n\n支持 MP4、HLS 及 HTTP/HTTPS 视频链接。推荐、索引、搜索、详情和播放使用 AniCh 接口，登录后读取完整线路。")
                    .setPositiveButton("知道了",null).create(),false);
        });
    }
    @SuppressWarnings("unchecked") private void updateSettingsMenu(AlertDialog menu,int position){
        android.widget.ArrayAdapter<String> adapter=(android.widget.ArrayAdapter<String>)menu.getListView().getAdapter();
        adapter.setNotifyOnChange(false);adapter.clear();adapter.addAll(settingsItems());adapter.notifyDataSetChanged();menu.getListView().setSelection(position);
    }
    private void contentVisibility(){
        boolean safeMode=!library.safeMode();
        library.prefs.edit().putBoolean(ContentVisibility.PREFERENCE,safeMode).remove("show-r18").apply();
        rail();focusedPosition=0;pendingFocus=-1;needsFocus=false;reload(true);
    }
    private void updateFilteredPageAction(List<Show> values){
        if(!loading && requestFailure==null){
            boolean pageButtonFocused=retryMore.hasFocus();
            retryMore.setText("继续加载下一页");
            retryMore.setVisibility(values.isEmpty() && !pages.ended()?View.VISIBLE:View.GONE);
            if(pageButtonFocused && retryMore.getVisibility()!=View.VISIBLE && values.isEmpty() && resumed)focusEmptyAction();
        }
    }
    private void cacheSettings() {
        AlertDialog waiting=TvDialogs.builder(this,"缓存与记录管理").setMessage("正在统计缓存…")
                .setNegativeButton("返回",null).create();showSettingsDialog(waiting,true);
        cacheManager.sizes((images,metadata)->{
            if(destroyed || activeDialog!=waiting || !waiting.isShowing())return;
            showSettingsDialog(TvDialogs.menu(this,"缓存与记录管理",new String[]{
                    "清理图片缓存 · "+CacheManager.label(images),"清理接口缓存 · "+CacheManager.label(metadata),
                    "清理全部缓存 · "+CacheManager.label(images+metadata),"清空观看历史","重置记忆线路"},
                    (d,kind)->confirmClear(kind)),true);
        });
    }
    private void confirmClear(int kind) {
        if(kind<=CacheManager.ALL){clearSelectedCache(kind);return;}
        String[] labels={"图片缓存","接口缓存","全部缓存","观看历史","记忆线路"};
        String explanation=kind==CacheManager.HISTORY?"清除历史列表和全部续播进度。账号、收藏和记忆线路保留。":
                "所有番剧恢复默认线路，下次手动选择后重新记忆。账号、收藏和观看历史保留。";
        showDialog(TvDialogs.builder(this,"清理"+labels[kind]+"？").setMessage(explanation)
                .setNegativeButton("取消",null).setPositiveButton("清理",(d,w)->clearSelectedCache(kind)).create(),false,this::cacheSettings);
    }
    private void clearSelectedCache(int kind){
        if(kind==CacheManager.HISTORY || kind==CacheManager.METADATA || kind==CacheManager.ALL)stopHistoryMetadata();
        if(kind==CacheManager.METADATA || kind==CacheManager.ALL){
            if(job!=null)job.cancel();generation++;requestSequence++;loading=false;
        }
        cacheManager.clear(kind,result->{
            if(destroyed)return;
            Toast.makeText(this,result,Toast.LENGTH_SHORT).show();
            if(kind==CacheManager.HISTORY && section==HISTORY){rail();reload();}
            if(kind==CacheManager.METADATA || kind==CacheManager.ALL){
                spinner.setVisibility(View.GONE);footer.setText(result+" · 在侧栏确认当前页可刷新");
                if(!isLocalSection() && adapter.getItemCount()==0)message.setText("缓存已清理\n在侧栏确认当前页可重新加载");
            }
        });
    }
    private void videoLink() {
        EditText input = TvDialogs.input(this,"https://…/video.mp4 或 .m3u8", "");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = TvDialogs.builder(this,"播放视频链接").setView(TvDialogs.field(this,input))
                .setNegativeButton("取消", null).setPositiveButton("播放", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String url = PlaySource.networkUrl(input.getText().toString());returnToSettings=true;dialog.dismiss();
                startActivity(new Intent(this, PlayerActivity.class).putExtra("directUrl", url).putExtra("title", "视频链接"));
            } catch (java.io.IOException e) { input.setError(e.getMessage()); }
        })); showSettingsDialog(dialog,false);
    }
    private void endpoint() {
        EditText input = TvDialogs.input(this,"HTTPS 接口地址",library.endpoint()); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(library.endpoint());
        AlertDialog dialog = TvDialogs.builder(this,"番剧接口地址").setView(TvDialogs.field(this,input))
                .setMessage("使用 AniCh 兼容的 HTTPS 接口。修改地址不会绕过服务端验证。")
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            HttpUrl url = HttpUrl.parse(input.getText().toString().trim());
            if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty() ||
                    url.query() != null || url.fragment() != null || !url.encodedPath().equals("/")) {
                input.setError("请输入 HTTPS 根地址，例如 https://example.org"); return;
            }
            library.prefs.edit().putString("endpoint", url.toString()).putBoolean("demo", false).apply();returnToSettings=true;dialog.dismiss();demo = false; type = ""; lang = ""; year = ""; recreate();
        })); showSettingsDialog(dialog,false);
    }
    private void showSettingsDialog(AlertDialog dialog,boolean side){showDialog(dialog,side,()->settings(settingsPosition));}
    private void showDialog(AlertDialog dialog,boolean side){showDialog(dialog,side,null);}
    private void showDialog(AlertDialog dialog,boolean side,Runnable parent){
        AlertDialog previous=activeDialog;activeDialog=dialog;
        if(previous!=null && previous!=dialog && previous.isShowing())previous.dismiss();
        dialog.setOnDismissListener(d->{
            if(activeDialog!=dialog)return;activeDialog=null;
            if(parent!=null)main.post(()->{if(!destroyed && resumed && !returnToSettings && activeDialog==null && !isFinishing())parent.run();});
        });
        if("compact-filter".equals(dialog.getWindow().getDecorView().getTag()))dialog.show();else TvDialogs.show(this,dialog,side);
    }
    @Override public void onBackPressed() {
        if(activeDialog!=null && activeDialog.isShowing()){activeDialog.cancel();return;}
        if(historySelection){endHistorySelection();return;}
        pendingFocus = -1;
        if (!rail.get(section).hasFocus()) { rail(); return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBack < 2000) super.onBackPressed();
        else { lastBack = now; Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show(); }
    }
    private void deleteHistory(Show show){
        showDialog(TvDialogs.builder(this,"删除此记录？").setMessage(show.title+"\n\n同时清除这部番的续播进度，收藏和记忆线路保留。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                    stopHistoryMetadata();new PlaybackHistory(this).remove(show.id,library.endpoint());
                    List<Show> shows=library.visible(new PlaybackHistory(this).shows(library.endpoint()));
                    focusedPosition=Math.max(0,Math.min(focusedPosition,shows.size()-1));pendingFocus=-1;needsFocus=!shows.isEmpty();
                    local(shows,"暂无观看历史\n播放番剧后会自动记录，可在这里继续观看");
                    if(shows.isEmpty())rail();startHistoryMetadata();
                }).create(),false);
    }
    private void cancelHistoryPress(){main.removeCallbacks(historyHold);historyPressTarget=null;}
    private boolean historyConfirm(KeyEvent event){
        if(historySelection)return false;
        int key=event.getKeyCode();boolean confirm=key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER;
        if(!confirm){if(event.getAction()==KeyEvent.ACTION_DOWN)cancelHistoryPress();return false;}
        if(historyConsumeRelease){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)historyConsumeRelease=false;
            else{if(event.getAction()==KeyEvent.ACTION_UP)historyConsumeRelease=false;return true;}
        }
        if(activeDialog!=null && activeDialog.isShowing() || section!=HISTORY)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){
                View focus=getCurrentFocus();RecyclerView.ViewHolder holder=focus==null?null:grid.findContainingViewHolder(focus);
                if(holder==null || holder.getBindingAdapterPosition()==RecyclerView.NO_POSITION)return false;
                cancelHistoryPress();historyPressTarget=holder.itemView;historyPressId=adapter.getItemId(holder.getBindingAdapterPosition());
                main.postDelayed(historyHold,600);
            }else if(historyPressTarget!=null && (event.isLongPress() || event.getEventTime()-event.getDownTime()>=600)){
                historyHold.run();
            }
            return historyPressTarget!=null || historyConsumeRelease;
        }
        if(event.getAction()==KeyEvent.ACTION_UP && historyPressTarget!=null){
            View target=historyPressTarget;cancelHistoryPress();
            if(!event.isCanceled() && target.hasFocus())target.performClick();return true;
        }
        return false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(refreshConfirm(event))return true;
        if(historyConfirm(event))return true;
        if(activeDialog!=null && activeDialog.isShowing())return activeDialog.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() != KeyEvent.KEYCODE_BACK) lastBack = 0;
        int key = event.getKeyCode();
        if(key==KeyEvent.KEYCODE_MENU && section==HISTORY){if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0){if(historySelection)endHistorySelection();else{historySelection=true;selectedHistory.clear();historyActions.setVisibility(View.VISIBLE);updateHistorySelection();}}return true;}
        if(key==KeyEvent.KEYCODE_MENU && section==1){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)filters();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // A held key advances only after the previous card has actually received focus.
                // Replaying repeats accumulated during a slow diff/layout jumps several rows at once.
                if(event.getRepeatCount()>0 && (listApplying || pendingFocus>=0))return true;
                if (event.getRepeatCount() > 0 && event.getEventTime() - lastDirectionTime < 90) return true;
                lastDirectionTime = event.getEventTime(); navigate(key); return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void cancelRefreshHold(){main.removeCallbacks(refreshHold);if(refreshTarget!=null && !refreshHeld)footer.setText(refreshFooter);refreshTarget=null;refreshHeld=false;}
    private boolean refreshConfirm(KeyEvent event){
        int key=event.getKeyCode();boolean confirm=key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER;
        if(!confirm){if(event.getAction()==KeyEvent.ACTION_DOWN)cancelRefreshHold();return false;}
        if(activeDialog!=null && activeDialog.isShowing())return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0){
                View target=getCurrentFocus();int index=rail.indexOf(target);
                if(index!=0 && index!=1)return false;
                cancelRefreshHold();refreshTarget=target;refreshFooter=footer.getText();footer.setText("按住确认键满 2 秒刷新，松开取消");main.postDelayed(refreshHold,2000);
            }
            return refreshTarget!=null;
        }
        if(event.getAction()==KeyEvent.ACTION_UP && refreshTarget!=null){
            View target=refreshTarget;boolean clicked=!refreshHeld && !event.isCanceled() && target.hasFocus();
            cancelRefreshHold();if(clicked)target.performClick();return true;
        }
        return false;
    }
    private List<View> visibleFilters() {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < filters.getChildCount(); i++) {
            View v = filters.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.isFocusable()) views.add(v);
        }
        return views;
    }
    private void focusFilter(int index) {
        List<View> views = visibleFilters(); lastFilter = Math.max(0, Math.min(index, views.size() - 1));
        if (!views.isEmpty()) views.get(lastFilter).requestFocus();
    }
    private void focusEmptyAction() {
        for (View action : emptyActions) if (action.getVisibility() == View.VISIBLE && action.isEnabled()) { action.requestFocus(); return; }
        rail();
    }
    private void enterContent() {
        if(listApplying){listRestoreFocus=true;grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);grid.requestFocus();return;}
        if (adapter.getItemCount() > 0) focusCard(focusedPosition);
        else if (empty.getVisibility() == View.VISIBLE) focusEmptyAction();
        else focusFilter(0);
    }
    /** Deterministic TV navigation. RecyclerView's geometric focus search is not
     * used at row boundaries or while the next row is being attached. */
    private void navigate(int key) {
        if(listApplying && listRestoreFocus){if(queuedDirections.isEmpty())queuedDirections.addLast(key);return;}
        View focus = getCurrentFocus();
        int railIndex = rail.indexOf(focus);
        if (railIndex >= 0) {
            pendingFocus = -1; needsFocus = false; restoredScroll = null;
            if (key == KeyEvent.KEYCODE_DPAD_UP) rail.get(Math.max(0, railIndex - 1)).requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) rail.get(Math.min(rail.size() - 1, railIndex + 1)).requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Right enters the displayed page; only Confirm changes the selected section.
                if(section==1)filters(); else enterContent();
            }
            return;
        }
        RecyclerView.ViewHolder holder = focus == null ? null : grid.findContainingViewHolder(focus);
        if (pendingFocus >= 0 || holder != null) {
            int p = pendingFocus >= 0 ? pendingFocus : holder.getBindingAdapterPosition();
            if (p < 0) { focusCard(focusedPosition); return; }
            int count = adapter.getItemCount(), span = layout.getSpanCount(), col = p % span;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (col == 0 || p-1<leadingSlots) rail(); else focusCard(p - 1);
            } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (col < span - 1 && p + 1 < count) focusCard(p + 1);
            } else if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (p < span && pages.hasPrevious())loadPrevious();
                else if(p < span){if(filters.getVisibility()==View.VISIBLE)filters();}
                else focusCard(p - span);
            } else {
                if (p + span < count) focusCard(p + span);
                else if ((p / span + 1) * span < count) focusCard(count - 1);
                else if(historySelection){pendingFocus=-1;historyActions.getChildAt(0).requestFocus();}
                else if (retryMore.getVisibility() == View.VISIBLE) { pendingFocus = -1; retryMore.requestFocus(); }
                if(p>=count-span*2)more();
            }
            return;
        }
        List<View> buttons = visibleFilters(); int filter = buttons.indexOf(focus);
        if (filter >= 0) {
            pendingFocus = -1;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (filter == 0) rail(); else focusFilter(filter - 1); }
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) focusFilter(filter + 1);
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (adapter.getItemCount() > 0) focusCard(Math.max(leadingSlots,Math.min(focusedPosition, adapter.getItemCount() - 1)));
                else if (empty.getVisibility() == View.VISIBLE) focusEmptyAction();
            }
            return;
        }
        int action = emptyActions.indexOf(focus);
        if (action >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (action == 0) rail(); else emptyActions.get(action - 1).requestFocus(); }
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) emptyActions.get(Math.min(action + 1, emptyActions.size() - 1)).requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_UP && filters.getVisibility()==View.VISIBLE) filters();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN && retryMore.getVisibility()==View.VISIBLE) retryMore.requestFocus();
            return;
        }
        if(historySelection && focus!=null && focus.getParent()==historyActions){int index=historyActions.indexOfChild(focus);if(key==KeyEvent.KEYCODE_DPAD_LEFT)historyActions.getChildAt(Math.max(0,index-1)).requestFocus();else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)historyActions.getChildAt(Math.min(historyActions.getChildCount()-1,index+1)).requestFocus();else if(key==KeyEvent.KEYCODE_DPAD_UP){if(adapter.getItemCount()>0)focusCard(focusedPosition);else rail();}return;}
        if (focus == retryMore) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if(adapter.getItemCount()>0)focusCard(focusedPosition);
                else focusEmptyAction();
            }
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) rail();
            return;
        }
        rail();
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true;Ui.fullscreen(this); tick.run();
        AccountSession account=new AccountSession(this);boolean available=account.available(HttpUrl.get(library.endpoint()));
        if(accountRevision!=account.revision() || accountAvailable!=available) {
            accountRevision=account.revision();accountAvailable=available;
            if(!demo && section!=FAVORITES && section!=HISTORY){rail();reload(true);}
        }
        if(section==HISTORY && adapter!=null){
            List<Show> shows=library.visible(new PlaybackHistory(this).shows(library.endpoint()));
            if(returningFromDetail)focusedPosition=Math.max(0,indexOf(shows,returningShowId));
            local(shows,"暂无观看历史\n播放番剧后会自动记录，可在这里继续观看");startHistoryMetadata();
        }
        if (section == FAVORITES && adapter != null) local(library.favorites(), "暂无收藏\n在番剧详情中按「收藏」添加");
        if(returnToSettings){returnToSettings=false;settings(settingsPosition);}
        if (returningFromDetail && !isLocalSection()) {
            returningFromDetail=false;
            int mapped=indexOf(adapter.getCurrentList(),returningShowId);
            if(mapped>=0)focusedPosition=mapped;
            if(listApplying){listAnchor=returningShowId;listAnchorTop=returningTop;listRestoreFocus=true;}
            else{
                needsFocus=true;layout.scrollToPositionWithOffset(focusedPosition,returningTop);
                final int epoch=++focusEpoch;
                grid.post(()->{if(resumed && epoch==focusEpoch)restoreFocus(true);});
            }
        }
    }
    @Override protected void onPause() {
        cancelRefreshHold();
        resumed=false;focusEpoch++;pendingFocus=-1;needsFocus=false;listRestoreFocus=false;queuedDirections.clear();
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        stopHistoryMetadata();cancelHistoryPress(); main.removeCallbacks(tick); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("section", section); out.putBoolean("demo", demo);
        out.putString("keyword", keyword); out.putString("type", type); out.putString("lang", lang); out.putString("year", year);
        out.putInt("focused", focusedPosition); out.putParcelable("scroll", layout.onSaveInstanceState());
        out.putBoolean("settings-return",returnToSettings);out.putInt("settings-position",settingsPosition);
        if(!isLocalSection() && !pages.snapshot().isEmpty()){
            out.putSerializable("catalog-window",pages.save());out.putString("catalog-query",queryKey);out.putString("catalog-endpoint",library.endpoint());out.putLong("catalog-account",accountRevision);out.putLong("catalog-at",System.currentTimeMillis());
            out.putLong("catalog-focus-id",focusedPosition>=0&&focusedPosition<adapter.getItemCount()?adapter.getItemId(focusedPosition):-1);out.putBoolean("catalog-grid-focus",grid.hasFocus());
        }
    }
    @Override protected void onDestroy() {
        destroyed = true;cancelHistoryPress();stopHistoryMetadata();cacheManager.close(); if(activeDialog!=null)activeDialog.dismiss(); if (job != null) job.cancel(); repository.close(); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
