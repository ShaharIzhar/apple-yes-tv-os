package tv.yes.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;

/**
 * The glass material. Nothing is blurred live: ArtView keeps a 1/8-size blurred,
 * saturated copy of each art image, and every glass shape paints the slice of it
 * that sits behind the shape's current on-screen position (transforms included).
 * Because a moving view is not redrawn by the framework, whoever moves glass
 * calls {@link #invalidateAll()} or invalidates the moving view itself.
 */
final class Glass {

    /** Precomputed geometry for one rounded glass shape, in the view's local px. */
    static final class Shape {
        final RectF rect = new RectF();
        final RectF stroke = new RectF();
        final Path path = new Path();
        final Path top = new Path();
        final Path bottom = new Path();
        float radius;

        void set(float w, float h, float radius) {
            this.radius = radius;
            rect.set(0, 0, w, h);
            path.reset();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            float b = Spec.BORDER_PX;
            stroke.set(b / 2f, b / 2f, w - b / 2f, h - b / 2f);
            // Inset highlights: the padding box minus itself shifted, like CSS inset box-shadow.
            RectF inner = new RectF(b, b, w - b, h - b);
            float ir = Math.max(0f, radius - b);
            top.reset();
            top.addRoundRect(inner, ir, ir, Path.Direction.CW);
            Path shifted = new Path();
            RectF down = new RectF(inner);
            down.offset(0, Spec.HIGHLIGHT_PX);
            shifted.addRoundRect(down, ir, ir, Path.Direction.CW);
            top.op(shifted, Path.Op.DIFFERENCE);
            bottom.reset();
            bottom.addRoundRect(inner, ir, ir, Path.Direction.CW);
            shifted.reset();
            RectF up = new RectF(inner);
            up.offset(0, -Spec.BOTTOM_EDGE_PX);
            shifted.addRoundRect(up, ir, ir, Path.Direction.CW);
            bottom.op(shifted, Path.Op.DIFFERENCE);
        }
    }

    private final ArtView art;
    private final float density;
    private final ArrayList<View> views = new ArrayList<>();
    private final Matrix viewM = new Matrix();
    private final Matrix inv = new Matrix();
    private final Matrix artM = new Matrix();
    private final Matrix local = new Matrix();
    private final Paint slice = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap shaderBmpA, shaderBmpB;
    private BitmapShader shaderA, shaderB;

    Glass(ArtView art, float density) {
        this.art = art;
        this.density = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Spec.BORDER_PX);
        shadow.setColor(Color.BLACK);
    }

    void register(View v) {
        if (!views.contains(v)) views.add(v);
    }

    void unregister(View v) {
        views.remove(v);
    }

    void invalidateAll() {
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            if (v.isShown()) v.invalidate();
        }
    }

    /**
     * Draws the full material for a shape: shadow, blurred art, white fill,
     * inset top/bottom edges and border.
     */
    void draw(Canvas c, View v, Shape s, float fillA, float borderA, float highlightA,
              float shadowDyDp, float shadowBlurDp, float shadowA) {
        if (shadowA > 0.001f) {
            int save = c.save();
            c.clipOutPath(s.path);
            shadow.setShadowLayer(Spec.shadowRadius(shadowBlurDp * density), 0,
                    shadowDyDp * density, Color.argb(Math.round(shadowA * 255), 0, 0, 0));
            c.drawRoundRect(s.rect, s.radius, s.radius, shadow);
            c.restoreToCount(save);
        }
        drawBehind(c, v, s);
        fill.setColor(Spec.white(fillA));
        c.drawRoundRect(s.rect, s.radius, s.radius, fill);
        fill.setColor(Spec.white(highlightA));
        c.drawPath(s.top, fill);
        fill.setColor(Spec.white(Spec.BOTTOM_EDGE));
        c.drawPath(s.bottom, fill);
        stroke.setColor(Spec.white(borderA));
        float sr = Math.max(0f, s.radius - Spec.BORDER_PX / 2f);
        c.drawRoundRect(s.stroke, sr, sr, stroke);
    }

    /** Only the blurred art behind the shape: no fill, edges, border or shadow (the frosted top band). */
    void drawArtBlur(Canvas c, View v, Shape s) {
        drawBehind(c, v, s);
    }

    private void drawBehind(Canvas c, View v, Shape s) {
        Bitmap from = art.blurFrom();
        Bitmap to = art.blurTo();
        float mix = art.mix();
        if (from == null && to == null) return;
        viewToWindow(v, viewM);
        if (!viewM.invert(inv)) return;
        viewToWindow(art, artM);
        if (from != null && mix < 1f) paintSlice(c, s, from, 255);
        if (to != null && mix > 0f) paintSlice(c, s, to, Math.round(mix * 255));
    }

    private void paintSlice(Canvas c, Shape s, Bitmap bmp, int alpha) {
        BitmapShader sh = shaderFor(bmp);
        // blurred bitmap px -> art view px -> window px -> this view's local px
        local.setScale(art.getWidth() / (float) bmp.getWidth(), art.getHeight() / (float) bmp.getHeight());
        local.postConcat(artM);
        local.postConcat(inv);
        sh.setLocalMatrix(local);
        slice.setShader(sh);
        slice.setAlpha(alpha);
        c.drawRoundRect(s.rect, s.radius, s.radius, slice);
        slice.setShader(null);
    }

    private BitmapShader shaderFor(Bitmap bmp) {
        if (bmp == shaderBmpA) return shaderA;
        if (bmp == shaderBmpB) return shaderB;
        // Keep the two most recent; the art layer never shows more than two at once.
        shaderBmpB = shaderBmpA;
        shaderB = shaderA;
        shaderBmpA = bmp;
        shaderA = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        return shaderA;
    }

    /** Local px of {@code v} -> window px, including every transform on the way up. */
    static void viewToWindow(View v, Matrix out) {
        out.reset();
        View cur = v;
        while (true) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) out.postConcat(m);
            out.postTranslate(cur.getLeft(), cur.getTop());
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            out.postTranslate(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
    }
}
