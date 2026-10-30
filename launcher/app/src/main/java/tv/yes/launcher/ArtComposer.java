package tv.yes.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Builds one screen-size art bitmap (the still, centerCrop) and its blurred,
 * saturated 1/8 copy for the glass. Runs off the
 * main thread. Both results are mutable so ArtView can flatten into them.
 */
final class ArtComposer {
    private ArtComposer() {}

    static ArtView.Art compose(String key, Bitmap backdrop, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (backdrop != null) {
            c.drawBitmap(backdrop, centerCrop(backdrop.getWidth(), backdrop.getHeight(), w, h),
                    new Rect(0, 0, w, h), p);
        }
        return new ArtView.Art(key, out, blurCopy(out));
    }

    private static Rect centerCrop(int sw, int sh, int dw, int dh) {
        float scale = Math.max(dw / (float) sw, dh / (float) sh);
        int cw = Math.round(dw / scale), chh = Math.round(dh / scale);
        int x = (sw - cw) / 2, y = (sh - chh) / 2;
        return new Rect(x, y, x + cw, y + chh);
    }

    static Bitmap blurCopy(Bitmap src) {
        int bw = Math.max(1, src.getWidth() / Spec.BLUR_DOWNSCALE);
        int bh = Math.max(1, src.getHeight() / Spec.BLUR_DOWNSCALE);
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(Spec.BLUR_SATURATION);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, null, new Rect(0, 0, bw, bh), p);
        int[] px = new int[bw * bh];
        small.getPixels(px, 0, bw, 0, 0, bw, bh);
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, bw, bh, Spec.BLUR_BOX_RADIUS, true);
            boxBlur(tmp, px, bw, bh, Spec.BLUR_BOX_RADIUS, false);
        }
        small.setPixels(px, 0, bw, 0, 0, bw, bh);
        return small;
    }

    /** One horizontal or vertical box-blur pass with clamped edges. Opaque input. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(line, clamp(i, len), w, horizontal)];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int cOut = in[idx(line, clamp(i - r, len), w, horizontal)];
                int cIn = in[idx(line, clamp(i + r + 1, len), w, horizontal)];
                rs += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                gs += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                bs += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
