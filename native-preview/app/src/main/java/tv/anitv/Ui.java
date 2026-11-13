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
    private Ui() {}
}
