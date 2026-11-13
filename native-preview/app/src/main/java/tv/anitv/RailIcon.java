package tv.anitv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class RailIcon extends View {
    private final int kind;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    RailIcon(Context c, int kind) { super(c); this.kind = kind; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.save(); c.scale(getWidth() / 24f, getHeight() / 24f);
        pen.setColor(isSelected() ? Ui.ACCENT : Ui.TEXT); pen.setStrokeWidth(1.7f);
        pen.setStyle(Paint.Style.STROKE); pen.setStrokeCap(Paint.Cap.ROUND); pen.setStrokeJoin(Paint.Join.ROUND);
        if (kind == 0) {
            Path p = path; p.reset(); p.moveTo(3, 10); p.lineTo(12, 3); p.lineTo(21, 10);
            p.moveTo(5, 9); p.lineTo(5, 21); p.lineTo(19, 21); p.lineTo(19, 9);
            p.moveTo(10, 21); p.lineTo(10, 14); p.lineTo(14, 14); p.lineTo(14, 21); c.drawPath(p, pen);
        } else if (kind == 1) {
            for (int x = 4; x <= 14; x += 10) for (int y = 4; y <= 14; y += 10) c.drawRoundRect(x, y, x + 6, y + 6, 1, 1, pen);
        } else if (kind == 2) {
            c.drawCircle(12,12,9,pen);c.drawLine(12,6,12,12,pen);c.drawLine(12,12,17,15,pen);
        } else if (kind == 3) {
            Path p = path; p.reset();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5; float r = i % 2 == 0 ? 10 : 4.5f;
                float x = 12 + (float) Math.cos(a) * r, y = 12 + (float) Math.sin(a) * r;
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            p.close(); c.drawPath(p, pen);
        } else if (kind == 4) {
            c.drawCircle(10, 10, 6.5f, pen); c.drawLine(15, 15, 21, 21, pen);
        } else {
            c.drawCircle(12, 12, 7, pen); c.drawCircle(12, 12, 2.5f, pen);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                c.drawLine(12 + (float) Math.cos(a) * 7, 12 + (float) Math.sin(a) * 7,
                        12 + (float) Math.cos(a) * 10, 12 + (float) Math.sin(a) * 10, pen);
            }
        }
        c.restore();
    }
    @Override public void setSelected(boolean selected) { super.setSelected(selected); invalidate(); }
}
