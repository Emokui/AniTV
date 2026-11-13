package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** Lightweight shared presentation for the two playback drawers. */
final class PlayerDrawer {
    interface Selection { void selected(int index); }
    private PlayerDrawer() {}
    static View header(Activity activity,String title,String subtitle,String current){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(activity,18),Ui.dp(activity,24),Ui.dp(activity,18),Ui.dp(activity,12));
        TextView heading=Ui.text(activity,title,22,Ui.TEXT);Ui.bold(heading);panel.addView(heading);
        TextView description=Ui.text(activity,subtitle,11,Ui.MUTED);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=Ui.dp(activity,7);panel.addView(description,dp);
        if(current!=null){
            TextView summary=Ui.text(activity,current,12,Ui.ACCENT);summary.setMaxLines(2);summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(Ui.dp(activity,10),Ui.dp(activity,10),Ui.dp(activity,10),Ui.dp(activity,10));
            summary.setBackground(Ui.shape(activity,0xff302730,8,0));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(activity,14);panel.addView(summary,sp);
        }
        TextView hint=Ui.text(activity,"↑↓ 选择   确认应用   返回关闭",10,Ui.MUTED);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(activity,12);panel.addView(hint,hp);
        return panel;
    }
    static void configureWindow(Activity activity,AlertDialog dialog){
        Window window=dialog.getWindow();
        if(window!=null){
            window.setWindowAnimations(R.style.AniDrawerAnimation);
            window.setBackgroundDrawable(new ColorDrawable(Ui.BG));
            window.setGravity(Gravity.RIGHT|Gravity.TOP);
            window.setLayout(Math.round(activity.getResources().getDisplayMetrics().widthPixels*.25f),WindowManager.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams params=window.getAttributes();params.x=0;params.y=0;params.dimAmount=.32f;window.setAttributes(params);
            window.getDecorView().setPadding(0,0,0,0);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }
    static void style(Activity activity,AlertDialog dialog,int selected){
        configureWindow(activity,dialog);
        ListView list=dialog.getListView();
        list.setPadding(Ui.dp(activity,12),Ui.dp(activity,4),Ui.dp(activity,12),Ui.dp(activity,16));list.setClipToPadding(false);
        list.setDivider(new ColorDrawable(android.graphics.Color.TRANSPARENT));list.setDividerHeight(Ui.dp(activity,6));
        list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));list.setVerticalScrollBarEnabled(false);
        list.setSelection(selected);list.requestFocus();
    }
    static void highlight(ListView list,int position){
        for(int i=0;i<list.getChildCount();i++)list.getChildAt(i).setSelected(list.getFirstVisiblePosition()+i==position);
    }
    static AlertDialog settings(Activity activity,float speed,int aspect,int initial,Selection apply,Selection cursor){
        String[] titles={"1.0×","1.25×","1.5×","2.0×","适应","裁剪","拉伸","上一集","下一集"};
        float[] speeds={1f,1.25f,1.5f,2f};
        int[][] rows={{7,8},{0,1},{2,3},{4,5,6}};
        TextView[] buttons=new TextView[titles.length];
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(activity,16),Ui.dp(activity,24),Ui.dp(activity,16),Ui.dp(activity,16));
        TextView heading=Ui.text(activity,"播放设置",20,Ui.TEXT);Ui.bold(heading);panel.addView(heading);
        String current=(speed==1f?"标准速度":speed+"× 倍速")+" · "+new String[]{"适应画面","裁剪铺满","拉伸全屏"}[aspect];
        TextView summary=Ui.text(activity,current,11,Ui.MUTED);summary.setMaxLines(1);summary.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=Ui.dp(activity,6);panel.addView(summary,sp);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.setFillViewport(true);scroll.addView(panel);
        AlertDialog dialog=new AlertDialog.Builder(activity).create();dialog.setView(scroll,0,0,0,0);
        for(int r=0;r<rows.length;r++){
            if(r==1 || r==3){TextView label=Ui.text(activity,r==1?"播放速度":"画面比例",12,Ui.MUTED);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=Ui.dp(activity,20);lp.bottomMargin=Ui.dp(activity,8);panel.addView(label,lp);}
            LinearLayout line=new LinearLayout(activity);line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=Ui.dp(activity,r==0?18:r==2?8:0);panel.addView(line,lp);
            for(int c=0;c<rows[r].length;c++){
                final int index=rows[r][c],row=r,column=c;
                TextView button=Ui.button(activity,titles[index],v->{apply.selected(index);dialog.dismiss();});buttons[index]=button;
                button.setTag("playback-option-"+index);button.setTextSize(13);button.setMaxLines(1);button.setEllipsize(TextUtils.TruncateAt.END);button.setPadding(Ui.dp(activity,5),Ui.dp(activity,8),Ui.dp(activity,5),Ui.dp(activity,8));
                android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();
                background.addState(new int[]{android.R.attr.state_focused},Ui.shape(activity,Ui.PANEL,7,Ui.ACCENT));background.addState(new int[]{},Ui.shape(activity,Ui.PANEL,7,0));button.setBackground(background);
                boolean active=index<4?speed==speeds[index]:index<7 && aspect==index-4;button.setSelected(active);button.setTextColor(active?Ui.ACCENT:Ui.TEXT);
                if(active){android.graphics.drawable.GradientDrawable dot=Ui.shape(activity,Ui.ACCENT,2,0);android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{background,dot});layers.setLayerSize(1,Ui.dp(activity,4),Ui.dp(activity,4));layers.setLayerGravity(1,Gravity.TOP|Gravity.RIGHT);layers.setLayerInsetRight(1,Ui.dp(activity,8));layers.setLayerInsetTop(1,Ui.dp(activity,6));button.setBackground(layers);}
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);if(c>0)bp.leftMargin=Ui.dp(activity,6);line.addView(button,bp);
                button.setOnFocusChangeListener((v,focused)->{if(focused)cursor.selected(index);});
                button.setOnKeyListener((v,key,event)->{
                    int target=index;
                    if(key==android.view.KeyEvent.KEYCODE_DPAD_LEFT)target=rows[row][Math.max(0,column-1)];
                    else if(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)target=rows[row][Math.min(rows[row].length-1,column+1)];
                    else if(key==android.view.KeyEvent.KEYCODE_DPAD_UP || key==android.view.KeyEvent.KEYCODE_DPAD_DOWN){int next=Math.max(0,Math.min(rows.length-1,row+(key==android.view.KeyEvent.KEYCODE_DPAD_DOWN?1:-1)));int mapped=Math.min(rows[next].length-1,(int)((column+.5f)*rows[next].length/rows[row].length));target=rows[next][mapped];}
                    else return false;
                    if(event.getAction()==android.view.KeyEvent.ACTION_DOWN)buttons[target].requestFocus();return true;
                });
            }
        }
        TextView hint=Ui.text(activity,"方向键选择 · 确认应用 · 返回关闭",10,Ui.MUTED);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=Ui.dp(activity,18);panel.addView(hint,hp);
        configureWindow(activity,dialog);
        dialog.setOnShowListener(d->{configureWindow(activity,dialog);buttons[Math.max(0,Math.min(buttons.length-1,initial))].requestFocus();});
        return dialog;
    }
    static class Option extends LinearLayout implements Checkable {
        final TextView section,title,detail,badge;
        private boolean checked;
        Option(Activity activity){
            super(activity);setOrientation(VERTICAL);setGravity(Gravity.CENTER_VERTICAL);setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setPadding(Ui.dp(activity,12),Ui.dp(activity,10),Ui.dp(activity,12),Ui.dp(activity,10));
            setMinimumHeight(Ui.dp(activity,48));
            android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();
            background.addState(new int[]{android.R.attr.state_focused},focusShape(activity));
            background.addState(new int[]{android.R.attr.state_selected},focusShape(activity));
            background.addState(new int[]{},Ui.shape(activity,Ui.PANEL,8,0));setBackground(background);
            section=Ui.text(activity,"",10,Ui.MUTED);section.setVisibility(GONE);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.bottomMargin=Ui.dp(activity,6);addView(section,sp);
            LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);
            title=Ui.text(activity,"",14,Ui.TEXT);Ui.bold(title);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            badge=Ui.text(activity,"当前",10,Ui.ACCENT);badge.setPadding(Ui.dp(activity,6),Ui.dp(activity,3),Ui.dp(activity,6),Ui.dp(activity,3));
            badge.setBackground(Ui.shape(activity,0xff49313f,5,0));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=Ui.dp(activity,8);line.addView(badge,bp);addView(line,new LinearLayout.LayoutParams(-1,-2));
            detail=Ui.text(activity,"",11,Ui.MUTED);
            LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=Ui.dp(activity,6);addView(detail,dp);
        }
        private static android.graphics.drawable.GradientDrawable focusShape(Activity activity){
            return Ui.shape(activity,Ui.PANEL,7,Ui.ACCENT);
        }
        @Override public void setChecked(boolean value){checked=value;badge.setVisibility(value?VISIBLE:GONE);}
        @Override public boolean isChecked(){return checked;}
        @Override public void toggle(){setChecked(!checked);}
    }
}
