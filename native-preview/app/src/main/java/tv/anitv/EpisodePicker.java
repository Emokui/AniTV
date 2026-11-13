package tv.anitv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.List;
import java.util.Locale;

/** Shared TV episode chooser. Canonical episode numbers remain the playback identity. */
final class EpisodePicker {
    interface Selection { void selected(int index); }
    private EpisodePicker() {}
    static AlertDialog create(Activity activity, String title, List<Episode> episodes, int selected, Selection selection) {
        return create(activity,title,"title:"+title,episodes,selected,selection);
    }
    static AlertDialog create(Activity activity,String title,String seriesKey,List<Episode> episodes,int selected,Selection selection){
        return create(activity,title,seriesKey,episodes,selected,false,selection);
    }
    static AlertDialog create(Activity activity,String title,String seriesKey,List<Episode> episodes,int selected,boolean focusCurrent,Selection selection){
        return create(activity,title,seriesKey,episodes,selected,focusCurrent,"",selection);
    }
    static AlertDialog create(Activity activity,String title,String seriesKey,List<Episode> episodes,int selected,boolean focusCurrent,String selectedLabel,Selection selection){
        if(episodes.isEmpty())return empty(activity,title,null);
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16));
        panel.setBackground(Ui.shape(activity, Ui.BG, 12, 0));
        TextView heading = Ui.text(activity, "选择剧集 · " + title, 20, Ui.TEXT); Ui.bold(heading);
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END); panel.addView(heading);
        TextView hint = Ui.text(activity, episodes.size() + " 集 · 方向键选择 · "+(episodes.size()>12?(episodes.size()>(episodes.size()<=48?12:50)?"首行上键 分段/排序 · ":"首行上键 排序 · "):"")+"确认播放 · 返回关闭", 11, Ui.MUTED);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = Ui.dp(activity, 8); panel.addView(hint, hp);
        LinearLayout groups=null,groupRow=null;HorizontalScrollView strip=null;
        if(episodes.size()>12){
            groups=new LinearLayout(activity);groups.setOrientation(LinearLayout.HORIZONTAL);
            strip=new HorizontalScrollView(activity);strip.setTag("episode-segments");strip.setHorizontalScrollBarEnabled(false);
            strip.addView(groups);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=Ui.dp(activity,8);
            groupRow=new LinearLayout(activity);groupRow.setGravity(Gravity.CENTER_VERTICAL);groupRow.addView(strip,new LinearLayout.LayoutParams(0,-2,1));panel.addView(groupRow,gp);
        }
        RecyclerView grid = new RecyclerView(activity); grid.setContentDescription("剧集封面列表"); grid.setTag("episode-grid");
        int width = Math.min(Ui.dp(activity, 920), activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 48));
        int columns = width >= Ui.dp(activity, 700) ? 4 : 3;
        GridLayoutManager layout = new GridLayoutManager(activity, columns); grid.setLayoutManager(layout);
        grid.setItemAnimator(null);grid.setClipChildren(false); grid.setClipToPadding(false); grid.setPadding(Ui.dp(activity,5), Ui.dp(activity, 10), Ui.dp(activity,5), Ui.dp(activity, 8));
        panel.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog = new AlertDialog.Builder(activity).create();dialog.setView(panel,0,0,0,0);
        Cards cards = new Cards(activity, grid, episodes, selected, seriesKey, columns, (width - Ui.dp(activity, 50)) / columns,focusCurrent,selectedLabel,
                index -> { selection.selected(index); dialog.dismiss(); });
        grid.setAdapter(cards);
        if(groups!=null){
            final LinearLayout tabs=groups;final HorizontalScrollView scroller=strip;
            int pages=episodes.size()>cards.pageSize?(episodes.size()+cards.pageSize-1)/cards.pageSize:0;
            TextView order=Ui.button(activity,cards.descending?"倒序 ↓":"正序 ↑",null);order.setTag("episode-order");groupRow.addView(order,0);
            Runnable mark=()->{
                order.setText(cards.descending?"倒序 ↓":"正序 ↑");
                for(int i=0;i<pages;i++){int first=i*cards.pageSize,last=Math.min((i+1)*cards.pageSize,episodes.size())-1;TextView tab=(TextView)tabs.getChildAt(i);int a=cards.rangeNumber(first),b=cards.rangeNumber(last);tab.setText(a==b?Integer.toString(a):a+"–"+b);android.graphics.drawable.GradientDrawable dot=Ui.shape(activity,Ui.ACCENT,3,0);dot.setBounds(0,0,Ui.dp(activity,6),Ui.dp(activity,6));tab.setCompoundDrawablePadding(Ui.dp(activity,5));tab.setCompoundDrawables(i==cards.start/cards.pageSize?dot:null,null,null,null);tab.setSelected(i==cards.start/cards.pageSize);}
                scroller.post(()->{int index=cards.start/cards.pageSize;if(index<tabs.getChildCount())scroller.smoothScrollTo(tabs.getChildAt(index).getLeft(),0);});
            };
            order.setOnClickListener(v->{cards.reverse();mark.run();order.requestFocus();});
            order.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_DOWN && key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==KeyEvent.KEYCODE_DPAD_DOWN)cards.focus(cards.display(cards.selected)>=cards.start && cards.display(cards.selected)<cards.end?cards.display(cards.selected)-cards.start:0);else if(tabs.getChildCount()>0)tabs.getChildAt(Math.min(tabs.getChildCount()-1,cards.start/cards.pageSize)).requestFocus();}return true;});
            for(int i=0;i<pages;i++){final int page=i;TextView button=Ui.button(activity,"",v->{cards.page(page*cards.pageSize);mark.run();cards.focus(0);});button.setTag("episode-page-"+i);button.setBackground(Ui.emphasis(activity,16));button.setTextSize(12);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.rightMargin=Ui.dp(activity,6);tabs.addView(button,bp);
                button.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_DOWN && key!=KeyEvent.KEYCODE_DPAD_LEFT && key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==KeyEvent.KEYCODE_DPAD_DOWN)cards.focus(0);else if(key==KeyEvent.KEYCODE_DPAD_LEFT && page==0)order.requestFocus();else tabs.getChildAt(Math.max(0,Math.min(tabs.getChildCount()-1,page+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1)))).requestFocus();}return true;});
            }
            cards.up=()->{if(tabs.getChildCount()>0)tabs.getChildAt(Math.min(tabs.getChildCount()-1,cards.start/cards.pageSize)).requestFocus();else order.requestFocus();};mark.run();
        }
        cards.fit(availableHeight(activity,panel,grid,width));cards.resize();
        configure(activity,dialog,width);
        dialog.setOnShowListener(ignored -> {
            configure(activity,dialog,width);
            View order=panel.findViewWithTag("episode-order");
            if(order!=null && !focusCurrent)order.requestFocus();else cards.focus(cards.display(Math.max(0,selected))-cards.start);
        });
        return dialog;
    }
    static AlertDialog empty(Activity activity,String title,Runnable retry){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(Ui.dp(activity,20),Ui.dp(activity,16),Ui.dp(activity,20),Ui.dp(activity,16));panel.setBackground(Ui.shape(activity,Ui.BG,12,0));
        TextView heading=Ui.text(activity,"选择剧集 · "+title,20,Ui.TEXT);heading.setMaxLines(1);heading.setEllipsize(TextUtils.TruncateAt.END);Ui.bold(heading);panel.addView(heading);
        ImageView cover=new ImageView(activity);cover.setImageDrawable(new PosterDrawable(0));cover.setScaleType(ImageView.ScaleType.CENTER_CROP);Ui.round(cover,5);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(Ui.dp(activity,230),Ui.dp(activity,130));cp.gravity=Gravity.CENTER_HORIZONTAL;cp.topMargin=Ui.dp(activity,16);panel.addView(cover,cp);
        TextView message=Ui.text(activity,"暂无可播放剧集",16,Ui.MUTED);message.setGravity(Gravity.CENTER);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=Ui.dp(activity,12);panel.addView(message,mp);
        AlertDialog dialog=new AlertDialog.Builder(activity).create();dialog.setView(panel,0,0,0,0);LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.topMargin=Ui.dp(activity,12);panel.addView(actions,ap);
        TextView again=retry==null?null:Ui.button(activity,"重新读取",v->{dialog.dismiss();retry.run();});if(again!=null){again.setTag("episode-retry");actions.addView(again);}
        TextView close=Ui.button(activity,"返回",v->dialog.dismiss());LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(-2,-2);if(again!=null)closeParams.leftMargin=Ui.dp(activity,10);actions.addView(close,closeParams);
        int width=Math.min(Ui.dp(activity,500),activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,48));configure(activity,dialog,width);
        dialog.setOnShowListener(d->{configure(activity,dialog,width);(again==null?close:again).requestFocus();});return dialog;
    }
    private static int availableHeight(Activity activity,LinearLayout panel,View grid,int width){
        int height=activity.getResources().getDisplayMetrics().heightPixels-Ui.dp(activity,32)-panel.getPaddingTop()-panel.getPaddingBottom();
        int contentWidth=width-panel.getPaddingLeft()-panel.getPaddingRight();
        for(int i=0;i<panel.getChildCount();i++){View child=panel.getChildAt(i);if(child==grid)continue;
            child.measure(View.MeasureSpec.makeMeasureSpec(contentWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)child.getLayoutParams();height-=child.getMeasuredHeight()+lp.topMargin+lp.bottomMargin;
        }
        return Math.max(Ui.dp(activity,80),height);
    }
    private static void configure(Activity activity,AlertDialog dialog,int width){
        Window window=dialog.getWindow();if(window==null)return;
        window.getDecorView().setPadding(0,0,0,0);
        window.setWindowAnimations(R.style.AniDialogAnimation);window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());window.setLayout(width,-2);
        android.view.WindowManager.LayoutParams params=window.getAttributes();params.dimAmount=.4f;window.setAttributes(params);
    }
    private static final class Cards extends RecyclerView.Adapter<Card> {
        final Activity activity; final RecyclerView grid; final List<Episode> episodes; final int selected, columns, width, pageSize; final String orderKey; boolean descending;
        int coverHeight,numberHeight,nameHeight,rowHeight,maxHeight,maxRows=2,nameLines=2;final String selectedLabel;
        final Selection selection; int pending = -1,start,end;Runnable up;
        Cards(Activity activity, RecyclerView grid, List<Episode> episodes, int selected, String seriesKey, int columns, int width,boolean focusCurrent,String selectedLabel, Selection selection) {
            this.selectedLabel=selectedLabel;
            this.activity = activity; this.grid = grid; this.episodes = episodes; this.selected = selected;
            orderKey="episode-order:"+new Library(activity).endpoint()+"|"+seriesKey;
            descending=episodes.size()>12 && new Library(activity).prefs.getBoolean(orderKey,false);
            pageSize=episodes.size()<=48?12:50;
            this.columns = columns; this.width = width; this.selection = selection; setHasStableIds(true);
            start=focusCurrent && episodes.size()>pageSize?display(Math.max(0,selected))/pageSize*pageSize:0;end=Math.min(start+pageSize,episodes.size());
        }
        @Override public long getItemId(int position) { return episodes.get(original(start+position)).number; }
        @Override public int getItemCount() { return end-start; }
        int rangeNumber(int display){int index=original(display),number=episodes.get(index).number;return number>=1000000000?index+1:number;}
        int original(int display){return descending?episodes.size()-1-display:display;}
        int display(int original){return descending?episodes.size()-1-original:original;}
        void reverse(){
            descending=!descending;new Library(activity).prefs.edit().putBoolean(orderKey,descending).apply();
            pending=-1;start=0;end=Math.min(pageSize,episodes.size());grid.stopScroll();notifyDataSetChanged();grid.scrollToPosition(0);resize();
        }
        void page(int absolute) {
            int next=absolute/pageSize*pageSize;
            pending=-1;
            if(start!=next){
                int oldCount=getItemCount();start=next;end=Math.min(start+pageSize,episodes.size());
                grid.stopScroll();notifyItemRangeRemoved(0,oldCount);notifyItemRangeInserted(0,getItemCount());grid.scrollToPosition(0);resize();
            }
        }
        void fit(int available){
            maxHeight=available;float scaled=activity.getResources().getDisplayMetrics().scaledDensity;
            numberHeight=Math.max(Ui.dp(activity,26),Math.round(12*scaled*1.4f)+Ui.dp(activity,4));
            nameHeight=Math.max(Ui.dp(activity,36),Math.round(12*scaled*2.6f));
            maxRows=Math.min(2,(Math.min(episodes.size(),pageSize)+columns-1)/columns);
            int budget=(available-Ui.dp(activity,18))/maxRows;
            int cover=budget-numberHeight-nameHeight-Ui.dp(activity,18);
            if(cover<Ui.dp(activity,45) && maxRows==2){nameLines=1;nameHeight=Math.round(12*scaled*1.4f);cover=budget-numberHeight-nameHeight-Ui.dp(activity,18);}
            if(cover<Ui.dp(activity,45) && maxRows==2){maxRows=1;cover=available-Ui.dp(activity,18)-numberHeight-nameHeight-Ui.dp(activity,18);}
            coverHeight=Math.max(Ui.dp(activity,24),Math.min(Math.round((width-Ui.dp(activity,18))*9f/16f),cover));
            rowHeight=coverHeight+numberHeight+nameHeight+Ui.dp(activity,18);
        }
        void resize(){int rows=(getItemCount()+columns-1)/columns;android.view.ViewGroup.LayoutParams lp=grid.getLayoutParams();lp.height=Math.min(maxHeight,Math.max(1,Math.min(rows,maxRows))*rowHeight+Ui.dp(activity,18));grid.setLayoutParams(lp);}
        void focus(int position) {
            if (position < 0 || position >= getItemCount()) return;
            pending = position;
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null && !grid.hasPendingAdapterUpdates()) { holder.itemView.requestFocus(); pending = -1; }
            else { grid.scrollToPosition(position); grid.post(() -> {
                RecyclerView.ViewHolder target = grid.findViewHolderForAdapterPosition(position);
                if (target != null && pending == position) { target.itemView.requestFocus(); pending = -1; }
            }); }
        }
        @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(activity, 5), Ui.dp(activity, 5), Ui.dp(activity, 5), Ui.dp(activity, 5));
            card.setFocusable(true); card.setFocusableInTouchMode(true); card.setClickable(true);
            card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); card.setBackground(Ui.focusBackground(activity));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
            params.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4)); card.setLayoutParams(params);
            card.setOnFocusChangeListener((v,focused)->{v.animate().cancel();v.animate().scaleX(focused?1.03f:1f).scaleY(focused?1.03f:1f).setDuration(90).start();});
            FrameLayout cover = new FrameLayout(activity);Ui.round(cover,5);
            card.addView(cover, new LinearLayout.LayoutParams(-1, coverHeight));
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = Ui.text(activity, "", 10, Color.WHITE); badge.setPadding(Ui.dp(activity, 5), Ui.dp(activity, 3), Ui.dp(activity, 5), Ui.dp(activity, 3));
            badge.setBackgroundColor(0xc0000000); FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); cover.addView(badge, bp);
            TextView number = Ui.text(activity, "", 12, Ui.ACCENT);
            number.setMaxLines(1);number.setEllipsize(TextUtils.TruncateAt.END);number.setGravity(Gravity.CENTER_VERTICAL); card.addView(number, new LinearLayout.LayoutParams(-1, numberHeight));
            TextView name = Ui.text(activity, "", 12, Ui.TEXT); name.setMaxLines(nameLines); name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(-1, nameHeight));
            return new Card(card, image, badge, number, name);
        }
        @Override public void onBindViewHolder(@NonNull Card holder, int position) {
            Episode episode = episodes.get(original(start+position));
            holder.itemView.setSelected(original(start+position) == selected);
            holder.number.setText((episode.named ? episode.title : "第 " + episode.number + " 集") + (original(start+position) == selected && !selectedLabel.isEmpty() ? " · "+selectedLabel : ""));
            holder.name.setText(episode.named?"确认播放":episode.title);
            String duration = episode.duration > 0 ? String.format(Locale.ROOT, "%d:%02d", episode.duration / 60, episode.duration % 60) : "";
            String availability = episode.available == null ? "" : episode.available ? "有资源" : "暂无资源";
            holder.badge.setText(availability + (!availability.isEmpty() && !duration.isEmpty() ? " · " : "") + duration);
            holder.badge.setVisibility(availability.isEmpty() && duration.isEmpty() ? View.GONE : View.VISIBLE);
            holder.itemView.setContentDescription(episode.label());
            if(episode.image.trim().isEmpty()){Glide.with(activity).clear(holder.image);holder.image.setImageDrawable(new PosterDrawable(episode.number));}
            else Glide.with(activity).load(episode.image).override(width,coverHeight)
                    .centerCrop().dontAnimate().placeholder(new PosterDrawable(episode.number)).error(new PosterDrawable(episode.number)).into(holder.image);
            holder.itemView.setOnClickListener(v -> { int index = holder.getBindingAdapterPosition(); if(index!=RecyclerView.NO_POSITION)selection.selected(original(start+index)); });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition(); if (index == RecyclerView.NO_POSITION) return false;
                int target;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) target = index % columns > 0 ? index - 1 : index;
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) target = index % columns < columns - 1 && index + 1 < getItemCount() ? index + 1 : index;
                else if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if(index<columns && up!=null){if(event.getAction()==KeyEvent.ACTION_DOWN){pending=-1;up.run();}return true;}
                    target = index >= columns ? index - columns : index;
                }
                else if (key == KeyEvent.KEYCODE_DPAD_DOWN) target = (index / columns + 1) * columns < getItemCount()
                        ? Math.min(index + columns, getItemCount() - 1) : index;
                else return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) focus(target);
                return true;
            });
        }
        @Override public void onViewAttachedToWindow(@NonNull Card holder) {
            final int target = pending;
            if (target >= 0 && holder.getBindingAdapterPosition() == target) holder.itemView.post(() -> {
                if (pending == target && holder.getBindingAdapterPosition() == target && holder.itemView.isAttachedToWindow()) {
                    holder.itemView.requestFocus(); pending = -1;
                }
            });
        }
        @Override public void onViewRecycled(@NonNull Card holder) { holder.itemView.animate().cancel();holder.itemView.setScaleX(1);holder.itemView.setScaleY(1);Glide.with(activity).clear(holder.image); }
    }
    private static final class Card extends RecyclerView.ViewHolder {
        final ImageView image; final TextView badge, number, name;
        Card(View view, ImageView image, TextView badge, TextView number, TextView name) {
            super(view); this.image = image; this.badge = badge; this.number = number; this.name = name;
        }
    }
}
