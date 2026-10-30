package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Fork lorenzoma97: how far into the episode you are, as a thin line at the foot of the shelf's play button
 * ("Riprendi · mancano 23 min"). Black on the white (focused) button, white on the glass one; nothing when the title
 * has no resume point.
 */
final class ResumeBar extends Drawable {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF line = new RectF();
    private final float density;
    private float progress = -1f;
    private boolean big, selected;
    private int alpha = 255;

    ResumeBar(float density) {
        this.density = density;
    }

    /** progress 0..1, or below 0 for none; big: the detail's larger button. */
    void setProgress(float progress, boolean big) {
        this.progress = progress;
        this.big = big;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        if (progress < 0) return;
        Rect b = getBounds();
        float h = (big ? 2.5f : 2f) * density, inset = (big ? 22 : 13) * density;
        float bottom = b.bottom - (big ? 6 : 4) * density;
        int color = selected ? 0xFF000000 : 0xFFFFFFFF;
        track.setColor((color & 0x00FFFFFF) | (0x38 * alpha / 255) << 24);
        fill.setColor((color & 0x00FFFFFF) | alpha << 24);
        line.set(b.left + inset, bottom - h, b.right - inset, bottom);
        canvas.drawRoundRect(line, h / 2, h / 2, track);
        line.right = line.left + line.width() * Math.max(0.04f, Math.min(1f, progress));
        canvas.drawRoundRect(line, h / 2, h / 2, fill);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean s = false;
        for (int x : state) s |= x == android.R.attr.state_focused;   // the button's white state (hero_button.xml)
        if (s == selected) return false;
        selected = s;
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        track.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
