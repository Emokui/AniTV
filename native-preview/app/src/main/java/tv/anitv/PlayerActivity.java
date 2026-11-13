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
    private final Runnable hideFeedback = () -> feedback.setVisibility(View.GONE);
    private ProgressBar loading, timeline;
    private long showId, resumePosition, lastSeek, pendingSeek = -1, lastSave;
    private String showTitle, directUrl, historyKey, episodeTitle;
    private String[] playbackTitles;
    private int number, sourceIndex, sourceGeneration;
    private boolean surfaceReady, started, destroyed, failed, finished, desiredPlaying = true, dialogOpen;
    private AlertDialog dialog;
    private boolean backKeyDown, backExitArmed;
    private long backExitAt;
    private boolean keepScreenOn;
    private int playbackMenuPosition=-1;
    private boolean historyRecorded, episodesLoading;
    private static final long BACK_EXIT_WINDOW_MS = 5000;
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
        super.onCreate(state); Ui.fullscreen(this);
        showId = getIntent().getLongExtra("showId", 0);
        showTitle = getIntent().getStringExtra("title"); if (showTitle == null) showTitle = "视频播放";
        playbackTitles = getIntent().getStringArrayExtra("playbackTitles");
        if (playbackTitles == null || playbackTitles.length == 0) playbackTitles = new String[]{showTitle};
        directUrl = getIntent().getStringExtra("directUrl"); if (directUrl == null) directUrl = "";
        episodeTitle = state == null ? getIntent().getStringExtra("episodeTitle") : state.getString("episodeTitle");
        if (episodeTitle == null) episodeTitle = "";
        number = state == null ? getIntent().getIntExtra("episode", 1) : state.getInt("episode", 1);
        sourceIndex = state == null ? 0 : state.getInt("source", 0);
        history = new PlaybackHistory(this); historyKey = PlaybackHistory.key(showId, directUrl);
        sourcePreference = new SourcePreference(this,new Library(this).endpoint(),historyKey);
        resumePosition = state == null ? history.position(historyKey, number) : state.getLong("position");
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
            } catch (java.io.IOException e) { error(e.getMessage()); }
        } else {
            loadEpisodes(); loadSources();
        }
    }
    private int dp(float value) { return Ui.dp(this, value); }
    private void build() {
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK); setContentView(frame);
        surface = new SurfaceView(this); surface.getHolder().addCallback(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(Gravity.CENTER);
        center.setPadding(dp(24), dp(16), dp(24), dp(16));
        loading = new ProgressBar(this); center.addView(loading, new LinearLayout.LayoutParams(dp(32), dp(32)));
        status = Ui.text(this, "正在打开视频…", 16, Ui.TEXT); status.setGravity(Gravity.CENTER); status.setMaxWidth(dp(620));
        status.setShadowLayer(3, 1, 1, Color.BLACK);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.topMargin = dp(15); center.addView(status, sp);
        frame.addView(center, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setPadding(dp(32), dp(20), dp(32), dp(18));
        controls.setBackgroundColor(0xdd17171b);
        controls.setVisibility(desiredPlaying ? View.GONE : View.VISIBLE);
        heading = Ui.text(this, showTitle, 19, Ui.TEXT); Ui.bold(heading); heading.setMaxLines(1); controls.addView(heading);
        LinearLayout progress = new LinearLayout(this); progress.setGravity(Gravity.CENTER_VERTICAL);
        timeline = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); timeline.setMax(10000);
        progress.addView(timeline, new LinearLayout.LayoutParams(0, dp(18), 1));
        clock = Ui.text(this, "00:00 / 00:00", 12, Ui.TEXT);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = dp(20); progress.addView(clock, cp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.topMargin = dp(14); controls.addView(progress, pp);
        hints = Ui.text(this, "确认 暂停/继续   ← → 快退/快进（长按加速）   ↑ 选集   ↓ 换源   菜单 倍速/比例   返回 隐藏进度/5秒内再次退出", 11, Ui.MUTED);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = dp(10); controls.addView(hints, hp);
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        feedback = Ui.text(this, "", 17, Ui.TEXT); feedback.setTag("playback-feedback");
        feedback.setGravity(Gravity.CENTER); feedback.setPadding(dp(20),dp(12),dp(20),dp(12));
        feedback.setBackground(Ui.shape(this,0xdd17171b,8,0)); feedback.setVisibility(View.GONE);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        fp.topMargin=dp(42);frame.addView(feedback,fp);
        frame.setFocusableInTouchMode(true); frame.requestFocus();
    }
    private void feedback(String text,long duration) {
        main.removeCallbacks(hideFeedback);feedback.setText(text);feedback.setVisibility(View.VISIBLE);
        main.postDelayed(hideFeedback,duration);
    }
    private void clearFeedback() { main.removeCallbacks(hideFeedback);feedback.setVisibility(View.GONE); }
    private void loadEpisodes() {
        if(episodesLoading || destroyed)return;episodesLoading=true;
        if (episodeTask != null) episodeTask.cancel();
        episodeTask = api.episodes(showId, showTitle, new PlaybackApi.Result<List<Episode>>() {
            @Override public void success(List<Episode> values) { if (!destroyed) {episodesLoading=false;episodes = values;} }
            @Override public void error(String message) { episodesLoading=false;Log.w("AniTVPlayer", "episode metadata unavailable"); }
        });
    }
    private Episode selectedEpisode() {
        for (Episode episode : episodes) if (episode.number == number) return episode;
        return new Episode(number, episodeTitle, !episodeTitle.isEmpty());
    }
    private void loadSources() {
        if (sourceTask != null) sourceTask.cancel();
        final int token = ++sourceGeneration;
        sources = new ArrayList<>(); failed = false; finished = false;
        syncControls();
        loading.setVisibility(View.VISIBLE); status.setText("正在读取播放线路…");
        sourceTask = api.sources(showId, playbackTitles, selectedEpisode(), new PlaybackApi.Result<List<PlaySource>>() {
            @Override public void progress(String message) { if (!destroyed && token == sourceGeneration) status.setText(message); }
            @Override public void warning(String message) { if (!destroyed && token == sourceGeneration) Toast.makeText(PlayerActivity.this,message,Toast.LENGTH_LONG).show(); }
            @Override public void success(List<PlaySource> values) {
                if (destroyed || token != sourceGeneration) return;
                sources = values;
                if (sources.isEmpty()) { error("这一集暂无播放线路，可按上键换集或确认键重试"); return; }
                int preferred=sourcePreference.match(sources);
                sourceIndex=preferred>=0?preferred:0;
                if(preferred<0 && sourcePreference.saved())Toast.makeText(PlayerActivity.this,"本集暂无上次线路，已使用默认线路",Toast.LENGTH_SHORT).show();
                prepare();
            }
            @Override public void error(String message) { if (!destroyed && token == sourceGeneration) PlayerActivity.this.error(message + "\n确认重试 · 上键选集 · 返回退出"); }
        });
    }
    private void prepare() {
        if (!surfaceReady || !started || sources.isEmpty() || destroyed) return;
        clearFeedback();
        pendingSeek = -1; lastSeek = 0;
        failed = false; finished = false; loading.setVisibility(View.VISIBLE); status.setText("正在打开视频…");
        engine.open(sources.get(sourceIndex), resumePosition, desiredPlaying); syncControls(); update();
    }
    private void save() {
        if (engine == null || !engine.prepared()) return;
        rememberHistory();
        resumePosition = engine.position(); history.save(historyKey, number, resumePosition, engine.duration());
        lastSave = SystemClock.elapsedRealtime();
    }
    private void rememberHistory(){
        if(historyRecorded || showId<=0)return;
        historyRecorded=true;
        String image=getIntent().getStringExtra("image"),subtitle=getIntent().getStringExtra("subtitle");
        history.remember(new Show(showId,showTitle,image==null?"":image,subtitle==null?"":subtitle),
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
                (sources.isEmpty() ? "" : " · " + sources.get(sourceIndex).caption) + (engine.prepared() && !desiredPlaying ? " · 已暂停" : "");
        if(!android.text.TextUtils.equals(heading.getText(),title))heading.setText(title);
    }
    private static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void syncControls() {
        backExitArmed = false;
        controls.setVisibility(!desiredPlaying && !dialogOpen && !failed && !finished ? View.VISIBLE : View.GONE);
        update();
        // Input and engine events update immediately; only visible progress needs frequent polling.
        if (started && !destroyed) {
            main.removeCallbacks(tick);
            main.postDelayed(tick, controls.getVisibility() == View.VISIBLE ? 500 : 5000);
        }
    }
    private void toggle() {
        if (failed) {
            desiredPlaying = true;
            if (sources.isEmpty()) { if (directUrl.isEmpty()) loadSources(); }
            else prepare();
        } else if (finished) { finished = false; status.setText(""); engine.seek(0); desiredPlaying = true; engine.play(); }
        else if (desiredPlaying) { desiredPlaying = false; engine.pause(); }
        else { desiredPlaying = true; engine.play(); }
        syncControls();
    }
    static int seekStepMillis(KeyEvent event) {
        if(event.getRepeatCount()==0)return 10000;
        long held=Math.max(0,event.getEventTime()-event.getDownTime());
        return held>=3000?60000:held>=1000?30000:10000;
    }
    private void seek(int delta) {
        if (!engine.prepared() || engine.duration() <= 0) { syncControls(); return; }
        long now = SystemClock.elapsedRealtime(); if (now - lastSeek < 220) return;
        if (now - lastSeek > 900 || pendingSeek < 0) pendingSeek = engine.position();
        long previous=pendingSeek;
        lastSeek = now; pendingSeek = Math.max(0, Math.min(engine.duration() - 500, pendingSeek + delta));
        engine.seek(pendingSeek); syncControls();
        long moved=pendingSeek-previous;
        feedback((delta>=0?"快进 ":"快退 ")+Math.abs(moved/1000)+" 秒　"+time(pendingSeek)+" / "+time(engine.duration()),1500);
        Log.i("AniTVPlayer", "seek=" + pendingSeek);
    }
    private void episodeMenu() {
        if (dialog != null && dialog.isShowing()) return;
        if (episodes.isEmpty()) { loadEpisodes(); Toast.makeText(this, "正在重试读取剧集，请稍后按上键", Toast.LENGTH_SHORT).show(); return; }
        final List<Episode> choices=new ArrayList<>(episodes);
        boolean resume = desiredPlaying; engine.pause(); dialogOpen = true;
        int checked = 0;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).number == number) checked = i;
        final boolean[] changed = {false};
        dialog = EpisodePicker.create(this, showTitle, choices, checked, index -> {
            int next = choices.get(index).number;
            if (next != number) { changed[0] = true; changeEpisode(next); }
        });
        showPlayerDialog(resume, changed);
    }
    private void sourceMenu() {
        if (dialog != null && dialog.isShowing()) return;
        if (sources.isEmpty()) { Toast.makeText(this, "暂无可用线路，确认键重试", Toast.LENGTH_SHORT).show(); return; }
        boolean resume = desiredPlaying; engine.pause(); dialogOpen = true;
        final boolean[] changed = {false};
        dialog = SourcePicker.create(this, sources, sourceIndex, index -> {
            sourcePreference.remember(sources.get(index));
            if (index != sourceIndex || failed) {
                changed[0] = true; save(); sourceIndex = index; desiredPlaying = true; prepare();
            }
        });
        showPlayerDialog(resume, changed);
    }
    private void playbackMenu() {
        if(dialog!=null && dialog.isShowing())return;
        boolean resume=desiredPlaying;engine.pause();dialogOpen=true;
        float[] speeds={1f,1.25f,1.5f,2f};String[] modes={"适应画面","裁剪铺满","拉伸全屏"};
        String[] items=new String[speeds.length+modes.length];
        for(int i=0;i<speeds.length;i++)items[i]=(engine.speed()==speeds[i]?"✓ ":"")+"速度 · "+speeds[i]+"×";
        for(int i=0;i<modes.length;i++)items[speeds.length+i]=(engine.aspectMode()==i?"✓ ":"")+"比例 · "+modes[i];
        int initial=playbackMenuPosition;
        if(initial<0){initial=0;for(int i=0;i<speeds.length;i++)if(engine.speed()==speeds[i])initial=i;}
        final int selected=initial;
        dialog=TvDialogs.menu(this,"播放设置 · 返回关闭",items,(d,index)->{
            playbackMenuPosition=index;
            if(index<speeds.length)engine.setSpeed(speeds[index]);else engine.setAspectMode(index-speeds.length);
        });
        final AlertDialog opened=dialog;
        opened.setOnShowListener(ignored->{
            TvDialogs.style(this,opened,true);
            android.widget.ListView list=opened.getListView();list.setSelection(selected);
            list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){playbackMenuPosition=position;}
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
            });
        });
        showPlayerDialog(resume,new boolean[]{false});
    }
    private void showPlayerDialog(boolean resume, boolean[] changed) {
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
            if (dialog == opened) { dialog = null; dialogOpen = false; }
            backKeyDown = false; backExitArmed = false;
            if (!destroyed && started) {
                if (!changed[0] && resume) engine.play();
                syncControls();
            }
        });
        opened.show();
    }
    @Override public void onBackPressed() {
        if (dialog != null && dialog.isShowing()) { dialog.cancel(); return; }
        long now = SystemClock.elapsedRealtime();
        if (!backExitArmed || now - backExitAt > BACK_EXIT_WINDOW_MS) {
            controls.setVisibility(View.GONE);
            backExitArmed = true; backExitAt = now;
            feedback("5 秒内再次返回退出播放",2000);
        } else { save(); finish(); }
    }
    private void changeEpisode(int next) {
        save(); engine.stop();historyRecorded=false; number = next; episodeTitle = ""; sourceIndex = 0; pendingSeek = -1;
        resumePosition = history.position(historyKey, number); desiredPlaying = true; loadSources();
    }
    private boolean nextEpisode(int delta) {
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number == number && i + delta >= 0 && i + delta < episodes.size()) {
            changeEpisode(episodes.get(i + delta).number); return true;
        }
        return false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // TV firmware can dispatch remote events to the Activity even while a
        // dialog is visible. Route every key to that window before player actions.
        if (dialog != null && dialog.isShowing()) return dialog.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) backKeyDown = true;
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
        backExitArmed = false;
        if(key!=KeyEvent.KEYCODE_DPAD_LEFT && key!=KeyEvent.KEYCODE_DPAD_RIGHT && key!=KeyEvent.KEYCODE_MEDIA_REWIND && key!=KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)clearFeedback();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND) seek(-seekStepMillis(event));
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) seek(seekStepMillis(event));
        else if (event.getRepeatCount() == 0) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) episodeMenu();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) sourceMenu();
            else if (key == KeyEvent.KEYCODE_MENU) playbackMenu();
            else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) nextEpisode(1);
            else if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) nextEpisode(-1);
            else if (key == KeyEvent.KEYCODE_MEDIA_PLAY) { desiredPlaying = true; engine.play(); syncControls(); }
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
        save(); finished = true;
        boolean auto = new Library(this).prefs.getBoolean("auto-next", false);
        if (auto && nextEpisode(1)) return;
        loading.setVisibility(View.GONE); status.setText("播放结束 · 确认重播 · 上键选集"); syncControls();
    }
    @Override public void error(String message) {
        failed = true; loading.setVisibility(View.GONE); status.setText(message == null ? "播放失败，请重试或换源" : message);
        syncControls(); Log.w("AniTVPlayer", "playback unavailable");
    }
    @Override public void changed() { update(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; prepare(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { save(); surfaceReady = false; if (engine != null) engine.stop(); }
    @Override protected void onStart() { super.onStart(); started = true;historyRecorded=false; if (surfaceReady) prepare(); main.removeCallbacks(tick); main.post(tick); }
    @Override protected void onStop() {
        save(); if (failed || finished) desiredPlaying = false;
        started = false;
        clearFeedback();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        engine.stop();
        main.removeCallbacks(tick); keepScreenOn=false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        save(); super.onSaveInstanceState(state); state.putInt("episode", number); state.putString("episodeTitle", selectedEpisode().named ? selectedEpisode().title : ""); state.putInt("source", sourceIndex);
        state.putLong("position", resumePosition); state.putBoolean("playing", desiredPlaying);
    }
    @Override protected void onDestroy() {
        destroyed = true; if (episodeTask != null) episodeTask.cancel(); if (sourceTask != null) sourceTask.cancel();
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); }
        api.close(); engine.close(); unregisterReceiver(noisy); main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
