package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Every value from design/LAUNCHER-DESIGN.md (dp = mockup px x 0.6) in one place.
 * Values marked "mockup" come from liquid-glass-home.dc.html because the spec
 * does not list them. Values marked "grid" are the app grid, which neither file
 * specifies; it reuses the row's tile spec.
 */
final class Spec {
    private Spec() {}

    // Layout (dp)
    static final float EDGE = 58;              // row left/right margin, title block left
    static final float ROW_BOTTOM = 120;       // user, 27 Oct: row higher than the spec's 72
    static final float TILE_W = 128;           // user, 27 Oct: smaller than the spec's 150x90
    static final float TILE_H = 77;
    static final float TILE_GAP = 24;
    static final int ROW_VISIBLE = 5;
    static final float TITLE_BOTTOM = 246;     // moved up with the row (spec 198)
    static final float TITLE_MAX_W = 456;
    static final float LOGO_MAX_H = 120;
    static final float TITLE_GAP = 7;
    // Continue watching as a glass card close above the row (user, 27 Oct: smaller, liquid glass).
    static final float CONTINUE_BOTTOM = 219;  // row top (120 + 77) + 22dp: clear of the focused first tile (user, 30 Oct)
    static final float CARD_PADDING = 12;
    static final float CARD_RADIUS = 20;
    static final float CARD_TITLE_SP = 20;
    static final float CARD_EPISODE_SP = 12;
    static final float CARD_LOGO_W = 360, CARD_LOGO_H = 52;
    static final float CARD_TRACK_W = 200;
    static final float RESUME_H = 30;
    // Now playing, speaker and calendar cards on the right: the continue card's glass and radius (user, 28 Oct),
    // one shared width, a little smaller, evenly spaced under the capsule (user, 29 Oct).
    static final float CARD_GAP = 12;          // capsule to first card, and between cards
    static final float SIDE_CARD_W = 224;
    static final float SIDE_CARD_PADDING = 10;
    static final float SIDE_LINE_SP = 13;
    static final float SIDE_SMALL_SP = 11;
    // Frosted band across the top while the grid scrolls under the clock, weather and capsule (user, 29 Oct).
    static final float TOP_BAND_H = 140;
    static final float TOP_BAND_FADE = 40;     // soft bottom edge
    static final float TOP_BAND_TINT = 0.62f;  // black over the blurred art
    static final float CAPSULE_TOP = 24;
    static final float CAPSULE_RIGHT = 34;

    // Type (sp)
    static final float TITLE_SP = 38;
    static final float TITLE_TRACKING = -0.025f;
    static final float TITLE_LEADING = 1.02f;
    static final float APPNAME_SP = 12;
    static final float APPNAME_TRACKING = 0.01f;
    static final int APPNAME_COLOR = 0xBDF5F5F7;   // #F5F5F7 at 74%
    static final float LABEL_SP = 12;
    static final float LABEL_GAP = 11;
    static final int TEXT = 0xFFF5F5F7;

    // Scrim
    static final float SCRIM_BOTTOM_ALPHA = 0.70f;
    static final float SCRIM_BOTTOM_EXTENT = 0.58f;
    static final float SCRIM_LEFT_ALPHA = 0.45f;
    static final float SCRIM_LEFT_EXTENT = 0.45f;
    // Behind the clock and weather (user additions, not in the spec): legible on bright art.
    static final float SCRIM_TOP_ALPHA = 0.40f;
    static final float SCRIM_TOP_EXTENT = 0.30f;

    // Glass material
    static final float GLASS_FILL = 0.10f;
    static final float GLASS_FILL_FOCUSED = 0.18f;  // mockup: tint + 0.08
    static final float GLASS_BORDER = 0.22f;
    static final float TILE_BORDER = 0.20f;
    static final float TILE_BORDER_FOCUSED = 0.45f;
    static final float BORDER_PX = 1f;
    static final float HIGHLIGHT_PX = 1.5f;
    static final float HIGHLIGHT = 0.45f;
    static final float HIGHLIGHT_FOCUSED = 0.80f;
    static final float BOTTOM_EDGE_PX = 1f;
    static final float BOTTOM_EDGE = 0.10f;
    static final float SHADOW_DY = 6, SHADOW_BLUR = 14, SHADOW_ALPHA = 0.30f;
    static final float SHADOW_DY_FOCUSED = 18, SHADOW_BLUR_FOCUSED = 36, SHADOW_ALPHA_FOCUSED = 0.55f;
    static final int BLUR_DOWNSCALE = 8;
    static final int BLUR_BOX_RADIUS = 3;      // 3 box passes of radius 3 = Gaussian sigma ~3.5 at 1/8 size
    static final float BLUR_SATURATION = 1.8f;

    // App tile
    static final float TILE_RADIUS = 16;
    static final float TILE_PADDING = 4;
    static final float PLATE_RADIUS = 12;
    static final float SHEEN_ANGLE = 125;
    static final float SHEEN_RESTING = 0.25f;
    static final float PLATE_REST_BRIGHTNESS = 0.8f;   // user, 27 Oct: unfocused plates gray, focused in colour
    static final float FOCUS_LIFT = 6;
    static final float FOCUS_SCALE = 1.10f;
    static final float ICON_ON_PLATE = 48;     // icon-only apps; neither file lists a size

    // Capsule
    static final float CAPSULE_PADDING = 4;
    static final float CAPSULE_GAP = 4;
    static final float CAP_BUTTON = 32;
    static final float CAP_ICON = 16;
    static final int CAP_ICON_FOCUSED = 0xFF111111;
    static final float CAP_FILL_FOCUSED = 0.92f;
    static final float CAP_SCALE = 1.08f;

    // Grid (not in the spec: reuses the tile spec; rows need room for the focused label)
    static final float GRID_ROW_GAP = 48;
    static final float ALL_APPS_GAP = 24;      // extra space between the row and the grid (user, 30 Oct)
    static final float ALL_APPS_TITLE_H = 34;  // "All Apps" above the first grid row
    static final float GRID_FOCUS_TOP = 246;   // focused grid row's top edge on screen (user, 29 Oct: 30dp lower)

    // Motion (ms)
    static final int TRANSFORM_MS = 450;
    static final int ART_MS = 600;
    static final int SHEEN_MS = 350;
    static final int BORDER_MS = 300;          // mockup: border-color / background 300ms
    static final int LABEL_MS = 250;
    static final int LABEL_DELAY_MS = 120;
    static final int CAP_MS = 350;
    static final int CAP_COLOR_MS = 250;       // mockup: background / color 250ms
    static final int GRID_FADE_MS = 300;
    static final int ART_WAIT_MS = 400;
    static final int HOLD_TO_MOVE_MS = 600;    // hold Select this long on a row app to reorder
    static final float WIGGLE_DEG = 1.2f;
    static final int WIGGLE_MS = 130;        // show art not ready by then: app name over the wallpaper

    static final Interpolator FOCUS = new PathInterpolator(0.2f, 0.9f, 0.3f, 1.0f);
    static final Interpolator ART = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f); // CSS "ease"

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    /** CSS box-shadow blur length -> Paint.setShadowLayer radius (both in px). */
    static float shadowRadius(float cssBlurPx) {
        float sigma = cssBlurPx / 2f;
        return Math.max(0.1f, (sigma - 0.5f) / 0.57735f);
    }

    static int white(float alpha) {
        return (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xFFFFFF;
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    interface FloatSink {
        void set(float v);
    }

    /** Cancels {@code old} and animates from -> to, handing every frame's value to {@code sink}. */
    static ValueAnimator tween(ValueAnimator old, float from, float to, int ms, Interpolator curve, FloatSink sink) {
        if (old != null) old.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.setInterpolator(curve);
        a.addUpdateListener(v -> sink.set((float) v.getAnimatedValue()));
        a.start();
        return a;
    }
}
