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
    private SourcePicker() {}
    static AlertDialog create(Activity activity, List<PlaySource> sources, int selected, Selection selection) {
        return create(activity,sources,selected,"",selection);
    }
    static AlertDialog create(Activity activity,List<PlaySource> sources,int selected,String currentResolution,Selection selection){
        String[] resolutions=new String[sources.size()];
        for(int i=0;i<sources.size();i++)resolutions[i]=i==selected && !currentResolution.isEmpty()?currentResolution:sources.get(i).resolutionLabel();
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
                row.resolution.setText(resolutions[position]);
                row.setSelected(position == cursor[0]);
                row.updateLatency(sources.get(position).caption, results[position]);
                return row;
            }
        };
        String current=selected>=0 && selected<sources.size()?"正在使用 · "+sources.get(selected).caption:null;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(PlayerDrawer.header(activity,"播放线路",sources.size()+" 条线路 · 首字节响应延迟",current))
                .setSingleChoiceItems(adapter, selected, (d, index) -> { selection.selected(index); d.dismiss(); }).create();
        PlayerDrawer.configureWindow(activity,dialog);
        dialog.setOnShowListener(ignored -> {
            PlayerDrawer.style(activity,dialog,selected);
            dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { probes.close(); }
            });
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
                // Let ListView handle arrows so only its viewport edge scrolls.
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        int position=list.getSelectedItemPosition();
                        if(position>=0)list.performItemClick(list.getChildAt(position-list.getFirstVisiblePosition()),position,position);
                    }
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
        void updateLatency(String title, SourceLatency.Result result) {
            int color = result.success() ? result.millis <= 800 ? 0xff79d9a1 : result.millis <= 2000 ? 0xffe7c66d : 0xfff19575
                    : result.label.equals("等待测速") || result.label.equals("测速中…") ? Ui.MUTED : 0xfff19575;
            latency.setText(result.success()?"响应  "+result.label:result.label);latency.setTextColor(color);
            setContentDescription(title + "，" + resolution.getText() + "，" + result.label+(isChecked()?"，当前使用":""));
        }
    }
}
