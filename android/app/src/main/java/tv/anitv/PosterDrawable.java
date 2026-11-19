package tv.anitv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Cheap fallback artwork and explicitly labelled local-demo cards. */
final class PosterDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long seed;
    private final int base, circle;
    PosterDrawable(long seed) {
        this.seed = seed; float hue = (seed * 37) % 360;
        base = Color.HSVToColor(new float[]{hue, .28f, .23f});
        circle = Color.HSVToColor(new float[]{hue, .25f, .29f});
    }
    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        paint.setColor(base); canvas.drawRect(b, paint);
        paint.setColor(circle);
        canvas.drawCircle(b.right * .88f, b.height() * .25f, b.width() * .65f, paint);
        paint.setColor(0x22ffffff);
        canvas.drawCircle(b.width() * .2f, b.height() * .9f, b.width() * .6f, paint);
        paint.setColor(0xffbcb9c4); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(b.width() * .18f); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("AniTV", b.exactCenterX(), b.exactCenterY(), paint);
        paint.setTextSize(b.width() * .07f); paint.setTypeface(android.graphics.Typeface.DEFAULT);
        canvas.drawText("ANIMATION LIBRARY", b.exactCenterX(), b.exactCenterY() + b.width() * .14f, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
