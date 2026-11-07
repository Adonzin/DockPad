package com.adonzin.dockpad;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small battery drawn like the menu's other line icons: an outline that fills to the level, with a bolt when charging. */
final class BatteryIcon extends Drawable {
    private final int level, color;
    private final boolean charging;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path bolt = new Path();

    BatteryIcon(int level, boolean charging, int color) {
        this.level = Math.max(0, Math.min(100, level));
        this.charging = charging;
        this.color = color;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float h = b.height(), w = b.width();
        // body (landscape) and the little cap on the right
        float stroke = Math.max(1.5f, h * 0.11f), capW = w * 0.08f;
        float left = b.left + stroke / 2, top = b.top + h * 0.2f + stroke / 2;
        float right = b.right - capW - stroke / 2, bottom = b.bottom - h * 0.2f - stroke / 2;
        float corner = (bottom - top) * 0.22f;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        r.set(left, top, right, bottom);
        c.drawRoundRect(r, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
        float capH = (bottom - top) * 0.45f, cy = (top + bottom) / 2;
        r.set(right + stroke / 2, cy - capH / 2, b.right, cy + capH / 2);
        c.drawRoundRect(r, capW / 2, capW / 2, paint);
        // the charge
        float pad = stroke * 1.1f;
        float innerL = left + pad, innerR = right - pad, innerT = top + pad, innerB = bottom - pad;
        float fillR = innerL + (innerR - innerL) * level / 100f;
        if (fillR > innerL) {
            int fill = level <= 15 && !charging ? 0xFFFF5252 : color;
            // when charging the fill is softer, so the bolt on top reads
            if (charging) fill = (fill & 0x00FFFFFF) | (Math.round(((fill >>> 24) & 0xFF) * 0.45f) << 24);
            paint.setColor(fill);
            r.set(innerL, innerT, fillR, innerB);
            c.drawRoundRect(r, corner * 0.5f, corner * 0.5f, paint);
        }
        if (charging) {
            float bx = (innerL + innerR) / 2, bh = (bottom - top) * 1.05f, bw = bh * 0.6f;
            bolt.reset();
            bolt.moveTo(bx + bw * 0.12f, cy - bh / 2);
            bolt.lineTo(bx - bw * 0.42f, cy + bh * 0.1f);
            bolt.lineTo(bx - bw * 0.02f, cy + bh * 0.1f);
            bolt.lineTo(bx - bw * 0.12f, cy + bh / 2);
            bolt.lineTo(bx + bw * 0.42f, cy - bh * 0.1f);
            bolt.lineTo(bx + bw * 0.02f, cy - bh * 0.1f);
            bolt.close();
            paint.setColor(color);
            c.drawPath(bolt, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
