package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PlayerActivity extends Activity implements SurfaceHolder.Callback, NativePlayback.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Episode> episodes = new ArrayList<>();
    private List<PlaySource> sources = new ArrayList<>();
    private NativePlayback engine;
    private PlaybackApi api;
    private PlaybackApi.Task episodeTask, sourceTask;
    private PlaybackHistory history;
    private SourcePreference sourcePreference;
    private SurfaceView surface;
    private LinearLayout controls;
    private TextView heading, clock, status, hints, feedback;
    private final Runnable hideFeedback = () -> Ui.hide(feedback);
    private ProgressBar loading, timeline;
    private long showId, resumePosition, lastSeek, pendingSeek = -1, lastSave;
    private String showTitle, directUrl, historyKey, progressKey, episodeTitle, restoredSourceUrl = "";
    private String[] playbackTitles;
    private int number, sourceIndex, restoredSourceIndex = -1, sourceGeneration, timeoutSwitches;
    private boolean surfaceReady, started, destroyed, failed, finished, desiredPlaying = true, dialogOpen;
    private AlertDialog dialog;
    private boolean[] sourceDialogAutoSwitched;
    private boolean prepareAfterDialog;
    private boolean backKeyDown, backExitArmed;
    private long backExitAt;
    private boolean keepScreenOn;
    private int playbackMenuPosition=-1;
    private boolean historyRecorded, episodesLoading, autoEpisodeRequest, autoNextPending;
    private java.util.function.Consumer<String> episodeAction;
    private static final long BACK_EXIT_WINDOW_MS = 5000;
    private static final String INVALID_LINK_HINT = "确认返回重新输入";
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { desiredPlaying = false; engine.pause(); syncControls(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            update();
            if (engine.prepared() && SystemClock.elapsedRealtime() - lastSave >= 5000) save();
            main.postDelayed(this, controls.getVisibility() == View.VISIBLE ? 500 : 5000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);overridePendingTransition(0,0); Ui.fullscreen(this);
        showId = getIntent().getLongExtra("showId", 0);
        showTitle = getIntent().getStringExtra("title"); if (showTitle == null) showTitle = "视频播放";
        playbackTitles = getIntent().getStringArrayExtra("playbackTitles");
        if (playbackTitles == null || playbackTitles.length == 0) playbackTitles = new String[]{showTitle};
        directUrl = getIntent().getStringExtra("directUrl"); if (directUrl == null) directUrl = "";
        episodeTitle = state == null ? getIntent().getStringExtra("episodeTitle") : state.getString("episodeTitle");
        if (episodeTitle == null) episodeTitle = "";
        number = state == null ? getIntent().getIntExtra("episode", 1) : state.getInt("episode", 1);
        if (state != null) {
            restoredSourceIndex = state.getInt("source", -1);
            restoredSourceUrl = state.getString("sourceUrl", "");
            sourceIndex = Math.max(0, restoredSourceIndex);
        } else {
            sourceIndex = 0;
        }
        history = new PlaybackHistory(this); historyKey = PlaybackHistory.key(showId, directUrl);
        String endpoint = new Library(this).endpoint();
        progressKey = showId > 0 ? PlaybackHistory.progressKey(endpoint, showId) : historyKey;
        sourcePreference = new SourcePreference(this,endpoint,historyKey);
        resumePosition = state == null ? history.position(progressKey, number) : state.getLong("position");
        desiredPlaying = state == null || state.getBoolean("playing", true);
        build(); engine = new NativePlayback(this, surface, ((AniApp) getApplication()).http(), this);
        api = new PlaybackApi(this);
        IntentFilter noisyFilter = new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, noisyFilter);
        if (!directUrl.isEmpty()) {
            try {
                sources.add(new PlaySource(PlaySource.networkUrl(directUrl), "视频链接", "", Collections.emptyMap()));
                episodes.add(new Episode(1, showTitle)); sourceIndex = 0;
            } catch (java.io.IOException e) { error(e.getMessage() + " · " + INVALID_LINK_HINT); }
        } else {
            loadEpisodes(); loadSources();
        }
    }
    private int dp(float value) { return Ui.dp(this, value); }
    private void build() {
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK); setContentView(frame);
        surface = new SurfaceView(this); surface.getHolder().addCallback(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        FrameLayout overlay = frame;
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            overlay = new FrameLayout(this);
            Ui.fitFullscreenContent(overlay, false);
            frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(Gravity.CENTER);
        center.setPadding(dp(24), dp(16), dp(24), dp(16));center.setBackground(Ui.shape(this,0xdd17171b,28,0));
        loading = new ProgressBar(this); center.addView(loading, new LinearLayout.LayoutParams(dp(32), dp(32)));
        status = Ui.text(this, "正在打开视频…", 16, Ui.TEXT); status.setGravity(Gravity.CENTER); status.setMaxWidth(dp(620));
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.topMargin = dp(15); center.addView(status, sp);
        overlay.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        status.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(sp.topMargin!=(loading.getVisibility()==View.VISIBLE?dp(15):0)){sp.topMargin=loading.getVisibility()==View.VISIBLE?dp(15):0;status.setLayoutParams(sp);}center.setVisibility(s.length()==0 && loading.getVisibility()!=View.VISIBLE?View.GONE:View.VISIBLE);}public void afterTextChanged(android.text.Editable e){}});
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setPadding(dp(32), dp(20), dp(32), dp(18));
        controls.setBackground(Ui.gradient(0x6617171b,0xee17171b));
        controls.setVisibility(desiredPlaying ? View.GONE : View.VISIBLE);
        heading = Ui.text(this, showTitle, 19, Ui.TEXT); Ui.bold(heading); heading.setMaxLines(1); controls.addView(heading);
        LinearLayout progress = new LinearLayout(this); progress.setGravity(Gravity.CENTER_VERTICAL);
        timeline = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); timeline.setMax(10000);timeline.setProgressDrawable(Ui.timeline(this));timeline.setPadding(0,dp(6),0,dp(6));
        progress.addView(timeline, new LinearLayout.LayoutParams(0, dp(18), 1));
        clock = Ui.text(this, "00:00 / 00:00", 12, Ui.TEXT);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = dp(20); progress.addView(clock, cp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = dp(14); controls.addView(progress, pp);
        hints = Ui.text(this, "确认 暂停/继续   ← → 快退/快进（长按加速）   ↑ 选集   ↓ 换源   菜单 倍速/比例   返回 隐藏进度/再次退出", 11, Ui.MUTED);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = dp(10); controls.addView(hints, hp);
        overlay.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        feedback = Ui.text(this, "", 17, Ui.TEXT); feedback.setTag("playback-feedback");
        feedback.setGravity(Gravity.CENTER); feedback.setPadding(dp(20),dp(12),dp(20),dp(12));
        feedback.setBackground(Ui.shape(this,0xdd17171b,8,0)); feedback.setVisibility(View.GONE);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        fp.topMargin=dp(42);overlay.addView(feedback,fp);
        frame.setFocusableInTouchMode(true); frame.requestFocus();
    }
    private void feedback(String text,long duration) {
        main.removeCallbacks(hideFeedback);feedback.setText(text);Ui.reveal(feedback,100,0);
        main.postDelayed(hideFeedback,duration);
    }
    private void clearFeedback() { main.removeCallbacks(hideFeedback);Ui.hide(feedback); }
    private void loadEpisodes() {
        if(episodesLoading || destroyed)return;episodesLoading=true;
        if (episodeTask != null) episodeTask.cancel();
        episodeTask = api.episodes(showId, showTitle, new PlaybackApi.Result<List<Episode>>() {
            @Override public void success(List<Episode> values) {
                episodes = values;
                finishEpisodeLoad(values.isEmpty() ? "暂无可选剧集" : null);
            }
            @Override public void error(String message) {
                finishEpisodeLoad(message == null ? "剧集读取失败，请重试" : message);
                Log.w("AniTVPlayer", "episode metadata unavailable");
            }
        });
    }
    private void finishEpisodeLoad(String error) {
        autoEpisodeRequest = false;
        autoNextPending = false;
        episodesLoading = false;
        java.util.function.Consumer<String> action = episodeAction;
        episodeAction = null;
        if (action != null && started && !destroyed) action.accept(error);
    }
    private void cancelEpisodeAction() {
        episodeAction = null;
        autoNextPending = false;
        if (autoEpisodeRequest) {
            episodeTask.cancel();
            autoEpisodeRequest = false;
            episodesLoading = false;
        }
    }
    private void readEpisodesFor(Runnable action) {
        boolean manualPending = episodeAction != null && !autoNextPending;
        autoEpisodeRequest = false;
        autoNextPending = false;
        if (!manualPending) Toast.makeText(this, "正在读取剧集…", Toast.LENGTH_SHORT).show();
        episodeAction = error -> {
            if (dialog != null && dialog.isShowing()) return;
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            else action.run();
        };
        loadEpisodes();
    }
    private Episode selectedEpisode() {
        for (Episode episode : episodes) if (episode.number == number) return episode;
        return new Episode(number, episodeTitle, !episodeTitle.isEmpty());
    }
    private void loadSources() {
        if (sourceTask != null) sourceTask.cancel();
        final int token = ++sourceGeneration;
        sources = new ArrayList<>(); failed = false; finished = false; prepareAfterDialog = false; timeoutSwitches = 0;
        syncControls();
        loading.setVisibility(View.VISIBLE); status.setText("正在读取播放线路…");
        sourceTask = api.sources(showId, playbackTitles, selectedEpisode(), new PlaybackApi.Result<List<PlaySource>>() {
            @Override public void progress(String message) { if (!destroyed && token == sourceGeneration) status.setText(message); }
            @Override public void success(List<PlaySource> values) {
                if (destroyed || token != sourceGeneration) return;
                sources = values;
                timeoutSwitches = 0;
                if (sources.isEmpty()) { error("这一集暂无播放线路，可按上键换集或确认键重试"); return; }
                int savedIndex = restoredSourceIndex;
                String savedUrl = restoredSourceUrl;
                restoredSourceIndex = -1;
                restoredSourceUrl = "";
                int matched = -1;
                if (savedUrl != null && !savedUrl.isEmpty()) {
                    for (int i = 0; i < sources.size(); i++) {
                        if (savedUrl.equals(sources.get(i).url)) { matched = i; break; }
                    }
                }
                if (matched < 0 && savedIndex >= 0 && savedIndex < sources.size()) {
                    matched = savedIndex;
                }
                if (matched < 0) {
                    int preferred = sourcePreference.match(sources);
                    matched = preferred >= 0 ? preferred : 0;
                }
                sourceIndex = matched;
                prepare();
            }
            @Override public void error(String message) { if (!destroyed && token == sourceGeneration) PlayerActivity.this.error(message + "\n确认重试 · 上键选集 · 返回退出"); }
        });
    }
    private void prepare() {
        if (!started || sources.isEmpty() || destroyed) return;
        if (dialogOpen) {
            prepareAfterDialog = true;
            return;
        }
        if (!surfaceReady) return;
        prepareAfterDialog = false;
        clearFeedback();
        pendingSeek = -1; lastSeek = 0;
        failed = false; finished = false; loading.setVisibility(View.VISIBLE); status.setText("正在打开视频…");
        engine.open(sources.get(sourceIndex), resumePosition, desiredPlaying); syncControls(); update();
    }
    private void save() {
        if (engine == null || !engine.prepared()) return;
        rememberHistory();
        long position = finished ? 0 : engine.position(), duration = engine.duration();
        resumePosition = PlaybackHistory.resumePosition(position, duration);
        String label = "";
        if (number >= Episode.SPECIAL_NUMBER_START) {
            Episode episode = selectedEpisode();
            if (episode.named) label = episode.title;
        }
        history.save(progressKey, number, position, duration, label);
        lastSave = SystemClock.elapsedRealtime();
    }
    private void rememberHistory(){
        if(historyRecorded || showId<=0)return;
        historyRecorded=true;
        String image=getIntent().getStringExtra("image"),subtitle=PlaybackHistory.cleanSubtitle(getIntent().getStringExtra("subtitle"));
        history.remember(new Show(showId,showTitle,image==null?"":image,subtitle,getIntent().getBooleanExtra("r18",false)),
                new Library(this).endpoint(),playbackTitles);
    }
    private void update() {
        if (engine == null) return;
        boolean playing=engine.playing();
        if (playing!=keepScreenOn) {
            keepScreenOn=playing;
            if(playing)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if(controls.getVisibility()!=View.VISIBLE)return;
        long position = engine.prepared() ? engine.position() : resumePosition, duration = engine.duration();
        String timeText=time(position) + " / " + time(duration);
        if(!android.text.TextUtils.equals(clock.getText(),timeText))clock.setText(timeText);
        int progress=duration > 0 ? (int) Math.min(10000, position * 10000 / duration) : 0;
        if(timeline.getProgress()!=progress)timeline.setProgress(progress);
        String title=showTitle + (showId > 0 ? " · " + selectedEpisode().label() : "") +
                (sources.isEmpty() ? "" : " · " + sources.get(sourceIndex).displayCaption) + (engine.prepared() && !desiredPlaying ? " · 已暂停" : "");
        if(!android.text.TextUtils.equals(heading.getText(),title))heading.setText(title);
    }
    private static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void syncControls() {
        backExitArmed = false;
        if(!desiredPlaying && !dialogOpen && !failed && !finished)Ui.reveal(controls,130,8);else Ui.hide(controls);
        update();
        // Input and engine events update immediately; only visible progress needs frequent polling.
        if (started && !destroyed) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, controls.getVisibility() == View.VISIBLE ? 500 : 5000);
        }
    }
    private void toggle() {
        if (failed) {
            timeoutSwitches = 0;
            if (sources.isEmpty() && !directUrl.isEmpty()) { finish(); return; }
            desiredPlaying = true;
            failed = false;
            if (sources.isEmpty()) loadSources();
            else prepare();
        } else if (finished) { seekFromEnd(0); }
        else if (desiredPlaying) { desiredPlaying = false; engine.pause(); }
        else { desiredPlaying = true; engine.play(); }
        syncControls();
    }
    private void seekFromEnd(long position) {
        cancelEpisodeAction();
        finished = false;
        desiredPlaying = true;
        resumePosition = position;
        if (!engine.prepared()) {
            prepare();
            return;
        }
        status.setText("");
        engine.seek(position);
        engine.play();
    }
    static int seekStepMillis(KeyEvent event) {
        if(event.getRepeatCount()==0)return 10000;
        long held=Math.max(0,event.getEventTime()-event.getDownTime());
        return held>=3000?60000:held>=1000?30000:10000;
    }
    private void seek(int delta) {
        if (finished && delta > 0) return;
        if (!engine.prepared() || engine.duration() <= 0) { syncControls(); return; }
        long now = SystemClock.elapsedRealtime(); if (now - lastSeek < 220) return;
        if (now - lastSeek > 900 || pendingSeek < 0) pendingSeek = engine.position();
        long previous=pendingSeek;
        lastSeek = now; pendingSeek = Math.max(0, Math.min(engine.duration() - 500, pendingSeek + delta));
        if (finished) seekFromEnd(pendingSeek);
        else engine.seek(pendingSeek);
        syncControls();
        long moved=pendingSeek-previous;
        feedback((delta>=0?"快进 ":"快退 ")+Math.abs(moved/1000)+" 秒　"+time(pendingSeek)+" / "+time(engine.duration()),1500);
        Log.i("AniTVPlayer", "seek=" + pendingSeek);
    }
    private void episodeMenu() {
        if (dialog != null && dialog.isShowing()) return;
        if (episodes.isEmpty()) {
            if (directUrl.isEmpty()) readEpisodesFor(this::episodeMenu);
            else if (failed) feedback("链接无效 · " + INVALID_LINK_HINT, 1500);
            return;
        }
        final List<Episode> choices=new ArrayList<>(episodes);
        boolean resume = desiredPlaying && !failed && !finished; engine.pause(); dialogOpen = true;
        int checked = 0;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).number == number) checked = i;
        final boolean[] changed = {false};
        dialog = EpisodePicker.create(this, showTitle, historyKey, choices, checked,true,"当前播放", index -> {
            int next = choices.get(index).number;
            if (next != number) { changed[0] = true; changeEpisode(next); }
        });
        showPlayerDialog(resume, changed);
    }
    private void sourceMenu() {
        if (dialog != null && dialog.isShowing()) return;
        if (sources.isEmpty()) {
            if (!directUrl.isEmpty() && failed) feedback("链接无效 · " + INVALID_LINK_HINT, 1500);
            else Toast.makeText(this, "暂无可用线路，确认键重试", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] resume = {desiredPlaying && !failed && !finished};
        engine.pause(); dialogOpen = true;
        final boolean[] changed = {false}, autoSwitched = {false};
        sourceDialogAutoSwitched = autoSwitched;
        dialog = SourcePicker.create(this, sources, sourceIndex, failed ? "" : engine.videoResolution(), failed, index -> {
            sourcePreference.remember(sources.get(index));
            timeoutSwitches = 0;
            desiredPlaying = true;
            if (index != sourceIndex || failed || autoSwitched[0] || finished) {
                if (!failed && !autoSwitched[0] && !finished) save();
                if (finished) resumePosition = 0;
                changed[0] = true; failed = false; finished = false; sourceIndex = index; prepare();
            } else {
                resume[0] = true;
            }
        });
        showPlayerDialog(resume, changed);
    }
    private void playbackMenu() {
        if(dialog!=null && dialog.isShowing())return;
        boolean resume=desiredPlaying && !failed && !finished;engine.pause();dialogOpen=true;
        float[] speeds={1f,1.25f,1.5f,2f};
        int initial=playbackMenuPosition;
        if(initial<0){initial=0;for(int i=0;i<speeds.length;i++)if(engine.speed()==speeds[i])initial=i;}
        final boolean[] changed={false};
        dialog=PlayerDrawer.settings(this,engine.speed(),engine.aspectMode(),initial,index->{
            playbackMenuPosition=index;
            if (index < speeds.length) engine.setSpeed(speeds[index]);
            else if (index < 7) engine.setAspectMode(index - speeds.length);
            else {
                int delta = index == 7 ? -1 : 1;
                if (episodeAction != null) return false;
                if (episodes.isEmpty() && directUrl.isEmpty()) {
                    readDrawerEpisodes(dialog, delta, changed);
                    return false;
                }
                changed[0] = nextEpisode(delta);
                if (!changed[0]) PlayerDrawer.setSettingsStatus(dialog, adjacentMissing(delta));
                return changed[0];
            }
            return true;
        },position->playbackMenuPosition=position);
        showPlayerDialog(resume,changed);
    }
    private void readDrawerEpisodes(AlertDialog opened, int delta, boolean[] changed) {
        autoNextPending = false;
        PlayerDrawer.setSettingsStatus(opened, "正在读取剧集…");
        episodeAction = error -> {
            if (dialog != opened || !opened.isShowing()) return;
            if (error != null) {
                PlayerDrawer.setSettingsStatus(opened, error);
            } else if (nextEpisode(delta)) {
                changed[0] = true;
                opened.dismiss();
            } else {
                PlayerDrawer.setSettingsStatus(opened, adjacentMissing(delta));
            }
        };
        loadEpisodes();
    }
    private void showPlayerDialog(boolean resume, boolean[] changed) {
        showPlayerDialog(new boolean[]{resume}, changed);
    }
    private void showPlayerDialog(boolean[] resume, boolean[] changed) {
        cancelEpisodeAction();
        clearFeedback();
        backKeyDown = false; backExitArmed = false;
        syncControls();
        final AlertDialog opened = dialog;
        // Consume the full Back press in the window that owns it. Dismissing on
        // key-up prevents the release from reaching the player and exiting it.
        opened.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) opened.cancel();
            return true;
        });
        opened.setOnDismissListener(d -> {
            if (dialog != opened) return;
            episodeAction = null; autoNextPending = false; sourceDialogAutoSwitched = null; dialog = null; dialogOpen = false;
            backKeyDown = false; backExitArmed = false;
            if (!destroyed && started) {
                if (prepareAfterDialog) {
                    prepareAfterDialog = false;
                    if (!failed && !finished) prepare();
                } else if (!changed[0] && resume[0] && desiredPlaying && !failed && !finished) {
                    engine.play();
                }
                syncControls();
            }
        });
        opened.show();
    }
    @Override public void onBackPressed() {
        if (dialog != null && dialog.isShowing()) { dialog.cancel(); return; }
        cancelEpisodeAction();
        long now = SystemClock.elapsedRealtime();
        if (!backExitArmed || now - backExitAt > BACK_EXIT_WINDOW_MS) {
            Ui.hide(controls);
            backExitArmed = true; backExitAt = now;
            feedback("再按一次返回退出播放",2000);
        } else { save(); finish(); }
    }
    private void changeEpisode(int next) {
        save(); engine.stop();historyRecorded=false; number = next; episodeTitle = ""; sourceIndex = 0; restoredSourceIndex = -1; restoredSourceUrl = ""; pendingSeek = -1; lastSeek = 0; prepareAfterDialog = false;
        resumePosition = history.position(progressKey, number); desiredPlaying = true; loadSources();
    }
    private boolean nextEpisode(int delta) {
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number == number && i + delta >= 0 && i + delta < episodes.size()) {
            changeEpisode(episodes.get(i + delta).number); return true;
        }
        return false;
    }
    private boolean selectAdjacentEpisode(int delta) {
        if (episodes.isEmpty() && directUrl.isEmpty()) {
            readEpisodesFor(() -> selectAdjacentEpisode(delta));
            return false;
        }
        if (nextEpisode(delta)) return true;
        Toast.makeText(this, adjacentMissing(delta), Toast.LENGTH_SHORT).show();
        return false;
    }
    private static String adjacentMissing(int delta) { return delta < 0 ? "没有上一集" : "没有下一集"; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // TV firmware can dispatch remote events to the Activity even while a
        // dialog is visible. Route every key to that window before player actions.
        if (dialog != null && dialog.isShowing()) return dialog.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    cancelEpisodeAction();
                    backKeyDown = true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean pressedHere = backKeyDown; backKeyDown = false;
                if (pressedHere && !event.isCanceled()) onBackPressed();
            }
            return true;
        }
        boolean handled = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_MEDIA_NEXT && key != KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            cancelEpisodeAction();
        }
        backExitArmed = false;
        if(key!=KeyEvent.KEYCODE_DPAD_LEFT && key!=KeyEvent.KEYCODE_DPAD_RIGHT && key!=KeyEvent.KEYCODE_MEDIA_REWIND && key!=KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)clearFeedback();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND) seek(-seekStepMillis(event));
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) seek(seekStepMillis(event));
        else if (event.getRepeatCount() == 0) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) episodeMenu();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) sourceMenu();
            else if (key == KeyEvent.KEYCODE_MENU) playbackMenu();
            else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) selectAdjacentEpisode(1);
            else if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) selectAdjacentEpisode(-1);
            else if (key == KeyEvent.KEYCODE_MEDIA_PLAY) { if(failed || finished)toggle();else{desiredPlaying = true; engine.play(); syncControls();} }
            else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) { desiredPlaying = false; engine.pause(); syncControls(); }
            else toggle();
        }
        return true;
    }
    @Override public void ready() { if(!desiredPlaying){loading.setVisibility(View.GONE);status.setText("");} Log.i("AniTVPlayer", "prepared engine=" + engine.engineName() + " duration=" + engine.duration() + " audio_tracks=" + engine.audioTracks()); update(); }
    @Override public void buffering(boolean active) { loading.setVisibility(active ? View.VISIBLE : View.GONE); if (active) status.setText("正在缓冲…"); else if (!failed && !finished) status.setText(""); }
    @Override public void firstFrame(int width, int height) {
        rememberHistory();
        loading.setVisibility(View.GONE); status.setText(""); Log.i("AniTVPlayer", "first_frame " + width + "x" + height); update();
    }
    @Override public void ended() {
        if (finished) return;
        finished = true;
        desiredPlaying = false;
        resumePosition = 0;
        pendingSeek = -1;
        lastSeek = 0;
        save();
        boolean auto = new Library(this).prefs.getBoolean("auto-next", false);
        if (auto && nextEpisode(1)) return;
        engine.pause();
        loading.setVisibility(View.GONE); status.setText("播放结束 · 确认重播 · 上键选集"); syncControls();
        if (auto && episodes.isEmpty() && directUrl.isEmpty()) awaitAutoNext(episodesLoading);
    }
    private void awaitAutoNext(boolean initialReadPending) {
        autoNextPending = true;
        autoEpisodeRequest = !episodesLoading;
        episodeAction = error -> {
            if (!finished || dialogOpen) return;
            if (error == null) nextEpisode(1);
            else if (initialReadPending) awaitAutoNext(false);
            else status.setText("剧集读取失败 · 上键重试");
        };
        loadEpisodes();
    }
    @Override public void timedOut(long position) {
        if (destroyed || !started) return;
        if (position > 0) resumePosition = position;
        if (timeoutSwitches + 1 >= sources.size()) {
            error("线路超时 · 确认重试");
            return;
        }
        timeoutSwitches++;
        sourceIndex = (sourceIndex + 1) % sources.size();
        if (sourceDialogAutoSwitched != null) sourceDialogAutoSwitched[0] = true;
        if (dialog != null && dialog.isShowing()) SourcePicker.updateActive(dialog, sourceIndex, false);
        prepare();
    }
    @Override public void error(String message) {
        failed = true; prepareAfterDialog = false;
        if (dialog != null && dialog.isShowing()) SourcePicker.updateActive(dialog, sourceIndex, true);
        loading.setVisibility(View.GONE); status.setText(message == null ? "播放失败，请重试或换源" : message);
        syncControls(); Log.w("AniTVPlayer", "playback unavailable");
    }
    @Override public void changed() { update(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; if (!failed && !finished) prepare(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { save(); surfaceReady = false; if (engine != null) engine.stop(); }
    @Override protected void onStart() { super.onStart(); started = true;historyRecorded=false; if (surfaceReady && !failed && !finished) prepare(); main.removeCallbacks(tick); main.post(tick); }
    @Override protected void onStop() {
        save(); if (failed || finished) desiredPlaying = false;
        started = false;
        prepareAfterDialog = false;
        cancelEpisodeAction();
        clearFeedback();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        engine.stop();
        main.removeCallbacks(tick); keepScreenOn=false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        save(); super.onSaveInstanceState(state); state.putInt("episode", number); state.putString("episodeTitle", selectedEpisode().named ? selectedEpisode().title : ""); state.putInt("source", sourceIndex);
        String activeUrl = sourceIndex >= 0 && sourceIndex < sources.size() ? sources.get(sourceIndex).url : restoredSourceUrl;
        if (activeUrl != null && !activeUrl.isEmpty()) state.putString("sourceUrl", activeUrl);
        state.putLong("position", resumePosition); state.putBoolean("playing", desiredPlaying);
    }
    @Override public void finish(){super.finish();overridePendingTransition(0,0);}
    @Override protected void onDestroy() {
        destroyed = true; if (episodeTask != null) episodeTask.cancel(); if (sourceTask != null) sourceTask.cancel();
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
        controls.animate().cancel();feedback.animate().cancel();
        api.close(); engine.close(); unregisterReceiver(noisy); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
