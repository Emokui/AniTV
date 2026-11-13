package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.bumptech.glide.Glide;
import java.io.File;
import java.nio.charset.StandardCharsets;
import okhttp3.HttpUrl;
import org.json.JSONObject;

public final class DetailActivity extends Activity {
    private CatalogRepository repository;
    private CatalogRepository.Job job, relatedJob;
    private int relatedRevision, relatedPosition, pendingRelated=-1;
    private LinearLayout relatedBlock;
    private RecyclerView relatedGrid;
    private PosterAdapter relatedAdapter;
    private ScrollView pageScroll;
    private TextView favorite;
    private boolean destroyed;
    private boolean detailFresh, relatedFresh;
    private String detailFailure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView overview, status;
    private PlaybackApi playback;
    private PlaybackApi.Task episodeTask;
    private java.util.List<Episode> episodes = new java.util.ArrayList<>();
    private TextView play, episodeStatus;
    private Show show;
    private String[] playbackTitles;
    private boolean selecting;
    private AlertDialog episodeDialog;
    private int contentGeneration;
    private boolean leavingForRelated,contentReleased;private int savedDetailScroll,savedRelatedPosition;private boolean savedRelatedFocus;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null){savedDetailScroll=state.getInt("detail-scroll");savedRelatedPosition=state.getInt("detail-related");savedRelatedFocus=state.getBoolean("detail-related-focus");}
        renderContent();
    }
    private void renderContent(){
        contentGeneration++;contentReleased=false;leavingForRelated=false;detailFresh=false;relatedFresh=false;detailFailure=null;selecting=false;episodesLoading=false;pendingRelated=-1;relatedPosition=savedRelatedPosition;
        Ui.fullscreen(this);
        show = new Show(getIntent().getLongExtra("id", 0), value("title"), value("image"), value("subtitle"));
        playbackTitles = new String[]{show.title};
        boolean demo = getIntent().getBooleanExtra("demo", false);
        Library library = new Library(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG); root.setPadding(dp(32), dp(22), dp(32), dp(22));
        setContentView(root);
        TextView back = Ui.button(this, "‹  返回列表", v -> onBackPressed());
        root.addView(back, new LinearLayout.LayoutParams(dp(110), dp(36)));
        pageScroll=new ScrollView(this);pageScroll.setFillViewport(true);
        LinearLayout.LayoutParams pageParams=new LinearLayout.LayoutParams(-1,0,1);pageParams.topMargin=dp(20);root.addView(pageScroll,pageParams);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);pageScroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        LinearLayout content = new LinearLayout(this);page.addView(content,new LinearLayout.LayoutParams(-1,-2));
        ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        content.addView(poster, new LinearLayout.LayoutParams(dp(175), dp(248)));
        Glide.with(this).load(show.image.isEmpty() ? null : show.image).override(dp(175), dp(248))
                .centerCrop().dontAnimate().placeholder(new PosterDrawable(show.id)).error(new PosterDrawable(show.id)).into(poster);
        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = dp(28); content.addView(right, rp);
        TextView title = Ui.text(this, show.title, 26, Ui.TEXT); Ui.bold(title); title.setMaxLines(2); right.addView(title);
        TextView subtitle = Ui.text(this, show.subtitle, 12, Ui.MUTED);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(12); right.addView(subtitle, sp);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = dp(22); right.addView(actions, ap);
        play = Ui.button(this, demo ? "界面示例" : "选集播放", v -> chooseEpisode());
        play.setEnabled(!demo); play.setFocusable(!demo); play.setAlpha(demo ? .45f : 1);
        actions.addView(play);
        favorite = Ui.button(this, library.contains(show.id) ? "已收藏 · 移除" : "收藏番剧", null);
        favorite.setOnClickListener(v -> favorite.setText(library.toggle(show) ? "已收藏 · 移除" : "收藏番剧"));
        LinearLayout.LayoutParams favParams = new LinearLayout.LayoutParams(-2, -2); favParams.leftMargin = dp(12);
        actions.addView(favorite, favParams); if (demo) favorite.requestFocus(); else play.requestFocus();
        episodeStatus = Ui.text(this, demo ? "界面示例 · 此卡片没有在线播放内容" : "正在读取剧集…", 11, Ui.ACCENT);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.topMargin = dp(15); right.addView(episodeStatus, np);
        overview = Ui.text(this, demo ? "本地界面示例，没有在线播放内容。" : "正在读取番剧简介…", 14, Ui.MUTED);
        overview.setLineSpacing(dp(6), 1);
        LinearLayout.LayoutParams overviewParams=new LinearLayout.LayoutParams(-1,-2);overviewParams.topMargin=dp(20);right.addView(overview,overviewParams);
        relatedBlock=new LinearLayout(this);relatedBlock.setOrientation(LinearLayout.VERTICAL);relatedBlock.setVisibility(View.GONE);
        LinearLayout.LayoutParams relatedParams=new LinearLayout.LayoutParams(-1,-2);relatedParams.topMargin=dp(24);page.addView(relatedBlock,relatedParams);
        TextView relatedTitle=Ui.text(this,"相关系列",18,Ui.TEXT);Ui.bold(relatedTitle);relatedBlock.addView(relatedTitle);
        relatedGrid=new RecyclerView(this);relatedGrid.setTag("related-series");relatedGrid.setContentDescription("相关季与剧场版");
        LinearLayoutManager relatedLayout=new LinearLayoutManager(this,RecyclerView.HORIZONTAL,false);relatedLayout.setInitialPrefetchItemCount(3);relatedGrid.setLayoutManager(relatedLayout);
        relatedGrid.setItemAnimator(null);relatedGrid.setHasFixedSize(true);relatedGrid.setItemViewCacheSize(4);relatedGrid.setClipToPadding(false);relatedGrid.setPadding(0,dp(8),0,dp(8));
        relatedAdapter=new PosterAdapter(this,new PosterAdapter.Events(){
            public void clicked(Show value){leavingForRelated=true;startActivity(new Intent(DetailActivity.this,DetailActivity.class).putExtra("id",value.id).putExtra("title",value.title).putExtra("image",value.image).putExtra("subtitle",value.subtitle));}
            public void focused(int position){relatedPosition=position;pendingRelated=-1;pageScroll.smoothScrollTo(0,relatedBlock.getTop());}
        });relatedAdapter.horizontal();relatedAdapter.size(dp(120));relatedGrid.setAdapter(relatedAdapter);
        relatedBlock.addView(relatedGrid,new LinearLayout.LayoutParams(-1,Math.round(dp(120)*1.43f)+dp(74)));
        relatedGrid.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrollStateChanged(RecyclerView view,int state){relatedAdapter.pauseImages(state!=RecyclerView.SCROLL_STATE_IDLE);}
        });
        status = Ui.text(this, "", 10, Ui.MUTED); root.addView(status);
        if (!demo && show.id > 0) {
            playback = new PlaybackApi(this); loadEpisodes();
            repository = new CatalogRepository(((AniApp) getApplication()).http(), new File(getCacheDir(), "details"));
            HttpUrl url = show.fromBangumi() ? PublicCatalog.detail(show.metadataId()) : HttpUrl.get(library.endpoint()).newBuilder().addPathSegment("bangumi").addPathSegment("detail")
                    .addPathSegment(Long.toString(show.id)).build();
            if(!show.fromBangumi())loadRelated(library.endpoint());
            final int contentToken=contentGeneration;
            final long historyRevision=new PlaybackHistory(this).revision();
            job = repository.get(url, true, new CatalogRepository.Listener() {
                @Override public void loaded(byte[] bytes, boolean cached) {
                    repository.decode(() -> {
                        try {
                            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                            if (json.optJSONObject("data") != null) json = json.getJSONObject("data");
                            if (json.optLong("id") != show.metadataId()) throw new java.io.IOException("详情响应编号不匹配");
                            PlaybackHistory.Entry metadata=HistoryMetadata.decode(bytes,new PlaybackHistory.Entry(show,library.endpoint(),playbackTitles));
                            String[] names = metadata.titles;
                            String summary = json.optString(show.fromBangumi() ? "summary" : "overview", "暂无简介");
                            main.post(() -> {
                                if (destroyed || contentToken!=contentGeneration || cached && detailFresh) return;
                                if(!cached){detailFresh=true;repository.save(url,bytes);}
                                boolean unknown=PlaybackHistory.missingTitle(show);
                                show=new Show(show.id,metadata.show.title,metadata.show.image.isEmpty()?show.image:metadata.show.image,show.subtitle);
                                title.setText(show.title);
                                Glide.with(DetailActivity.this).load(show.image.isEmpty()?null:show.image).override(dp(175),dp(248))
                                        .centerCrop().dontAnimate().placeholder(new PosterDrawable(show.id)).error(new PosterDrawable(show.id)).into(poster);
                                playbackTitles = names;
                                new PlaybackHistory(DetailActivity.this).enrich(metadata,historyRevision);
                                if(unknown && !PlaybackHistory.missingTitle(show))loadEpisodes(true);
                                overview.setText(summary.isEmpty() ? "暂无简介" : summary);
                                status.setText((show.fromBangumi() ? "Bangumi 简介 · " : "") + (cached ? detailFailure==null?"显示缓存，正在更新…":"显示缓存 · 更新失败" : "简介已更新"));
                            });
                        } catch (Exception error) {
                            if (!cached) main.post(() -> {if(contentToken==contentGeneration)failed("详情格式与当前接口不匹配");});
                        }
                    });
                }
                @Override public void failed(String error) {
                    if (destroyed || contentToken!=contentGeneration) return;
                    detailFailure=error;status.setText(error);
                    if (overview.getText().toString().equals("正在读取番剧简介…")) overview.setText("暂时无法读取简介。可返回列表重试，或在设置中检查接口。");
                }
            });
        }
    }
    private void loadRelated(String endpoint) {
        final int contentToken=contentGeneration;
        HttpUrl url=HttpUrl.get(endpoint).newBuilder().addPathSegment("bangumi").addPathSegment("related").addPathSegment(Long.toString(show.id)).build();
        relatedJob=repository.get(url,true,new CatalogRepository.Listener(){
            public void loaded(byte[] bytes,boolean cached){
                relatedRevision++;
                repository.decode(()->{try{
                    java.util.List<Show> values=RelatedCodec.decode(bytes,show.id);
                    main.post(()->{
                        if(destroyed || contentToken!=contentGeneration || cached && relatedFresh)return;
                        if(!cached){relatedFresh=true;repository.save(url,bytes);}
                        if(values.isEmpty() && relatedGrid.hasFocus())play.requestFocus();
                        relatedAdapter.submitList(values,()->{if(savedRelatedFocus && !values.isEmpty()){savedRelatedFocus=false;focusRelated(savedRelatedPosition,0);}});relatedBlock.setVisibility(values.isEmpty()?View.GONE:View.VISIBLE);
                    });
                }catch(java.io.IOException ignored){/* Related data is optional; do not interrupt detail or playback. */}});
            }
            public void failed(String message){/* Keep cached results if present; otherwise the entire section stays hidden. */}
        });
    }
    private void focusRelated(int position,int attempt) {
        if(destroyed || relatedAdapter.getItemCount()==0 || attempt>12)return;
        int target=Math.max(0,Math.min(position,relatedAdapter.getItemCount()-1));pendingRelated=target;
        RecyclerView.ViewHolder holder=relatedGrid.findViewHolderForAdapterPosition(target);
        if(holder!=null){holder.itemView.requestFocus();return;}
        relatedGrid.scrollToPosition(target);
        relatedGrid.postOnAnimation(()->{if(pendingRelated==target)focusRelated(target,attempt+1);});
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(episodeDialog!=null && episodeDialog.isShowing())return episodeDialog.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        boolean direction=key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT || key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN;
        if(direction) {
            View focus=getCurrentFocus();
            RecyclerView.ViewHolder holder=focus==null?null:relatedGrid.findContainingViewHolder(focus);
            if(holder!=null || pendingRelated>=0) {
                if(event.getAction()==KeyEvent.ACTION_DOWN) {
                    int position=pendingRelated>=0?pendingRelated:holder.getBindingAdapterPosition();
                    if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT)focusRelated(position+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1),0);
                    else if(key==KeyEvent.KEYCODE_DPAD_UP){pendingRelated=-1;play.requestFocus();pageScroll.smoothScrollTo(0,0);}
                }
                return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && (focus==play || focus==favorite) && relatedBlock.getVisibility()==View.VISIBLE) {
                if(event.getAction()==KeyEvent.ACTION_DOWN)focusRelated(relatedPosition,0);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private boolean episodesLoading;
    private int episodeGeneration;
    private void loadEpisodes(){loadEpisodes(false);}
    private void loadEpisodes(boolean replace) {
        if (playback == null || episodesLoading && !replace) return;
        episodesLoading=true;final int token=++episodeGeneration;
        if (episodeTask != null) episodeTask.cancel();
        episodeStatus.setText("正在读取剧集…");
        episodeTask = playback.episodes(show.id, show.title, new PlaybackApi.Result<java.util.List<Episode>>() {
            @Override public void success(java.util.List<Episode> values) {
                if (destroyed || token!=episodeGeneration) return; episodesLoading=false;episodes = values;android.util.Log.i("AniTVNet","kind=episodes stage=ui_ready count="+values.size());
                episodeStatus.setText(values.isEmpty() ? "暂无剧集，确认键重新读取" : values.size() + " 集 · 原生播放 · 上键选集 / 下键换源");
                if (selecting && !values.isEmpty()) { selecting = false; chooseEpisode(); }
            }
            @Override public void cached(java.util.List<Episode> values){success(values);if(!destroyed && token==episodeGeneration)episodeStatus.setText(values.size()+" 集 · 本地记录，后台更新中");}
            @Override public void warning(String message){if(!destroyed && token==episodeGeneration)episodeStatus.setText(episodes.size()+" 集 · "+message);}
            @Override public void error(String message) {
                if (!destroyed && token==episodeGeneration) { episodesLoading=false;selecting = false; episodeStatus.setText(message + " · 点击选集播放重试"); }
            }
        });
    }
    private void chooseEpisode() {
        if (episodes.isEmpty()) { selecting = true; loadEpisodes(); return; }
        final java.util.List<Episode> choices=new java.util.ArrayList<>(episodes);
        int checked = 0;
        int last = new PlaybackHistory(this).lastEpisode(Long.toString(show.id), choices.get(0).number);
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).number == last) checked = i;
        episodeDialog = EpisodePicker.create(this, show.title, choices, checked, index -> { startActivity(new Intent(this, PlayerActivity.class).putExtra("showId", show.id)
                    .putExtra("playbackTitles", playbackTitles).putExtra("image",show.image).putExtra("subtitle",show.subtitle).putExtra("title", show.title).putExtra("episode", choices.get(index).number)
                    .putExtra("episodeTitle", choices.get(index).named ? choices.get(index).title : ""));
        });
        episodeDialog.show();
    }
    private void captureDetailPosition(){if(pageScroll!=null){savedDetailScroll=pageScroll.getScrollY();savedRelatedPosition=relatedPosition;savedRelatedFocus=relatedGrid.hasFocus();}}
    private void releaseContent(){
        contentGeneration++;
        if(relatedJob!=null)relatedJob.cancel();if(job!=null)job.cancel();if(repository!=null)repository.close();
        if(episodeTask!=null)episodeTask.cancel();if(playback!=null)playback.close();
        if(episodeDialog!=null){episodeDialog.dismiss();episodeDialog=null;}main.removeCallbacksAndMessages(null);pendingRelated=-1;
        if(relatedGrid!=null)relatedGrid.setAdapter(null);
        clearImages(getWindow().getDecorView());episodes.clear();
    }
    private void clearImages(View view){if(view instanceof ImageView)Glide.with(this).clear((ImageView)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)clearImages(((ViewGroup)view).getChildAt(i));}
    @Override protected void onStop(){
        if(leavingForRelated){captureDetailPosition();releaseContent();contentReleased=true;setContentView(new android.widget.FrameLayout(this));
            relatedGrid=null;relatedAdapter=null;relatedBlock=null;pageScroll=null;play=null;episodeStatus=null;favorite=null;overview=null;status=null;}
        super.onStop();
    }
    @Override protected void onRestart(){super.onRestart();if(contentReleased){renderContent();pageScroll.post(()->{if(!destroyed && pageScroll!=null)pageScroll.scrollTo(0,savedDetailScroll);});}}
    @Override protected void onSaveInstanceState(Bundle state){captureDetailPosition();super.onSaveInstanceState(state);state.putInt("detail-scroll",savedDetailScroll);state.putInt("detail-related",savedRelatedPosition);state.putBoolean("detail-related-focus",savedRelatedFocus);}
    private String value(String key) { String value = getIntent().getStringExtra(key); return value == null ? "" : value; }
    private int dp(float value) { return Ui.dp(this, value); }
    @Override protected void onResume() { super.onResume(); Ui.fullscreen(this); }
    @Override protected void onDestroy() {
        destroyed=true;releaseContent();super.onDestroy();
    }
}
