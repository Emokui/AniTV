package tv.anitv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(28, 28, 32), PANEL = Color.rgb(36, 36, 41);
    static final int TEXT = Color.rgb(239, 239, 242), MUTED = Color.rgb(159, 159, 170);
    static final int ACCENT = Color.rgb(235, 113, 153);
    static int dp(Context c, float value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }
    static GradientDrawable shape(Context c, int color, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 2), stroke);
        return d;
    }
    static StateListDrawable focusBackground(Context c) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, shape(c, 0xff39343e, 7, ACCENT));
        d.addState(new int[]{android.R.attr.state_selected}, shape(c, 0xff302a31, 7, 0));
        d.addState(new int[]{}, shape(c, PANEL, 7, 0));
        return d;
    }
    static TextView text(Context c, String value, float size, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false); return v;
    }
    static TextView button(Context c, String title, View.OnClickListener action) {
        TextView v = text(c, title, 14, TEXT); v.setGravity(android.view.Gravity.CENTER);
        v.setMinHeight(dp(c, 38)); v.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        v.setFocusable(true); v.setClickable(true); v.setBackground(focusBackground(c));
        v.setFocusableInTouchMode(true);
        v.setOnClickListener(action); return v;
    }
    static void fullscreen(Activity a) {
        a.getWindow().setStatusBarColor(BG); a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    static void bold(TextView view) { view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); }

    static StateListDrawable formBackground(Context c){
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},shape(c,0xff39343e,7,ACCENT));
        GradientDrawable idle=shape(c,BG,7,0);idle.setStroke(dp(c,1),0x22ffffff);states.addState(new int[]{},idle);return states;
    }
    static void round(View view,float radius){
        final float pixels=dp(view.getContext(),radius);
        view.setOutlineProvider(new android.view.ViewOutlineProvider(){
            @Override public void getOutline(View v,android.graphics.Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),pixels);}
        });view.setClipToOutline(true);
    }
    static GradientDrawable gradient(int top,int bottom){return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});}
    static StateListDrawable emphasis(Context c,float radius){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},shape(c,0x33eb7199,radius,ACCENT));
        GradientDrawable active=shape(c,0x33eb7199,radius,0);active.setStroke(dp(c,1),0x66eb7199);
        states.addState(new int[]{android.R.attr.state_selected},active);
        states.addState(new int[]{},shape(c,PANEL,radius,0));return states;
    }
    static StateListDrawable railBackground(Context c){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused,android.R.attr.state_selected},railLayer(c,true));
        states.addState(new int[]{android.R.attr.state_selected},railLayer(c,false));
        states.addState(new int[]{android.R.attr.state_focused},shape(c,0xff39343e,7,ACCENT));
        states.addState(new int[]{},shape(c,PANEL,7,0));return states;
    }
    private static android.graphics.drawable.LayerDrawable railLayer(Context c,boolean focused){
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shape(c,0xff302a31,7,focused?ACCENT:0),shape(c,ACCENT,2,0)});
        layers.setLayerSize(1,dp(c,3),dp(c,20));layers.setLayerGravity(1,android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);layers.setLayerInsetLeft(1,dp(c,3));return layers;
    }
    static void reveal(View view,int duration,float offset){
        if(view.getVisibility()==View.VISIBLE)return;
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){view.setVisibility(View.VISIBLE);view.setAlpha(1);view.setTranslationY(0);return;}
        view.animate().cancel();view.setAlpha(0);view.setTranslationY(dp(view.getContext(),offset));view.setVisibility(View.VISIBLE);
        view.animate().alpha(1).translationY(0).setDuration(duration).start();
    }
    static void hide(View view){view.animate().cancel();view.setVisibility(View.GONE);view.setAlpha(1);view.setTranslationY(0);}
    static android.graphics.drawable.LayerDrawable timeline(Context c){
        android.graphics.drawable.Drawable track=shape(c,0x44ffffff,3,0);
        android.graphics.drawable.Drawable progress=new android.graphics.drawable.ClipDrawable(shape(c,ACCENT,3,0),android.view.Gravity.LEFT,android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{track,progress});
        layers.setId(0,android.R.id.background);layers.setId(1,android.R.id.progress);return layers;
    }
    private Ui() {}
}
