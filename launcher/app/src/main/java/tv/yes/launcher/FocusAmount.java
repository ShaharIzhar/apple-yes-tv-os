package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;

/**
 * A glass button's 0..1 focus amount: jumps or animates toward on / off and
 * redraws its view on every step, optionally scaling the view along with it.
 * Shared by the capsule buttons, menu rows, Resume / Play, the calendar switch
 * and the app badges, which each used to carry their own copy of this.
 */
final class FocusAmount {

    private final View view;
    private final int ms;
    private final Interpolator curve;
    private float scale;            // <= 0: the view is not scaled
    private int scaleMs;
    private boolean redrawWhileScaling;
    private Spec.FloatSink onStep;
    private float f;
    private boolean on;
    private ValueAnimator anim;

    FocusAmount(View view, int ms, Interpolator curve) {
        this.view = view;
        this.ms = ms;
        this.curve = curve;
    }

    /** Focus also scales the view to this, over scaleMs (FOCUS curve). */
    FocusAmount scale(float to, int scaleMs, boolean redrawWhileScaling) {
        this.scale = to;
        this.scaleMs = scaleMs;
        this.redrawWhileScaling = redrawWhileScaling;
        return this;
    }

    /** Extra work on every step, before the redraw. */
    FocusAmount onStep(Spec.FloatSink sink) {
        onStep = sink;
        return this;
    }

    float get() {
        return f;
    }

    boolean isOn() {
        return on;
    }

    void set(boolean value, boolean animate) {
        if (value == on) return;
        on = value;
        float target = on ? 1f : 0f;
        float s = on ? scale : 1f;
        if (anim != null) anim.cancel();
        if (!animate) {
            if (scale > 0f) {
                view.animate().cancel();
                view.setScaleX(s);
                view.setScaleY(s);
            }
            step(target);
            return;
        }
        if (scale > 0f) {
            ViewPropertyAnimator a = view.animate().scaleX(s).scaleY(s)
                    .setDuration(scaleMs).setInterpolator(Spec.FOCUS);
            if (redrawWhileScaling) a.setUpdateListener(x -> view.invalidate());
            a.start();
        }
        anim = Spec.tween(anim, f, target, ms, curve, this::step);
    }

    private void step(float v) {
        f = v;
        if (onStep != null) onStep.set(v);
        view.invalidate();
    }
}
