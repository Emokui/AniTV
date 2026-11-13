package tv.anitv;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

final class PosterAdapter extends ListAdapter<Show, PosterAdapter.Holder> {
    interface Events {
        void clicked(Show show);
        void focused(int position);
    }
    private final Activity activity;
    private final RequestManager images;
    private final Events events;
    private int cardWidth = 150;
    private boolean horizontal,selectionMode;private java.util.Set<Long> checked=java.util.Collections.emptySet();
    void selection(boolean active,java.util.Set<Long> ids){selectionMode=active;checked=new java.util.HashSet<>(ids);notifyItemRangeChanged(0,getItemCount(),"selection");}
    PosterAdapter(Activity activity, Events events) {
        super(new DiffUtil.ItemCallback<Show>() {
            @Override public boolean areItemsTheSame(@NonNull Show a, @NonNull Show b) { return a.id == b.id; }
            @Override public boolean areContentsTheSame(@NonNull Show a, @NonNull Show b) { return a.equals(b); }
        });
        this.activity = activity; this.events = events; images = Glide.with(activity);
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }
    static int titleHeight(Activity a){return Math.max(Ui.dp(a,24),Math.round(13*a.getResources().getDisplayMetrics().scaledDensity*1.4f));}
    static int subtitleHeight(Activity a){return Math.max(Ui.dp(a,15),Math.round(11*a.getResources().getDisplayMetrics().scaledDensity*1.4f));}
    static int cardHeight(Activity a,int width){return Math.round(width*1.43f)+Ui.dp(a,15)+titleHeight(a)+subtitleHeight(a);}
    void horizontal() { horizontal = true; }
    void size(int width) { cardWidth = width; }
    void pauseImages(boolean pause) { if (pause) images.pauseRequests(); else images.resumeRequests(); }
    @Override public long getItemId(int position) { return getItem(position).id; }
    @Override public int getItemViewType(int position){return getItem(position).id<0?1:0;}
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if(type==1){
            View spacer=new View(activity);spacer.setVisibility(View.INVISIBLE);spacer.setFocusable(false);spacer.setClickable(false);
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,cardHeight(activity,cardWidth));
            lp.setMargins(Ui.dp(activity,4),Ui.dp(activity,5),Ui.dp(activity,4),Ui.dp(activity,5));spacer.setLayoutParams(lp);
            return new Holder(spacer,null,null,null);
        }
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        int inset = Ui.dp(activity, 5); card.setPadding(inset, inset, inset, inset);
        card.setBackground(Ui.focusBackground(activity)); card.setFocusable(true); card.setClickable(true);
        card.setFocusableInTouchMode(true);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(horizontal ? cardWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                cardHeight(activity,cardWidth));
        params.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 5), Ui.dp(activity, 4), Ui.dp(activity, 5));
        card.setLayoutParams(params);
        ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(image,6);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView title = Ui.text(activity, "", 13, Ui.TEXT); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, titleHeight(activity));
        title.setGravity(android.view.Gravity.CENTER_VERTICAL); card.addView(title, titleParams);
        TextView subtitle = Ui.text(activity, "", 11, Ui.MUTED); subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(subtitle, new LinearLayout.LayoutParams(-1, subtitleHeight(activity)));
        return new Holder(card, image, title, subtitle);
    }
    private void bindSelection(Holder h,int position){Show show=getItem(position);if(show.id<0)return;h.title.setText((selectionMode?(checked.contains(show.id)?"✓ ":"○ "):"")+show.title);h.itemView.setSelected(selectionMode && checked.contains(show.id));}
    @Override public void onBindViewHolder(@NonNull Holder h,int position,@NonNull java.util.List<Object> payloads){if(!payloads.isEmpty() && payloads.contains("selection"))bindSelection(h,position);else onBindViewHolder(h,position);}
    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        Show show = getItem(position);if(show.id<0)return;bindSelection(h,position);h.subtitle.setText(show.subtitle);
        h.itemView.setContentDescription(show.title + "，" + show.subtitle);
        images.load(show.image.isEmpty() ? null : show.image).override(cardWidth, Math.round(cardWidth * 1.43f))
                .centerCrop().dontAnimate().placeholder(new PosterDrawable(show.id))
                .error(new PosterDrawable(show.id)).into(h.image);
        h.itemView.setOnClickListener(v -> events.clicked(show));
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(90).start();
            if (focused && h.getBindingAdapterPosition() != RecyclerView.NO_POSITION) events.focused(h.getBindingAdapterPosition());
        });
    }
    @Override public void onViewRecycled(@NonNull Holder h) {
        if(h.image!=null)images.clear(h.image); h.itemView.animate().cancel(); h.itemView.setScaleX(1); h.itemView.setScaleY(1);
        super.onViewRecycled(h);
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image; final TextView title, subtitle;
        Holder(View v, ImageView image, TextView title, TextView subtitle) {
            super(v); this.image = image; this.title = title; this.subtitle = subtitle;
        }
    }
}
