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
    private static final int PAGE_SIZE=50;
    private EpisodePicker() {}
    static AlertDialog create(Activity activity, String title, List<Episode> episodes, int selected, Selection selection) {
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16));
        panel.setBackground(Ui.shape(activity, Ui.BG, 12, 0));
        TextView heading = Ui.text(activity, "选择剧集 · " + title, 20, Ui.TEXT); Ui.bold(heading);
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END); panel.addView(heading);
        TextView hint = Ui.text(activity, episodes.size() + " 集 · 方向键选择 · "+(episodes.size()>PAGE_SIZE?"首行上键 分段/最新 · ":"")+"确认播放 · 返回关闭", 11, Ui.MUTED);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.topMargin = Ui.dp(activity, 8); panel.addView(hint, hp);
        LinearLayout groups=null;
        if(episodes.size()>PAGE_SIZE){
            groups=new LinearLayout(activity);groups.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView strip=new HorizontalScrollView(activity);strip.setHorizontalScrollBarEnabled(false);
            strip.addView(groups);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=Ui.dp(activity,8);
            panel.addView(strip,gp);
        }
        RecyclerView grid = new RecyclerView(activity); grid.setContentDescription("剧集封面列表"); grid.setTag("episode-grid");
        int width = Math.min(Ui.dp(activity, 920), activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 48));
        int columns = width >= Ui.dp(activity, 700) ? 4 : 3;
        GridLayoutManager layout = new GridLayoutManager(activity, columns); grid.setLayoutManager(layout);
        grid.setItemAnimator(null); grid.setClipToPadding(false); grid.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 8));
        int height = Math.min(Ui.dp(activity, 370), activity.getResources().getDisplayMetrics().heightPixels - Ui.dp(activity, groups==null?170:225));
        panel.addView(grid, new LinearLayout.LayoutParams(-1, Math.max(Ui.dp(activity, 140), height)));
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(panel).create();
        Cards cards = new Cards(activity, grid, episodes, selected, columns, (width - Ui.dp(activity, 40)) / columns,
                index -> { selection.selected(index); dialog.dismiss(); });
        grid.setAdapter(cards);
        if(groups!=null){
            final LinearLayout tabs=groups;
            int pages=(episodes.size()+PAGE_SIZE-1)/PAGE_SIZE;
            Runnable mark=()->{for(int i=0;i<pages;i++)tabs.getChildAt(i+1).setSelected(i==cards.start/PAGE_SIZE);};
            for(int i=0;i<=pages;i++){
                final int page=i;
                int first=i*PAGE_SIZE+1,last=Math.min((i+1)*PAGE_SIZE,episodes.size());
                String label=i==pages?"跳到最新":first==last?Integer.toString(first):first+"–"+last;
                TextView button=Ui.button(activity,label,v->{
                    int target=page==pages?episodes.size()-1:page*PAGE_SIZE;
                    cards.page(target);mark.run();cards.focus(target-cards.start);
                });
                button.setTag(i==pages?"episode-latest":"episode-page-"+i);
                button.setTextSize(12);button.setMinHeight(Ui.dp(activity,36));
                button.setPadding(Ui.dp(activity,12),Ui.dp(activity,6),Ui.dp(activity,12),Ui.dp(activity,6));
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,Ui.dp(activity,40));bp.rightMargin=Ui.dp(activity,6);
                if(i==pages)tabs.addView(button,0,bp);else tabs.addView(button,bp);
                button.setOnKeyListener((v,key,event)->{
                    if(key!=KeyEvent.KEYCODE_DPAD_DOWN && key!=KeyEvent.KEYCODE_DPAD_LEFT && key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
                    if(event.getAction()==KeyEvent.ACTION_DOWN){
                        if(key==KeyEvent.KEYCODE_DPAD_DOWN)cards.focus(cards.selected>=cards.start && cards.selected<cards.end?cards.selected-cards.start:0);
                        else tabs.getChildAt(Math.max(0,Math.min(pages,(page==pages?0:page+1)+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1)))).requestFocus();
                    }return true;
                });
            }
            cards.up=()->tabs.getChildAt(0).requestFocus();mark.run();
        }
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
                window.setLayout(width, -2);
            }
            cards.focus(Math.max(0,selected)-cards.start);
        });
        return dialog;
    }
    private static final class Cards extends RecyclerView.Adapter<Card> {
        final Activity activity; final RecyclerView grid; final List<Episode> episodes; final int selected, columns, width;
        final Selection selection; int pending = -1,start,end;Runnable up;
        Cards(Activity activity, RecyclerView grid, List<Episode> episodes, int selected, int columns, int width, Selection selection) {
            this.activity = activity; this.grid = grid; this.episodes = episodes; this.selected = selected;
            this.columns = columns; this.width = width; this.selection = selection; setHasStableIds(true);
            start=episodes.size()>PAGE_SIZE?Math.max(0,selected)/PAGE_SIZE*PAGE_SIZE:0;end=Math.min(start+PAGE_SIZE,episodes.size());
        }
        @Override public long getItemId(int position) { return episodes.get(start+position).number; }
        @Override public int getItemCount() { return end-start; }
        void page(int absolute) {
            int next=absolute/PAGE_SIZE*PAGE_SIZE;
            pending=-1;
            if(start!=next){
                int oldCount=getItemCount();start=next;end=Math.min(start+PAGE_SIZE,episodes.size());
                grid.stopScroll();notifyItemRangeRemoved(0,oldCount);notifyItemRangeInserted(0,getItemCount());grid.scrollToPosition(0);
            }
        }
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
            FrameLayout cover = new FrameLayout(activity);
            card.addView(cover, new LinearLayout.LayoutParams(-1, Math.round((width - Ui.dp(activity, 18)) * 9f / 16f)));
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = Ui.text(activity, "", 10, Color.WHITE); badge.setPadding(Ui.dp(activity, 5), Ui.dp(activity, 3), Ui.dp(activity, 5), Ui.dp(activity, 3));
            badge.setBackgroundColor(0xc0000000); FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); cover.addView(badge, bp);
            TextView number = Ui.text(activity, "", 12, Ui.ACCENT);
            number.setGravity(Gravity.CENTER_VERTICAL); card.addView(number, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 26)));
            TextView name = Ui.text(activity, "", 12, Ui.TEXT); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 36)));
            return new Card(card, image, badge, number, name);
        }
        @Override public void onBindViewHolder(@NonNull Card holder, int position) {
            Episode episode = episodes.get(start+position);
            holder.itemView.setSelected(start+position == selected);
            holder.number.setText((episode.named ? episode.title : "第 " + episode.number + " 集") + (start+position == selected ? " · 上次选择" : ""));
            holder.name.setText(episode.named ? "确认播放" : episode.title);
            String duration = episode.duration > 0 ? String.format(Locale.ROOT, "%d:%02d", episode.duration / 60, episode.duration % 60) : "";
            String availability = episode.available == null ? "" : episode.available ? "有资源" : "暂无资源";
            holder.badge.setText(availability + (!availability.isEmpty() && !duration.isEmpty() ? " · " : "") + duration);
            holder.badge.setVisibility(availability.isEmpty() && duration.isEmpty() ? View.GONE : View.VISIBLE);
            holder.itemView.setContentDescription(episode.label());
            Glide.with(activity).load(episode.image.isEmpty() ? null : episode.image).override(width, Math.round(width * 9f / 16f))
                    .centerCrop().dontAnimate().placeholder(new PosterDrawable(episode.number)).error(new PosterDrawable(episode.number)).into(holder.image);
            holder.itemView.setOnClickListener(v -> { int index = holder.getBindingAdapterPosition(); if (index != RecyclerView.NO_POSITION) selection.selected(start+index); });
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
        @Override public void onViewRecycled(@NonNull Card holder) { Glide.with(activity).clear(holder.image); }
    }
    private static final class Card extends RecyclerView.ViewHolder {
        final ImageView image; final TextView badge, number, name;
        Card(View view, ImageView image, TextView badge, TextView number, TextView name) {
            super(view); this.image = image; this.badge = badge; this.number = number; this.name = name;
        }
    }
}
