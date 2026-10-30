package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * Layers 1 and 2: the Top Shelf art (one composed, screen-size bitmap per show)
 * and the scrim. Holds at most two large bitmaps. A new image interrupting a
 * crossfade first flattens the current blend into the incoming bitmap, so the
 * next fade starts from exactly what is on screen.
 */
final class ArtView extends View {

    /** One composed art image plus its blurred 1/8 copy for the glass. Both mutable. */
    static final class Art {
        final String key;
        final Bitmap image;
        final Bitmap blur;

        Art(String key, Bitmap image, Bitmap blur) {
            this.key = key;
            this.image = image;
            this.blur = blur;
        }
    }

    interface FrameListener {
        void onArtFrame();
    }

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimBottom = new Paint();
    private final Paint scrimLeft = new Paint();
    private final Paint scrimTop = new Paint();
    private final Rect dst = new Rect();
    private Bitmap from, to, blurFrom, blurTo;
    private String key;
    private float mix = 1f;
    private ValueAnimator anim;
    private FrameListener frameListener;

    ArtView(Context c) {
        super(c);
    }

    void setFrameListener(FrameListener l) {
        frameListener = l;
    }

    Bitmap blurFrom() { return mix >= 1f ? null : blurFrom; }
    Bitmap blurTo() { return blurTo; }
    float mix() { return mix; }
    String key() { return key; }

    void show(Art next) {
        show(next, Spec.ART_MS);
    }

    /** With a longer fade for the wallpapers' slow colour flow. */
    void show(Art next, int fadeMs) {
        if (anim != null) anim.cancel();
        if (to != null && mix < 1f) {
            flatten(to, from, mix);
            if (blurTo != null) flatten(blurTo, blurFrom, mix);
        }
        from = to;
        blurFrom = blurTo;
        to = next.image;
        blurTo = next.blur;
        key = next.key;
        mix = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(fadeMs);
        anim.setInterpolator(Spec.ART);
        anim.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            if (mix >= 1f) {
                from = null;
                blurFrom = null;
            }
            invalidate();
            if (frameListener != null) frameListener.onArtFrame();
        });
        anim.start();
    }

    /** Drops the outgoing image (call when Home is hidden). */
    void releaseHidden() {
        if (anim != null && anim.isRunning()) anim.end();
        from = null;
        blurFrom = null;
    }

    /** dst := src * (1 - mix) + dst * mix, which is what the screen shows right now. */
    private static void flatten(Bitmap dstBmp, Bitmap srcBmp, float mix) {
        Canvas cv = new Canvas(dstBmp);
        int a = Math.round((1f - mix) * 255f);
        if (srcBmp != null) {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(a);
            cv.drawBitmap(srcBmp, null, new Rect(0, 0, dstBmp.getWidth(), dstBmp.getHeight()), p);
        } else {
            cv.drawColor(Color.argb(a, 0, 0, 0));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int black70 = Color.argb(Math.round(Spec.SCRIM_BOTTOM_ALPHA * 255), 0, 0, 0);
        int black45 = Color.argb(Math.round(Spec.SCRIM_LEFT_ALPHA * 255), 0, 0, 0);
        scrimBottom.setShader(new LinearGradient(0, h, 0, h * (1f - Spec.SCRIM_BOTTOM_EXTENT),
                black70, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        scrimLeft.setShader(new LinearGradient(0, 0, w * Spec.SCRIM_LEFT_EXTENT, 0,
                black45, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        scrimTop.setShader(new LinearGradient(0, 0, 0, h * Spec.SCRIM_TOP_EXTENT,
                Color.argb(Math.round(Spec.SCRIM_TOP_ALPHA * 255), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        dst.set(0, 0, w, h);
        if (from != null && mix < 1f) {
            bmpPaint.setAlpha(255);
            c.drawBitmap(from, null, dst, bmpPaint);
        }
        if (to != null && mix > 0f) {
            bmpPaint.setAlpha(Math.round(mix * 255));
            c.drawBitmap(to, null, dst, bmpPaint);
        }
        c.drawRect(0, 0, w, h, scrimBottom);
        c.drawRect(0, 0, w, h, scrimLeft);
        c.drawRect(0, 0, w, h, scrimTop);
    }
}
