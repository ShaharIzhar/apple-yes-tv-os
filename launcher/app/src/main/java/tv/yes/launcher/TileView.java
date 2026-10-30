package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * One app tile: a 128x77dp glass frame with the banner plate inside, the sheen
 * on top, and the name 11dp below (visible on the focused tile only). Scale and
 * lift apply to the whole tile from its centre, like the mockup's wrapper.
 * Every animation starts from the current on-screen value, so a new key press
 * simply retargets it.
 */
final class TileView extends FrameLayout {

    final Frame frame;
    final TextView label;
    private final Glass glass;
    private boolean focused;
    private boolean editBadge;
    private float fBorder, fShadow, fSheen;
    private ValueAnimator aBorder, aShadow, aSheen;

    TileView(Context c, Glass glass, Bitmap plate, CharSequence name, Typeface medium) {
        super(c);
        this.glass = glass;
        setClipChildren(false);
        setClipToPadding(false);
        setOutlineProvider(null);
        int w = Spec.dpi(c, Spec.TILE_W), h = Spec.dpi(c, Spec.TILE_H);
        frame = new Frame(c, plate);
        addView(frame, new LayoutParams(w, h));
        label = new TextView(c);
        label.setText(name);
        label.setTextColor(Spec.TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Spec.LABEL_SP);
        label.setTypeface(medium);
        label.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(w, Spec.dpi(c, 20));
        lp.topMargin = h + Spec.dpi(c, Spec.LABEL_GAP);
        addView(label, lp);
        glass.register(frame);
    }

    /** Edit mode: a small "−" badge on the top-left corner, like the iPhone home screen. */
    void setEditBadge(boolean on) {
        editBadge = on;
        frame.invalidate();
    }

    void detach() {
        glass.unregister(frame);
    }

    boolean isFocusedTile() {
        return focused;
    }

    void setFocusedTile(boolean on, boolean animate) {
        if (on == focused) return;
        focused = on;
        setTranslationZ(on ? 1f : 0f);
        float scale = on ? Spec.FOCUS_SCALE : 1f;
        float lift = on ? -Spec.dp(getContext(), Spec.FOCUS_LIFT) : 0f;
        float target = on ? 1f : 0f;
        if (!animate) {
            animate().cancel();
            label.animate().cancel();
            cancel(aBorder);
            cancel(aShadow);
            cancel(aSheen);
            setScaleX(scale);
            setScaleY(scale);
            setTranslationY(lift);
            label.setAlpha(target);
            fBorder = fShadow = fSheen = target;
            frame.invalidate();
            return;
        }
        animate().scaleX(scale).scaleY(scale).translationY(lift)
                .setStartDelay(0)
                .setDuration(Spec.TRANSFORM_MS).setInterpolator(Spec.FOCUS)
                .setUpdateListener(a -> frame.invalidate())
                .start();
        label.animate().alpha(target)
                .setStartDelay(on ? Spec.LABEL_DELAY_MS : 0)
                .setDuration(Spec.LABEL_MS).setInterpolator(Spec.EASE)
                .start();
        aBorder = Spec.tween(aBorder, fBorder, target, Spec.BORDER_MS, Spec.EASE, f -> {
            fBorder = f;
            frame.invalidate();
        });
        aShadow = Spec.tween(aShadow, fShadow, target, Spec.TRANSFORM_MS, Spec.FOCUS, f -> {
            fShadow = f;
            frame.invalidate();
        });
        aSheen = Spec.tween(aSheen, fSheen, target, Spec.SHEEN_MS, Spec.EASE, f -> {
            fSheen = f;
            frame.invalidate();
        });
    }

    private static void cancel(ValueAnimator a) {
        if (a != null) a.cancel();
    }

    /** The glass frame, plate and sheen. */
    final class Frame extends View {
        private final Glass.Shape shape = new Glass.Shape();
        private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Bitmap plate;
        private final float pad;

        Frame(Context c, Bitmap plate) {
            super(c);
            this.plate = plate;
            this.pad = Spec.dp(c, Spec.TILE_PADDING);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shape.set(w, h, Spec.dp(getContext(), Spec.TILE_RADIUS));
            sheen.setShader(sheenGradient(w, h));
        }

        @Override
        protected void onDraw(Canvas c) {
            glass.draw(c, this, shape,
                    Spec.lerp(Spec.GLASS_FILL, Spec.GLASS_FILL_FOCUSED, fBorder),
                    Spec.lerp(Spec.TILE_BORDER, Spec.TILE_BORDER_FOCUSED, fBorder),
                    Spec.lerp(Spec.HIGHLIGHT, Spec.HIGHLIGHT_FOCUSED, fShadow),
                    Spec.lerp(Spec.SHADOW_DY, Spec.SHADOW_DY_FOCUSED, fShadow),
                    Spec.lerp(Spec.SHADOW_BLUR, Spec.SHADOW_BLUR_FOCUSED, fShadow),
                    Spec.lerp(Spec.SHADOW_ALPHA, Spec.SHADOW_ALPHA_FOCUSED, fShadow));
            if (plate != null) {
                plain.setColorFilter(plateFilter(fSheen));
                c.drawBitmap(plate, pad, pad, plain);
            }
            sheen.setAlpha(Math.round(Spec.lerp(Spec.SHEEN_RESTING, 1f, fSheen) * 255));
            c.drawPath(shape.path, sheen);
            if (editBadge) {
                plain.setColorFilter(null);
                GlassPanel.drawMinusBadge(c, getResources().getDisplayMetrics().density, plain);
            }
        }
    }

    private static final ColorFilter REST_FILTER = plateMatrix(0f);
    private static final ColorFilter[] FILTERS = new ColorFilter[256];

    /**
     * Gray and slightly dimmed at rest, full colour when focused (f = 1). In
     * 1/255 steps, built once each: a fade used to allocate a new filter every
     * frame for every moving tile and badge. A step moves a channel by less than
     * one 8-bit level, so the pixels match the exact filter. Main thread only.
     */
    static ColorFilter plateFilter(float f) {
        if (f >= 1f) return null;
        if (f <= 0f) return REST_FILTER;
        int i = Math.round(f * 255f);
        ColorFilter cf = FILTERS[i];
        if (cf == null) FILTERS[i] = cf = plateMatrix(i / 255f);
        return cf;
    }

    private static ColorFilter plateMatrix(float f) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(f);
        float k = Spec.lerp(Spec.PLATE_REST_BRIGHTNESS, 1f, f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(k, k, k, 1f);
        cm.postConcat(dim);
        return new ColorMatrixColorFilter(cm);
    }

    /** CSS linear-gradient(125deg, white .42 0%, white .08 32%, white 0 55%) over w x h. */
    private static Shader sheenGradient(int w, int h) {
        double a = Math.toRadians(Spec.SHEEN_ANGLE);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float cx = w / 2f, cy = h / 2f;
        return new LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                new int[]{Spec.white(0.42f), Spec.white(0.08f), Spec.white(0f), Spec.white(0f)},
                new float[]{0f, 0.32f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }
}
