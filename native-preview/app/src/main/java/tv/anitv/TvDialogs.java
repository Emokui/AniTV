package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared dark TV dialogs; showing never replaces validation or keyboard listeners. */
final class TvDialogs {
    private TvDialogs() {}
    static AlertDialog.Builder builder(Activity activity, String title) {
        TextView heading = Ui.text(activity,title,20,Ui.TEXT);Ui.bold(heading);
        heading.setPadding(Ui.dp(activity,20),Ui.dp(activity,20),Ui.dp(activity,20),Ui.dp(activity,14));
        return new AlertDialog.Builder(activity).setCustomTitle(heading);
    }
    static AlertDialog menu(Activity activity,String title,String[] items,DialogInterface.OnClickListener click) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,new java.util.ArrayList<>(java.util.Arrays.asList(items))) {
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                TextView row = recycled instanceof TextView ? (TextView)recycled : Ui.text(activity,"",14,Ui.TEXT);
                row.setText(getItem(position));row.setTextColor(Ui.TEXT);row.setTextSize(14);
                row.setGravity(Gravity.CENTER_VERTICAL);row.setMinHeight(Ui.dp(activity,54));
                row.setPadding(Ui.dp(activity,18),Ui.dp(activity,12),Ui.dp(activity,18),Ui.dp(activity,12));
                row.setBackgroundColor(Color.TRANSPARENT);return row;
            }
        };
        return builder(activity,title).setAdapter(adapter,click).create();
    }
    static EditText input(Activity activity,String hint,String value) {
        EditText input = new EditText(activity);input.setSingleLine();input.setText(value);input.setHint(hint);
        input.setTextSize(16);input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.MUTED);
        input.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,14),Ui.dp(activity,12));
        input.setMinHeight(Ui.dp(activity,52));input.setBackground(Ui.focusBackground(activity));return input;
    }
    static View field(Activity activity,EditText input) {
        LinearLayout panel=new LinearLayout(activity);
        panel.setPadding(Ui.dp(activity,20),Ui.dp(activity,6),Ui.dp(activity,20),Ui.dp(activity,14));
        panel.addView(input,new LinearLayout.LayoutParams(-1,-2));return panel;
    }
    static void show(Activity activity,AlertDialog dialog,boolean side) {
        configureWindow(activity,dialog,side);dialog.show();style(activity,dialog,side);
    }
    static void configureWindow(Activity activity,AlertDialog dialog,boolean side){
        Window window=dialog.getWindow();
        if(window!=null) {
            window.setWindowAnimations(side?R.style.AniDrawerAnimation:R.style.AniDialogAnimation);
            window.setBackgroundDrawable(Ui.shape(activity,Ui.BG,side?0:12,0));
            window.getDecorView().setPadding(0,0,0,0);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
            int screen=activity.getResources().getDisplayMetrics().widthPixels;
            window.setGravity(side?Gravity.RIGHT|Gravity.TOP:Gravity.CENTER);
            window.setLayout(side?Math.round(screen*.25f):Math.min(Ui.dp(activity,640),screen-Ui.dp(activity,64)),side?-1:-2);
            WindowManager.LayoutParams params=window.getAttributes();params.dimAmount=.4f;window.setAttributes(params);
        }
    }
    static void style(Activity activity,AlertDialog dialog,boolean side) {
        configureWindow(activity,dialog,side);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button button=dialog.getButton(which);if(button==null)continue;
            button.setTextColor(Ui.TEXT);button.setTextSize(13);button.setAllCaps(false);
            button.setBackground(Ui.focusBackground(activity));button.setMinHeight(Ui.dp(activity,40));
            button.setPadding(Ui.dp(activity,18),Ui.dp(activity,8),Ui.dp(activity,18),Ui.dp(activity,8));
        }
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null){message.setTextColor(Ui.MUTED);message.setTextSize(14);message.setLineSpacing(Ui.dp(activity,5),1);}
        if(dialog.getListView()!=null){dialog.getListView().setSelector(Ui.focusBackground(activity));dialog.getListView().setDividerHeight(Ui.dp(activity,4));dialog.getListView().setPadding(Ui.dp(activity,8),0,Ui.dp(activity,8),Ui.dp(activity,12));dialog.getListView().setSelection(0);dialog.getListView().requestFocus();}
    }
}
