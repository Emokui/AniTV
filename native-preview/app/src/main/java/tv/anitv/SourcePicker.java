package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ListView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.Checkable;
import android.content.res.ColorStateList;
import android.widget.TextView;
import java.util.List;

/** Right-side source drawer with a stable source order during asynchronous probes. */
final class SourcePicker {
    interface Selection { void selected(int index); }
    private SourcePicker() {}
    static AlertDialog create(Activity activity, List<PlaySource> sources, int selected, Selection selection) {
        SourceLatency probes = new SourceLatency();
        SourceLatency.Result[] results = new SourceLatency.Result[sources.size()];
        java.util.Arrays.fill(results, SourceLatency.Result.pending());
        final int[] cursor = {selected};
        ArrayAdapter<PlaySource> adapter = new ArrayAdapter<PlaySource>(activity, android.R.layout.simple_list_item_single_choice, sources) {
            @Override public boolean hasStableIds() { return true; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                SourceRow row = convertView instanceof SourceRow ? (SourceRow) convertView : new SourceRow(activity);
                row.caption.setText(sources.get(position).caption);
                row.setSelected(position == cursor[0]);
                row.updateLatency(sources.get(position).caption, results[position]);
                return row;
            }
        };
        TextView title = Ui.text(activity, "播放线路 · " + sources.size() + "\n首字节延迟 · 返回关闭", 14, Ui.TEXT);
        title.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 18), Ui.dp(activity, 14), Ui.dp(activity, 14));
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title)
                .setSingleChoiceItems(adapter, selected, (d, index) -> { selection.selected(index); d.dismiss(); }).create();
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Ui.BG));
                window.setGravity(Gravity.RIGHT | Gravity.TOP);
                window.setLayout(Math.round(activity.getResources().getDisplayMetrics().widthPixels * .25f), WindowManager.LayoutParams.MATCH_PARENT);
                WindowManager.LayoutParams params = window.getAttributes(); params.x = 0; params.y = 0; params.dimAmount = .25f;
                window.setAttributes(params);
                window.getDecorView().setPadding(0, 0, 0, 0);
                window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
                window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) { }
                    @Override public void onViewDetachedFromWindow(View view) { probes.close(); }
                });
            }
            ListView list = dialog.getListView();
            list.setSelection(selected); list.requestFocus();
            list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    cursor[0] = position;
                    for (int child = 0; child < list.getChildCount(); child++)
                        list.getChildAt(child).setSelected(list.getFirstVisiblePosition() + child == position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            list.setOnKeyListener((view, key, event) -> {
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        cursor[0] = Math.max(0, Math.min(sources.size() - 1, cursor[0] + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                        list.setSelection(cursor[0]);
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                        list.performItemClick(list.getChildAt(cursor[0] - list.getFirstVisiblePosition()), cursor[0], cursor[0]);
                    return true;
                }
                return false;
            });
            probes.start(sources, (index, result) -> activity.runOnUiThread(() -> {
                if (dialog.isShowing()) {
                    results[index] = result;
                    View row = list.getChildAt(index - list.getFirstVisiblePosition());
                    if (row instanceof SourceRow) ((SourceRow) row).updateLatency(sources.get(index).caption, result);
                }
            }));
        });
        return dialog;
    }
    private static final class SourceRow extends LinearLayout implements Checkable {
        final CheckedTextView caption;
        final TextView latency;
        SourceRow(Activity activity) {
            super(activity); setOrientation(VERTICAL); setGravity(Gravity.CENTER_VERTICAL);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 12), Ui.dp(activity, 10));
            setMinimumHeight(Ui.dp(activity, 76)); setBackground(Ui.focusBackground(activity));
            caption = new CheckedTextView(activity); caption.setTextColor(Ui.TEXT); caption.setTextSize(12);
            caption.setMaxLines(2); caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setCheckMarkDrawable(android.R.drawable.btn_radio);
            caption.setCheckMarkTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.ACCENT,Ui.MUTED}));
            addView(caption,new LinearLayout.LayoutParams(-1,-2));
            latency = Ui.text(activity,"",11,Ui.MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.topMargin=Ui.dp(activity,6); addView(latency,lp);
        }
        void updateLatency(String title, SourceLatency.Result result) {
            int color = result.success() ? result.millis <= 800 ? 0xff79d9a1 : result.millis <= 2000 ? 0xffe7c66d : 0xfff19575
                    : result.label.equals("等待测速") || result.label.equals("测速中…") ? Ui.MUTED : 0xfff19575;
            latency.setText(result.label); latency.setTextColor(color);
            setContentDescription(title + "，" + result.label);
        }
        @Override public void setChecked(boolean checked) { caption.setChecked(checked); }
        @Override public boolean isChecked() { return caption.isChecked(); }
        @Override public void toggle() { setChecked(!isChecked()); }
    }

}
