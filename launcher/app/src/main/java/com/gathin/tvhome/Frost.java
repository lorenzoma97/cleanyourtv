package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Fork lorenzoma97: frosted glass for the panels over the cinematic shelf (clock, weather, the apps, the Scopri bar).
 * The TV cannot blur live, so the screen's picture (the art at its alpha over black, then the scrim) is drawn once
 * into a 512x288 copy, halved down to 128x72 and blurred there (three box passes each way, close to a Gaussian);
 * every panel paints its own part of that copy, stretched back over the screen, under its tint. A new picture
 * crossfades in with the art. (A 64x36 copy without the blur showed its blocks once stretched twenty times.)
 */
final class Frost {
    private static final int W = 128, H = 72, FIRST = 4, RADIUS = 2, PASSES = 3;   // 512x288 first, then two halvings

    private final List<View> hosts = new ArrayList<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] at = new int[2];
    private BitmapShader now, before;
    private float mix = 1f;
    private ValueAnimator fade;

    /** A view that paints frost (its background, or the app panel's band): redrawn when the picture changes. */
    void addHost(View v) {
        hosts.add(v);
    }

    /** What the screen shows behind the panels, blurred. Off the main thread is fine; null art gives the scrim on black. */
    static Bitmap blur(Bitmap art, boolean crop, float alpha, Bitmap scrim) {
        int w = W * FIRST, h = H * FIRST;
        Bitmap cur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(cur);
        c.drawColor(0xFF000000);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
            Matrix m = new Matrix();
            float sx = w / (float) art.getWidth(), sy = h / (float) art.getHeight();
            if (crop) {   // ImageView CENTER_CROP
                float s = Math.max(sx, sy);
                m.setScale(s, s);
                m.postTranslate((w - art.getWidth() * s) / 2f, (h - art.getHeight() * s) / 2f);
            } else {      // FIT_XY: the app's art, a few pixels stretched
                m.setScale(sx, sy);
            }
            p.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(art, m, p);
            p.setAlpha(255);
        }
        if (scrim != null) c.drawBitmap(scrim, null, new Rect(0, 0, w, h), p);
        while (cur.getWidth() > W) {
            Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            cur.recycle();
            cur = half;
        }
        int[] px = new int[W * H];
        cur.getPixels(px, 0, W, 0, 0, W, H);
        cur.recycle();
        for (int i = 0; i < PASSES; i++) boxBlur(px, W, H, RADIUS);
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }

    /** One box pass along the rows, then one along the columns, edges clamped. Opaque pixels: alpha stays 255. */
    static void boxBlur(int[] px, int w, int h, int radius) {
        int[] tmp = new int[px.length];
        boxLine(px, tmp, w, h, radius, 1, w);   // rows
        boxLine(tmp, px, h, w, radius, w, 1);   // columns
    }

    /** lines of n pixels, step apart inside a line and next apart between lines. */
    private static void boxLine(int[] src, int[] dst, int n, int lines, int radius, int step, int next) {
        int span = 2 * radius + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * next;
            for (int i = 0; i < n; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = src[base + Math.max(0, Math.min(n - 1, i + k)) * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                dst[base + i * step] = 0xFF000000 | (r / span) << 16 | (g / span) << 8 | (b / span);
            }
        }
    }

    /** Main thread. The new picture fades in over ms (0: at once). */
    void set(Bitmap blurred, long ms) {
        if (blurred == null) return;
        if (fade != null) fade.cancel();
        before = ms > 0 ? now : null;
        now = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (before == null) {
            mix = 1f;
            redraw();
            return;
        }
        mix = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
        fade.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            if (mix >= 1f) before = null;
            redraw();
        });
        fade.start();
    }

    private void redraw() {
        for (View v : hosts) v.invalidate();
    }

    /** Paints the blurred picture inside r (host coordinates, rounded by radius), lined up with the screen. */
    void draw(Canvas canvas, View host, RectF r, float radius) {
        if (now == null) return;
        View root = host.getRootView();
        host.getLocationInWindow(at);
        matrix.setScale(root.getWidth() / (float) W, root.getHeight() / (float) H);
        matrix.postTranslate(-at[0], -at[1]);
        if (before != null) paintWith(canvas, before, r, radius, 255);
        paintWith(canvas, now, r, radius, Math.round(255 * mix));
    }

    private void paintWith(Canvas canvas, BitmapShader shader, RectF r, float radius, int alpha) {
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(r, radius, radius, paint);
    }

    /** A panel background: the frost, a tint over it and a hairline edge (the clock, the weather, the Scopri bar). */
    static final class Panel extends Drawable {
        private final Frost frost;
        private final View host;
        private final float radius, density;
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Panel(Frost frost, View host, float radiusDp, int tint, int edge, float edgeDp) {
            this.frost = frost;
            this.host = host;
            this.density = host.getResources().getDisplayMetrics().density;
            this.radius = radiusDp * density;
            this.edge.setStyle(Paint.Style.STROKE);
            style(tint, edge, edgeDp);
            frost.addHost(host);
        }

        /** New tint and edge (the Scopri bar lights up on the last app row). */
        void style(int tint, int edge, float edgeDp) {
            this.tint.setColor(tint);
            this.edge.setColor(edge);
            // Whole pixels: a 1.33 px hairline (1dp on the 720p UI) was smeared over two.
            this.edge.setStrokeWidth(Math.max(1f, Math.round(edgeDp * density)));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            r.set(getBounds());
            frost.draw(canvas, host, r, radius);
            canvas.drawRoundRect(r, radius, radius, tint);
            float half = edge.getStrokeWidth() / 2f;
            r.inset(half, half);
            canvas.drawRoundRect(r, radius - half, radius - half, edge);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
