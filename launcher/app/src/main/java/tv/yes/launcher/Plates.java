package tv.yes.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

/**
 * Pre-renders each tile's inner plate (120x69dp, 12dp corners) once, so tiles
 * only draw a bitmap: the app's own banner, or its icon centred on a plate
 * tinted from the icon's dominant colour.
 */
final class Plates {
    private Plates() {}

    private static final int FALLBACK_TINT = 0xFF2C2C2E;
    private static final int SYSTEM_PLATE = 0x1FFFFFFF;   // Inputs / Settings: light glass

    static int width(Context c) {
        return Spec.dpi(c, Spec.TILE_W) - 2 * Spec.dpi(c, Spec.TILE_PADDING);
    }

    static int height(Context c) {
        return Spec.dpi(c, Spec.TILE_H) - 2 * Spec.dpi(c, Spec.TILE_PADDING);
    }

    static Bitmap forApp(Context c, AppInfo app) {
        int w = width(c), h = height(c);
        Bitmap content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(content);
        if (app.banner != null) {
            drawCover(cv, app.banner, w, h);
        } else {
            cv.drawColor(dominant(app.icon));
            drawCentered(cv, app.icon, w, h, Spec.dpi(c, Spec.ICON_ON_PLATE));
        }
        return round(c, content);
    }

    /** Plate for the Inputs and Settings tiles in the grid. */
    static Bitmap forSystem(Context c, Drawable icon) {
        int w = width(c), h = height(c);
        Bitmap content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(content);
        cv.drawColor(SYSTEM_PLATE);
        Drawable d = icon.mutate();
        d.setTint(Spec.TEXT);
        drawCentered(cv, d, w, h, Spec.dpi(c, 32));
        return round(c, content);
    }

    /** The drawable as a circle, size x size px (menu-row icons, app badges). */
    static Bitmap circle(Drawable d, int size) {
        Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(raw));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p);
        raw.recycle();
        return out;
    }

    private static Bitmap round(Context c, Bitmap content) {
        int w = content.getWidth(), h = content.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = Spec.dp(c, Spec.PLATE_RADIUS);
        new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        content.recycle();
        return out;
    }

    private static void drawCover(Canvas cv, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
        } else {
            float s = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            int x = (w - dw) / 2, y = (h - dh) / 2;
            d.setBounds(x, y, x + dw, y + dh);
        }
        d.draw(cv);
    }

    private static void drawCentered(Canvas cv, Drawable d, int w, int h, int size) {
        int x = (w - size) / 2, y = (h - size) / 2;
        d.setBounds(x, y, x + size, y + size);
        d.draw(cv);
    }

    private static int dominant(Drawable icon) {
        try {
            Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 64, 64);
            icon.draw(new Canvas(b));
            Palette.Swatch s = Palette.from(b).generate().getDominantSwatch();
            b.recycle();
            return s != null ? (0xFF000000 | s.getRgb()) : FALLBACK_TINT;
        } catch (Throwable t) {
            return FALLBACK_TINT;
        }
    }
}
