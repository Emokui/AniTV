package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Compact index options with a stable current selection and direct D-pad navigation. */
final class FilterPicker {
    static AlertDialog create(Activity activity,String title,String[] names,int selected,DialogInterface.OnClickListener apply){
        int columns=names.length>12?4:names.length<=4?2:3;
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,16),Ui.dp(activity,12));
        TextView heading=Ui.text(activity,title,19,Ui.TEXT);Ui.bold(heading);panel.addView(heading);
        TextView hint=Ui.text(activity,"方向键选择 · 确认应用 · 返回取消",11,Ui.MUTED);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(activity,6);hp.bottomMargin=Ui.dp(activity,10);panel.addView(hint,hp);
        GridLayout grid=new GridLayout(activity);grid.setColumnCount(columns);grid.setClipChildren(false);grid.setPadding(Ui.dp(activity,2),Ui.dp(activity,2),Ui.dp(activity,2),Ui.dp(activity,2));
        ScrollView scroll=new ScrollView(activity);scroll.setClipToPadding(false);scroll.addView(grid,new ScrollView.LayoutParams(-1,-2));
        int rowHeight=Ui.dp(activity,Math.max(42,activity.getResources().getConfiguration().fontScale*32));
        int rows=(names.length+columns-1)/columns;panel.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(rows*rowHeight+Ui.dp(activity,4),Math.round(activity.getResources().getDisplayMetrics().heightPixels*.48f))));
        AlertDialog dialog=new AlertDialog.Builder(activity).create();dialog.setView(panel,0,0,0,0);
        TextView[] buttons=new TextView[names.length];
        for(int i=0;i<names.length;i++){
            final int index=i;TextView button=Ui.button(activity,names[i],v->{apply.onClick(dialog,index);dialog.dismiss();});buttons[i]=button;button.setTag("filter-option-"+i);button.setTextSize(13);if(i==selected)button.setText("✓ "+names[i]);button.setMinHeight(0);button.setPadding(Ui.dp(activity,8),Ui.dp(activity,4),Ui.dp(activity,8),Ui.dp(activity,4));button.setSelected(i==selected);button.setBackground(Ui.emphasis(activity,17));
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(i/columns),GridLayout.spec(i%columns,1f));lp.width=0;lp.height=rowHeight-Ui.dp(activity,6);lp.setMargins(Ui.dp(activity,3),Ui.dp(activity,3),Ui.dp(activity,3),Ui.dp(activity,3));grid.addView(button,lp);
            button.setOnKeyListener((v,key,event)->{
                int target=index;
                if(key==KeyEvent.KEYCODE_DPAD_LEFT)target=index%columns>0?index-1:index;
                else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)target=index%columns<columns-1?Math.min(index+1,names.length-1):index;
                else if(key==KeyEvent.KEYCODE_DPAD_UP)target=index>=columns?index-columns:index;
                else if(key==KeyEvent.KEYCODE_DPAD_DOWN)target=index+columns<names.length?index+columns:(index/columns+1)*columns<names.length?names.length-1:index;
                else return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN)buttons[target].requestFocus();return true;
            });
        }
        Window window=dialog.getWindow();window.getDecorView().setTag("compact-filter");window.setWindowAnimations(R.style.AniDialogAnimation);window.setBackgroundDrawable(Ui.shape(activity,Ui.BG,12,0));window.setGravity(Gravity.CENTER);window.setLayout(Math.min(Ui.dp(activity,columns==4?420:360),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,48)),-2);android.view.WindowManager.LayoutParams params=window.getAttributes();params.dimAmount=.4f;window.setAttributes(params);window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        Runnable size=()->{window.getDecorView().setPadding(0,0,0,0);window.setLayout(Math.min(Ui.dp(activity,columns==4?420:360),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,48)),-2);};size.run();
        dialog.setOnShowListener(d->{size.run();scroll.post(()->{View target=buttons[Math.max(0,Math.min(selected,names.length-1))];target.requestFocus();scroll.scrollTo(0,Math.max(0,target.getTop()-scroll.getHeight()/2+target.getHeight()/2));});});return dialog;
    }
    private FilterPicker(){}
}
