package tv.yes.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A resting glass container: the top-right capsule and the Inputs dropdown. */
final class GlassPanel extends LinearLayout {

    private final Glass glass;
    private final Glass.Shape shape = new Glass.Shape();
    private final float radiusDp;   // < 0: fully rounded
    private boolean glassOn = true;
    private boolean editBadge, editFocus;
    private Paint editPaint;
    private final RectF editRing = new RectF();

    GlassPanel(Context c, Glass glass, int orientation, float radiusDp) {
        super(c);
        this.glass = glass;
        this.radiusDp = radiusDp;
        setOrientation(orientation);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        int pad = Spec.dpi(c, Spec.CAPSULE_PADDING);
        setPadding(pad, pad, pad, pad);
        glass.register(this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shape.set(w, h, radiusDp < 0 ? h / 2f : Spec.dp(getContext(), radiusDp));
    }

    /** The title block is a glass card only in the continue layout. */
    void setGlass(boolean on) {
        glassOn = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!glassOn) return;
        glass.draw(c, this, shape, Spec.GLASS_FILL, Spec.GLASS_BORDER, Spec.HIGHLIGHT,
                Spec.SHADOW_DY, Spec.SHADOW_BLUR, Spec.SHADOW_ALPHA);
    }

    /**
     * Edit widgets (user, 29 Oct): the apps' "−" badge on the top-left corner,
     * and a white ring around the widget that has focus. Both drawn over the
     * children, partly outside the panel (its parents keep clipChildren off).
     */
    void setEdit(boolean badge, boolean focus) {
        if (badge == editBadge && focus == editFocus) return;
        editBadge = badge;
        editFocus = focus;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (!editBadge && !editFocus) return;
        if (editPaint == null) editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float d = getResources().getDisplayMetrics().density;
        if (editFocus) {
            float g = 4 * d;
            editPaint.setStyle(Paint.Style.STROKE);
            editPaint.setStrokeWidth(2 * d);
            editPaint.setColor(Spec.white(0.9f));
            editRing.set(-g, -g, getWidth() + g, getHeight() + g);
            c.drawRoundRect(editRing, shape.radius + g, shape.radius + g, editPaint);
        }
        if (editBadge) drawMinusBadge(c, d, editPaint);
    }

    /** Edit mode's "−" badge on the top-left corner (apps and widgets), like the iPhone home screen. */
    static void drawMinusBadge(Canvas c, float d, Paint p) {
        float r = 9 * d, cx = 3 * d, cy = 3 * d;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Spec.white(0.92f));
        p.setShadowLayer(4 * d, 0, d, 0x66000000);
        c.drawCircle(cx, cy, r, p);
        p.clearShadowLayer();
        p.setColor(0xFF111111);
        p.setStrokeWidth(1.6f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - 4 * d, cy, cx + 4 * d, cy, p);
    }

    /** Round 32dp icon button in the capsule. Focused: white 92% disc, #111 icon, scale 1.08. */
    static final class CapButton extends View {
        private final Drawable icon;
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final FocusAmount focus = new FocusAmount(this, Spec.CAP_COLOR_MS, Spec.EASE)
                .scale(Spec.CAP_SCALE, Spec.CAP_MS, false);

        CapButton(Context c, Drawable icon) {
            super(c);
            this.icon = icon.mutate();
        }

        void setOn(boolean value, boolean animate) {
            focus.set(value, animate);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), fOn = focus.get();
            if (fOn > 0f) {
                disc.setColor(Spec.white(Spec.CAP_FILL_FOCUSED * fOn));
                c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, disc);
            }
            icon.setTint(blend(Spec.TEXT, Spec.CAP_ICON_FOCUSED, fOn));
            int s = Spec.dpi(getContext(), Spec.CAP_ICON);
            int x = Math.round((w - s) / 2f), y = Math.round((h - s) / 2f);
            icon.setBounds(x, y, x + s, y + s);
            icon.draw(c);
        }
    }

    /**
     * One row of the Inputs dropdown. Not in the spec; it borrows the capsule
     * button's focused look (white 92% pill, #111 text) so focus reads the same.
     */
    static final class MenuRow extends TextView {
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final FocusAmount focus = new FocusAmount(this, Spec.CAP_COLOR_MS, Spec.EASE)
                .onStep(f -> setTextColor(blend(Spec.TEXT, Spec.CAP_ICON_FOCUSED, f)));

        private Bitmap icon;   // round app icon left of the text, or null

        MenuRow(Context c, CharSequence text, Typeface medium) {
            this(c, text, null, medium);
        }

        /** With an app icon: a small circle on the left; row size and text size stay the same. */
        MenuRow(Context c, CharSequence text, Drawable appIcon, Typeface medium) {
            super(c);
            setText(text);
            setTypeface(medium);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            setTextColor(Spec.TEXT);
            // Hebrew names stay left-aligned like the rest of the (LTR) launcher: absolute LEFT, not "start".
            setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            int padH = Spec.dpi(c, 14);
            if (appIcon != null) {
                int s = Spec.dpi(c, ICON_DP);
                icon = Plates.circle(appIcon, s);
                setPadding(padH + s + Spec.dpi(c, 8), 0, padH, 0);
            } else {
                setPadding(padH, 0, padH, 0);
            }
        }

        private static final float ICON_DP = 18;

        void setOn(boolean value, boolean animate) {
            focus.set(value, animate);
        }

        @Override
        protected void onDraw(Canvas c) {
            float fOn = focus.get();
            if (fOn > 0f) {
                pill.setColor(Spec.white(Spec.CAP_FILL_FOCUSED * fOn));
                // A single-line TextView may be scrolled; keep the pill on the view itself.
                r.set(getScrollX(), 0, getScrollX() + getWidth(), getHeight());
                float rad = Spec.dp(getContext(), Spec.PLATE_RADIUS);
                c.drawRoundRect(r, rad, rad, pill);
            }
            if (icon != null) {
                c.drawBitmap(icon, getScrollX() + Spec.dp(getContext(), 14), (getHeight() - icon.getHeight()) / 2f, null);
            }
            super.onDraw(c);
        }
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
