package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** The Continue-watching progress track and Resume button (design spec, Continue watching). */
final class ContinueViews {
    private ContinueViews() {}

    /** 204x4dp track, white 22% with a 0.5dp white 35% top highlight; #F5F5F7 fill. */
    static final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;
        private ValueAnimator anim;

        Track(Context c) {
            super(c);
        }

        /** from < 0: set at once; otherwise animate from that fraction (600ms, art curve). */
        void setFraction(float from, float to) {
            if (anim != null) anim.cancel();
            if (from < 0) {
                fraction = to;
                invalidate();
                return;
            }
            anim = Spec.tween(anim, from, to, Spec.ART_MS, Spec.ART, f -> {
                fraction = f;
                invalidate();
            });
        }

        float fraction() {
            return fraction;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = Spec.dp(getContext(), 2);
            r.set(0, 0, w, h);
            paint.setColor(Spec.white(0.22f));
            c.drawRoundRect(r, rad, rad, paint);
            paint.setColor(Spec.white(0.35f));
            int save = c.save();
            c.clipRect(0, 0, w, Spec.dp(getContext(), 0.5f));
            c.drawRoundRect(r, rad, rad, paint);
            c.restoreToCount(save);
            if (fraction > 0f) {
                r.set(0, 0, Math.max(h, w * fraction), h);
                paint.setColor(Spec.TEXT);
                c.drawRoundRect(r, rad, rad, paint);
            }
        }
    }

    /**
     * Resume: a liquid-glass capsule like the top-right capsule (user, 27 Oct),
     * not a solid white pill. Always glass with white content. Focus (made
     * stronger, user 29 Oct) brightens the glass, sharpens its border, adds the
     * sheen, shadow and a thin white halo just outside it, and scales it 1.10
     * from its left edge, 450ms. The now playing and speaker cards reuse it as
     * Play / Pause (pause icon: two bars).
     */
    static final class Resume extends View {
        private final Glass glass;
        private final Glass.Shape shape = new Glass.Shape();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF haloRect = new RectF();
        // Hold to open (user, 30 Oct): a ring along the halo, from top middle, clockwise.
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ringPath = new Path();
        private final Path ringPart = new Path();
        private final PathMeasure ringMeasure = new PathMeasure();
        private float holdP;
        private final Path play = new Path();
        private final Path pause = new Path();
        private final Path plus = new Path();
        private String label;
        private boolean pauseIcon, plusIcon;
        // Sizes in dp: regular (continue card) or compact (the smaller media cards, user 29 Oct).
        private final float padL, icon, gap, padR, height;
        private final float d;
        private final FocusAmount focus = new FocusAmount(this, Spec.TRANSFORM_MS, Spec.FOCUS)
                .scale(FOCUS_SCALE, Spec.TRANSFORM_MS, true);

        Resume(Context c, Glass glass, String label, Typeface semibold) {
            this(c, glass, label, semibold, false);
        }

        Resume(Context c, Glass glass, String label, Typeface semibold, boolean compact) {
            super(c);
            this.glass = glass;
            this.label = label;
            d = c.getResources().getDisplayMetrics().density;
            padL = compact ? 10 : 12;
            icon = compact ? 9 : 11;
            gap = compact ? 5 : 6;
            padR = compact ? 13 : 16;
            height = compact ? 26 : Spec.RESUME_H;
            text.setTypeface(semibold);
            text.setTextSize((compact ? 11 : 12) * c.getResources().getDisplayMetrics().scaledDensity);
            text.setColor(Spec.TEXT);
            fill.setColor(Spec.TEXT);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(1.5f * d);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * d);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(Spec.white(0.95f));
            setPivotX(0f);
            glass.register(this);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = Math.round((padL + icon + gap + padR) * d + text.measureText(label));
            setMeasuredDimension(w, Math.round(height * d));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shape.set(w, h, h / 2f);
            setPivotY(h / 2f);
            float s = icon * d, x = padL * d, y = (h - s) / 2f;
            play.reset();
            play.moveTo(x + s * 0.18f, y + s * 0.08f);
            play.lineTo(x + s * 0.92f, y + s * 0.5f);
            play.lineTo(x + s * 0.18f, y + s * 0.92f);
            play.close();
            float bw = s * 0.26f;
            pause.reset();
            pause.addRect(x + s * 0.14f, y + s * 0.1f, x + s * 0.14f + bw, y + s * 0.9f, Path.Direction.CW);
            pause.addRect(x + s * 0.86f - bw, y + s * 0.1f, x + s * 0.86f, y + s * 0.9f, Path.Direction.CW);
            float t = s * 0.16f;
            plus.reset();
            plus.addRect(x + s / 2f - t / 2f, y, x + s / 2f + t / 2f, y + s, Path.Direction.CW);
            plus.addRect(x, y + s / 2f - t / 2f, x + s, y + s / 2f + t / 2f, Path.Direction.CW);
            // The ring's track: the halo's outline, starting at the top middle and running clockwise.
            float g = HALO_GAP_DP * d, rl = -g, rt = -g, rr = w + g, rb = h + g, rad = h / 2f + g;
            ringPath.reset();
            ringPath.moveTo(w / 2f, rt);
            ringPath.lineTo(rr - rad, rt);
            ringPath.arcTo(rr - 2 * rad, rt, rr, rt + 2 * rad, -90, 90, false);
            ringPath.lineTo(rr, rb - rad);
            ringPath.arcTo(rr - 2 * rad, rb - 2 * rad, rr, rb, 0, 90, false);
            ringPath.lineTo(rl + rad, rb);
            ringPath.arcTo(rl, rb - 2 * rad, rl + 2 * rad, rb, 90, 90, false);
            ringPath.lineTo(rl, rt + rad);
            ringPath.arcTo(rl, rt, rl + 2 * rad, rt + 2 * rad, 180, 90, false);
            ringPath.lineTo(w / 2f, rt);
            ringMeasure.setPath(ringPath, false);
            sheen.setShader(new LinearGradient(0, 0, w * 0.55f, h,
                    new int[]{Spec.white(0.42f), Spec.white(0.08f), Spec.white(0f)},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        }

        boolean isOn() {
            return focus.isOn();
        }

        /** 0..1 of the hold ring; 0 hides it. */
        void setHoldProgress(float p) {
            holdP = p;
            invalidate();
        }

        float holdProgress() {
            return holdP;
        }

        /** "+": the Add widget button. */
        void setPlusIcon(boolean on) {
            plusIcon = on;
            invalidate();
        }

        /** Play / Pause in the media cards; the width follows the label. */
        void setLabel(String value, boolean showPause) {
            if (value.equals(label) && showPause == pauseIcon) return;
            boolean resize = !value.equals(label);
            label = value;
            pauseIcon = showPause;
            if (resize) requestLayout();
            invalidate();
        }

        void setOn(boolean value, boolean animate) {
            focus.set(value, animate);
        }

        private static final float FOCUS_SCALE = 1.10f;
        private static final float FOCUS_FILL = 0.32f;
        private static final float FOCUS_BORDER = 0.90f;
        private static final float FOCUS_HALO = 0.85f;
        private static final float HALO_GAP_DP = 3f;

        @Override
        protected void onDraw(Canvas c) {
            float fOn = focus.get();
            glass.draw(c, this, shape,
                    Spec.lerp(Spec.GLASS_FILL, FOCUS_FILL, fOn),
                    Spec.lerp(Spec.GLASS_BORDER, FOCUS_BORDER, fOn),
                    Spec.lerp(Spec.HIGHLIGHT, 1f, fOn),
                    Spec.lerp(Spec.SHADOW_DY, 10, fOn), Spec.lerp(Spec.SHADOW_BLUR, 22, fOn),
                    Spec.lerp(Spec.SHADOW_ALPHA, 0.45f, fOn));
            if (fOn > 0f) {
                // Drawn outside the view: every parent up to the card keeps clipChildren off.
                float g = HALO_GAP_DP * d;
                haloRect.set(-g, -g, getWidth() + g, getHeight() + g);
                halo.setColor(Spec.white(FOCUS_HALO * fOn));
                c.drawRoundRect(haloRect, shape.radius + g, shape.radius + g, halo);
            }
            if (holdP > 0f) {
                ringPart.reset();
                ringMeasure.getSegment(0f, holdP * ringMeasure.getLength(), ringPart, true);
                c.drawPath(ringPart, ring);
            }
            sheen.setAlpha(Math.round(Spec.lerp(Spec.SHEEN_RESTING, 1f, fOn) * 255));
            c.drawRoundRect(shape.rect, shape.radius, shape.radius, sheen);
            c.drawPath(plusIcon ? plus : pauseIcon ? pause : play, fill);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(label, (padL + icon + gap) * d, baseline, text);
        }
    }

    /**
     * The app's logo in a small circle at the continue card's top right (user,
     * 29 Oct): gray and dimmed like a resting tile, in full colour while Resume
     * has focus, so the card names its app without lighting the row tile.
     */
    static final class AppBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Bitmap icon;
        private final FocusAmount focus = new FocusAmount(this, Spec.SHEEN_MS, Spec.EASE);

        AppBadge(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(c.getResources().getDisplayMetrics().density);
            ring.setColor(Spec.white(0.3f));
        }

        /** A circular bitmap, or null to show nothing. */
        void setIcon(Bitmap b) {
            icon = b;
            invalidate();
        }

        void setOn(boolean value, boolean animate) {
            focus.set(value, animate);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (icon == null) return;
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            paint.setColorFilter(TileView.plateFilter(focus.get()));
            c.drawBitmap(icon, null, r, paint);
            float half = ring.getStrokeWidth() / 2f;
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - half, ring);
        }
    }

    static int alpha(float a) {
        return Color.argb(Math.round(a * 255), 0xF5, 0xF5, 0xF7);
    }
}
