package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;

/** Right-side source drawer with a stable source order during asynchronous probes. */
final class SourcePicker {
    interface Selection { void selected(int index); }
    private interface ActiveUpdater { void update(int selected, boolean failed); }
    private static final java.util.Map<AlertDialog, ActiveUpdater> UPDATERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private SourcePicker() {}
    static void updateActive(AlertDialog dialog, int selected, boolean failed) {
        ActiveUpdater updater = dialog == null ? null : UPDATERS.get(dialog);
        if (updater != null) updater.update(selected, failed);
    }
    static AlertDialog create(Activity activity, List<PlaySource> sources, int selected, Selection selection) {
        return create(activity, sources, selected, "", false, selection);
    }
    static AlertDialog create(Activity activity, List<PlaySource> sources, int selected, String currentResolution, Selection selection) {
        return create(activity, sources, selected, currentResolution, false, selection);
    }
    static AlertDialog create(Activity activity, List<PlaySource> sources, int selected, String currentResolution, boolean initialFailed, Selection selection) {
        int safeCursor = sources.isEmpty() ? -1 : Math.max(0, Math.min(selected, sources.size() - 1));
        final int[] active = {initialFailed ? -1 : selected};
        final int[] cursor = {safeCursor};
        final boolean[] userMoved = {false};
        String[] resolutions = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            resolutions[i] = i == active[0] && !currentResolution.isEmpty() ? currentResolution : sources.get(i).resolutionLabel();
        }
        SourceLatency probes = new SourceLatency();
        SourceLatency.Result[] results = new SourceLatency.Result[sources.size()];
        java.util.Arrays.fill(results, SourceLatency.Result.pending());
        ArrayAdapter<PlaySource> adapter = new ArrayAdapter<PlaySource>(activity, android.R.layout.simple_list_item_single_choice, sources) {
            @Override public boolean hasStableIds() { return true; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                SourceRow row = convertView instanceof SourceRow ? (SourceRow) convertView : new SourceRow(activity);
                row.caption.setText(sources.get(position).displayCaption);
                row.resolution.setText(resolutions[position]);
                row.setSelected(position == cursor[0]);
                row.updateLatency(results[position], position == active[0]);
                return row;
            }
        };
        String current = initialFailed ? "当前线路不可用 · 请选择线路"
                : (active[0] >= 0 && active[0] < sources.size() ? "正在使用 · " + sources.get(active[0]).displayCaption : null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(PlayerDrawer.header(activity,"播放线路",sources.size()+" 条线路 · 首字节响应延迟",current))
                .setSingleChoiceItems(adapter, active[0], (d, index) -> { selection.selected(index); d.dismiss(); }).create();
        UPDATERS.put(dialog, (nextSelected, failed) -> {
            int previous = active[0];
            active[0] = failed ? -1 : nextSelected;
            if (previous >= 0 && previous < sources.size()) {
                resolutions[previous] = sources.get(previous).resolutionLabel();
            }
            if (active[0] >= 0 && active[0] < sources.size()) {
                resolutions[active[0]] = sources.get(active[0]).resolutionLabel();
            }
            View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
            TextView summary = decor == null ? null : decor.findViewWithTag("drawer-current-summary");
            if (summary != null) {
                if (failed) {
                    summary.setText("当前线路不可用 · 请选择线路");
                    summary.setVisibility(View.VISIBLE);
                } else if (active[0] >= 0 && active[0] < sources.size()) {
                    summary.setText("正在使用 · " + sources.get(active[0]).displayCaption);
                    summary.setVisibility(View.VISIBLE);
                } else {
                    summary.setText("");
                    summary.setVisibility(View.GONE);
                }
            }
            ListView list = dialog.getListView();
            if (list != null) {
                if (active[0] >= 0 && active[0] < sources.size()) {
                    list.setItemChecked(active[0], true);
                    if (!userMoved[0]) {
                        cursor[0] = active[0];
                        list.setSelection(active[0]);
                    }
                } else {
                    list.clearChoices();
                }
                for (int child = 0; child < list.getChildCount(); child++) {
                    int pos = list.getFirstVisiblePosition() + child;
                    View row = list.getChildAt(child);
                    row.setSelected(pos == cursor[0]);
                    if (row instanceof android.widget.Checkable) ((android.widget.Checkable) row).setChecked(pos == active[0]);
                    if (row instanceof SourceRow && pos >= 0 && pos < sources.size()) {
                        SourceRow sourceRow = (SourceRow) row;
                        sourceRow.resolution.setText(resolutions[pos]);
                        sourceRow.updateLatency(results[pos], pos == active[0]);
                    }
                }
            }
        });
        PlayerDrawer.configureWindow(activity,dialog);
        dialog.setOnShowListener(ignored -> {
            PlayerDrawer.style(activity,dialog,Math.max(0,cursor[0]));
            dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { UPDATERS.remove(dialog); probes.close(); }
            });
            ListView list = dialog.getListView();
            if (active[0] >= 0 && active[0] < sources.size()) {
                list.setItemChecked(active[0], true);
            } else {
                list.clearChoices();
            }
            if (cursor[0] >= 0 && cursor[0] < sources.size()) {
                list.setSelection(cursor[0]);
            }
            list.requestFocus();
            list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (position != cursor[0]) userMoved[0] = true;
                    cursor[0] = position;
                    for (int child = 0; child < list.getChildCount(); child++)
                        list.getChildAt(child).setSelected(list.getFirstVisiblePosition() + child == position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            list.setOnKeyListener((view, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN
                        || key == KeyEvent.KEYCODE_MOVE_HOME || key == KeyEvent.KEYCODE_MOVE_END)) {
                    userMoved[0] = true;
                }
                // Let ListView handle arrows so only its viewport edge scrolls.
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        int position = list.getSelectedItemPosition();
                        if (position < 0) position = cursor[0];
                        if (position >= 0 && position < sources.size()) {
                            list.performItemClick(list.getChildAt(position - list.getFirstVisiblePosition()), position, position);
                        }
                    }
                    return true;
                }
                return false;
            });
            probes.start(sources, (index, result) -> activity.runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    results[index] = result;
                    View row = list.getChildAt(index - list.getFirstVisiblePosition());
                    if (row instanceof SourceRow) ((SourceRow) row).updateLatency(result, index == active[0]);
                }
            }));
        });
        return dialog;
    }
    private static final class SourceRow extends PlayerDrawer.Option {
        final TextView caption,latency,resolution;
        SourceRow(Activity activity) {
            super(activity);caption=title;latency=detail;
            removeView(latency);
            android.widget.LinearLayout metadata=new android.widget.LinearLayout(activity);
            metadata.setGravity(android.view.Gravity.CENTER_VERTICAL);
            resolution=Ui.text(activity,"",11,Ui.TEXT);resolution.setTag("source-resolution");
            metadata.addView(resolution,new android.widget.LinearLayout.LayoutParams(0,-2,1));
            metadata.addView(latency,new android.widget.LinearLayout.LayoutParams(-2,-2));
            android.widget.LinearLayout.LayoutParams mp=new android.widget.LinearLayout.LayoutParams(-1,-2);mp.topMargin=Ui.dp(activity,8);addView(metadata,mp);
            caption.setTextSize(13);setMinimumHeight(Ui.dp(activity,68));badge.setText("使用中");
        }
        void updateLatency(SourceLatency.Result result, boolean current) {
            int color = result.success() ? result.millis <= 800 ? 0xff79d9a1 : result.millis <= 2000 ? 0xffe7c66d : 0xfff19575
                    : result.label.equals("等待测速") || result.label.equals("测速中…") ? Ui.MUTED : 0xfff19575;
            latency.setText(result.success()?"响应  "+result.label:result.label);latency.setTextColor(color);
            setContentDescription(caption.getText() + "，" + resolution.getText() + "，" + result.label+(current?"，当前使用":""));
        }
    }
}
