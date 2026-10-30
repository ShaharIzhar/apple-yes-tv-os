package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small glass cards on Home, all built from the continue-watching card's
 * material (user, 28 Oct): GlassPanel with the card radius, its eyebrow / line
 * styles, and the glass Resume button as Play / Pause. The cards on the right
 * share one width (user, 29 Oct), set by the column that holds them.
 */
final class GlassCards {
    private GlassCards() {}

    /** A Home text line: typeface, size, colour and its drop shadow (blur and offset in dp). */
    static TextView text(Context c, Typeface face, float sp, int color, float shadowBlur, float shadowDy) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setShadowLayer(shadowBlur * d, 0, shadowDy * d, 0x59000000);
        return t;
    }

    /** The continue card's eyebrow: semibold caps, +0.1em, 62%. */
    static TextView eyebrow(Context c, Typeface semibold, float sp) {
        TextView t = text(c, semibold, sp, ContinueViews.alpha(0.62f), 8, 1);
        t.setLetterSpacing(0.1f);
        t.setAllCaps(true);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** One card line: single line, ellipsized at the width its parent gives it. */
    static TextView line(Context c, Typeface face, float sp, int color) {
        TextView t = text(c, face, sp, color, 8, 1);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GlassPanel panel(Context c, Glass glass, int orientation) {
        GlassPanel p = new GlassPanel(c, glass, orientation, Spec.CARD_RADIUS);
        int pad = Spec.dpi(c, Spec.SIDE_CARD_PADDING);
        p.setPadding(pad, pad, pad, pad);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setClipChildren(false);
        p.setClipToPadding(false);
        p.setVisibility(View.GONE);
        return p;
    }

    private static LinearLayout.LayoutParams fill(int topMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMarginPx;
        return lp;
    }

    /**
     * Now playing / speaker: the eyebrow with the playing app's logo in a circle
     * at the top right (gray, in colour while Play has focus, like the continue
     * card; user 29 Oct), then title and artist with Play / Pause beside them.
     */
    static final class MediaCard {
        private static final float BADGE_DP = 18;   // user, 29 Oct: smaller

        final GlassPanel view;
        final ContinueViews.Resume button;
        private final ContinueViews.AppBadge badge;
        private final LiveBars bars;
        private final TextView eyebrow, title, by;
        private final String play, pause;

        MediaCard(Context c, Glass glass, Typeface regular, Typeface semibold) {
            view = panel(c, glass, LinearLayout.VERTICAL);
            play = c.getString(R.string.play);
            pause = c.getString(R.string.pause);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            bars = new LiveBars(c);
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(Spec.dpi(c, 12), Spec.dpi(c, 10));
            lb.rightMargin = Spec.dpi(c, 6);
            header.addView(bars, lb);
            eyebrow = eyebrow(c, semibold, 9);
            header.addView(eyebrow, new LinearLayout.LayoutParams(0, -2, 1f));
            badge = new ContinueViews.AppBadge(c);
            int s = Spec.dpi(c, BADGE_DP);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(s, s);
            gp.leftMargin = Spec.dpi(c, 8);
            header.addView(badge, gp);
            view.addView(header, fill(0));

            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setClipChildren(false);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            title = line(c, semibold, Spec.SIDE_LINE_SP, Spec.TEXT);
            col.addView(title, fill(0));
            by = line(c, regular, Spec.SIDE_SMALL_SP, Spec.APPNAME_COLOR);
            col.addView(by, fill(Spec.dpi(c, 3)));
            body.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            button = new ContinueViews.Resume(c, glass, play, semibold, true);   // compact (user, 29 Oct)
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = Spec.dpi(c, 10);
            body.addView(button, bp);
            view.addView(body, fill(Spec.dpi(c, 4)));
        }

        private boolean focused, playing;

        /** Play / Pause and the logo together; the logo stays in colour while playing (user, 30 Oct). */
        void setFocus(boolean on) {
            focused = on;
            button.setOn(on, true);
            badge.setOn(on || playing, true);
        }

        boolean isShown() {
            return view.getVisibility() == View.VISIBLE;
        }

        /** Null title hides the card; an empty artist drops that line; logo is the app's circle or null. */
        void show(CharSequence eyebrowText, CharSequence titleText, CharSequence byText, boolean playing,
                  android.graphics.Bitmap logo) {
            if (titleText == null) {
                view.setVisibility(View.GONE);
                button.setOn(false, false);
                badge.setOn(false, false);
                focused = false;
                return;
            }
            this.playing = playing;
            badge.setIcon(logo);
            badge.setOn(focused || playing, true);
            eyebrow.setText(eyebrowText);
            title.setText(titleText);
            by.setText(byText);
            by.setVisibility(TextUtils.isEmpty(byText) ? View.GONE : View.VISIBLE);
            button.setLabel(playing ? pause : play, playing);
            bars.setPlaying(playing);
            bars.setVisibility(playing ? View.VISIBLE : View.GONE);
            view.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Three "live" bars while music plays (user, 30 Oct; like the Dynamic
     * Island). Android does not let a launcher read another app's audio, so,
     * like the iPhone's, they move on their own rhythm rather than the sound:
     * each bar eases toward a new random height, a few times a second.
     */
    static final class LiveBars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] h = {0.4f, 0.7f, 0.5f}, target = {0.8f, 0.3f, 0.9f};
        private final java.util.Random random = new java.util.Random();
        private boolean playing;

        LiveBars(Context c) {
            super(c);
            paint.setColor(Spec.TEXT);
        }

        void setPlaying(boolean on) {
            if (on == playing) return;
            playing = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), ht = getHeight(), bw = w / 4.2f, gap = (w - 3 * bw) / 2f;
            for (int i = 0; i < 3; i++) {
                if (playing) {
                    h[i] += (target[i] - h[i]) * 0.18f;
                    if (Math.abs(target[i] - h[i]) < 0.04f) target[i] = 0.2f + random.nextFloat() * 0.8f;
                }
                float bh = Math.max(bw, (playing ? h[i] : 0.25f) * ht), x = i * (bw + gap);
                float top = (ht - bh) / 2f;   // centred, like the Dynamic Island's
                c.drawRoundRect(x, top, x + bw, top + bh, bw / 2f, bw / 2f, paint);
            }
            if (playing && isShown()) postInvalidateOnAnimation();
        }
    }

    /**
     * Two-state switch with an icon and a small label per side (the calendar's
     * Next / Week, user 29 Oct). The chosen side is a white 92% pill with #111
     * content, like a focused capsule button; when the switch itself has focus
     * it gets the glass buttons' white halo and scales 1.06 from its right edge.
     */
    static final class Toggle extends View {
        interface Icon {
            void draw(Canvas c, float x, float y, float size, Paint p);
        }

        private static final float H = 22, PAD = 7, ICON = 10, ICON_GAP = 4, HALO_GAP = 3;

        private final String[] labels;
        private final Icon[] icons;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;
        private int selected;
        private float fSel;
        private ValueAnimator aSel;
        private final FocusAmount focus = new FocusAmount(this, Spec.CAP_COLOR_MS, Spec.EASE)
                .scale(1.06f, Spec.CAP_MS, false);

        Toggle(Context c, Typeface semibold, String[] labels, Icon[] icons) {
            super(c);
            this.labels = labels;
            this.icons = icons;
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(semibold);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                    c.getResources().getDisplayMetrics()));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        private float segmentW(int i) {
            return (PAD * 2 + ICON + ICON_GAP) * d + text.measureText(labels[i]);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(Math.round(segmentW(0) + segmentW(1)), Math.round(H * d));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            setPivotX(w);
            setPivotY(h / 2f);
        }

        int selected() {
            return selected;
        }

        void select(int i, boolean animate) {
            if (i == selected) return;
            selected = i;
            if (aSel != null) aSel.cancel();
            if (!animate) {
                fSel = i;
                invalidate();
                return;
            }
            aSel = Spec.tween(aSel, fSel, i, Spec.CAP_COLOR_MS, Spec.FOCUS, f -> {
                fSel = f;
                invalidate();
            });
        }

        void setOn(boolean value, boolean animate) {
            focus.set(value, animate);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f, fOn = focus.get();
            // Track: light glass inside the card, a little brighter with focus.
            r.set(0, 0, w, h);
            fill.setColor(Spec.white(Spec.lerp(0.12f, 0.2f, fOn)));
            c.drawRoundRect(r, rad, rad, fill);
            // The chosen side's pill slides between the two segments.
            float w0 = segmentW(0), inset = 2 * d;
            r.set(fSel * w0 + inset, inset, Spec.lerp(w0, w, fSel) - inset, h - inset);
            fill.setColor(Spec.white(Spec.CAP_FILL_FOCUSED));
            c.drawRoundRect(r, rad - inset, rad - inset, fill);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            float x = 0;
            for (int i = 0; i < 2; i++) {
                float chosen = i == 0 ? 1f - fSel : fSel;
                int color = GlassPanel.blend(Spec.TEXT, Spec.CAP_ICON_FOCUSED, chosen);
                float ix = x + PAD * d, iy = (h - ICON * d) / 2f;
                stroke.setColor(color);
                stroke.setStrokeWidth(1.3f * d);
                icons[i].draw(c, ix, iy, ICON * d, stroke);
                text.setColor(color);
                c.drawText(labels[i], ix + (ICON + ICON_GAP) * d, baseline, text);
                x += segmentW(i);
            }
            if (fOn > 0f) {
                // Drawn outside the view: the card and column keep clipChildren off.
                float g = HALO_GAP * d;
                r.set(-g, -g, w + g, h + g);
                stroke.setColor(Spec.white(0.85f * fOn));
                stroke.setStrokeWidth(1.5f * d);
                c.drawRoundRect(r, rad + g, rad + g, stroke);
            }
        }

        /** A clock: the next event. */
        static final Icon CLOCK = (c, x, y, s, p) -> {
            float cx = x + s / 2f, cy = y + s / 2f, rr = s / 2f - p.getStrokeWidth() / 2f;
            c.drawCircle(cx, cy, rr, p);
            c.drawLine(cx, cy, cx, cy - rr * 0.6f, p);
            c.drawLine(cx, cy, cx + rr * 0.45f, cy, p);
        };

        /** A calendar page: the week. */
        static final Icon WEEK = (c, x, y, s, p) -> {
            float sw = p.getStrokeWidth() / 2f;
            float left = x + sw, top = y + s * 0.12f + sw, right = x + s - sw, bottom = y + s - sw;
            c.drawRoundRect(left, top, right, bottom, s * 0.15f, s * 0.15f, p);
            float bar = top + s * 0.28f;
            c.drawLine(left, bar, right, bar, p);
            float dy = top + s * 0.58f;
            for (int i = 0; i < 3; i++) c.drawPoint(left + s * (0.22f + i * 0.24f), dy, p);
        };
    }

    /**
     * Google Calendar from the box's own synced calendar (CalendarContract; needs
     * READ_CALENDAR, granted by install.sh), in one of two views chosen with the
     * Next / Week switch in its top-right corner (user, 29 Oct): the next event,
     * or the week grouped by day. The choice is remembered. A local query:
     * refreshed when Home shows, when the calendar changes and once a minute for
     * the "in N min" text. The week list is cut to the lines that fit above the
     * row (setWeekLines).
     */
    static final class CalendarCard {
        /** Week lines (day headers count) with nothing stacked above the card. */
        static final int WEEK_LINES = 11;
        static final int NEXT = 0, WEEK = 1;
        private static final String PREF_VIEW = "calendar_view";

        final GlassPanel view;
        final Toggle toggle;
        private final Context ctx;
        private final Typeface regular, semibold;
        private final SharedPreferences prefs;
        private final TextView eyebrow, title, when;
        private final LinearLayout week;
        private final int lookaheadHours;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService exec = Executors.newSingleThreadExecutor();
        private List<Event> events;
        private int weekLines = WEEK_LINES;
        private boolean running;
        /** Home moves focus off the switch when the card hides. */
        Runnable onShownChanged;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                refresh();
                long now = System.currentTimeMillis();
                main.postDelayed(this, 60000L - now % 60000L + 50L);
            }
        };

        private final ContentObserver changed = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                refresh();
            }
        };

        CalendarCard(Context c, Glass glass, Typeface regular, Typeface semibold) {
            ctx = c;
            this.regular = regular;
            this.semibold = semibold;
            prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
            lookaheadHours = c.getResources().getInteger(R.integer.calendar_lookahead_hours);
            view = panel(c, glass, LinearLayout.VERTICAL);
            view.setGravity(Gravity.START);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setClipChildren(false);
            eyebrow = eyebrow(c, semibold, 9);
            header.addView(eyebrow, new LinearLayout.LayoutParams(0, -2, 1f));
            toggle = new Toggle(c, semibold,
                    new String[]{c.getString(R.string.calendar_view_next), c.getString(R.string.calendar_view_week)},
                    new Toggle.Icon[]{Toggle.CLOCK, Toggle.WEEK});
            toggle.select(prefs.getInt(PREF_VIEW, WEEK), false);
            header.addView(toggle);
            view.addView(header, fill(0));

            title = line(c, semibold, Spec.SIDE_LINE_SP, Spec.TEXT);
            view.addView(title, fill(Spec.dpi(c, 8)));
            when = line(c, regular, Spec.SIDE_SMALL_SP, Spec.APPNAME_COLOR);
            when.setFontFeatureSettings("tnum");
            view.addView(when, fill(Spec.dpi(c, 4)));
            week = new LinearLayout(c);
            week.setOrientation(LinearLayout.VERTICAL);
            view.addView(week, fill(Spec.dpi(c, 2)));
        }

        boolean isShown() {
            return view.getVisibility() == View.VISIBLE;
        }

        boolean isWeek() {
            return toggle.selected() == WEEK;
        }

        /** Next or Week; remembered. The card expands into Week and collapses into Next. */
        void setView(int which) {
            if (which == toggle.selected()) return;
            toggle.select(which, true);
            prefs.edit().putInt(PREF_VIEW, which).apply();
            int from = view.getHeight();
            bind(events);
            animateHeight(from);
        }

        private ValueAnimator aHeight;

        /** Slides the card from its old height to the new content's, fading the new content in. */
        private void animateHeight(int from) {
            if (!isShown() || from <= 0 || view.getWidth() <= 0) return;
            view.measure(View.MeasureSpec.makeMeasureSpec(view.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int to = view.getMeasuredHeight();
            if (to == from) return;
            for (View v : new View[]{title, when, week}) {
                if (v.getVisibility() != View.VISIBLE) continue;
                v.setAlpha(0f);
                v.animate().alpha(1f).setStartDelay(to > from ? Spec.LABEL_DELAY_MS : 0)
                        .setDuration(Spec.TRANSFORM_MS).setInterpolator(Spec.EASE).start();
            }
            // The content is clipped to the sliding height; the switch's halo sits inside the padding.
            final ViewGroup.LayoutParams lp = view.getLayoutParams();
            final android.graphics.Rect clip = new android.graphics.Rect();
            aHeight = Spec.tween(aHeight, from, to, Spec.TRANSFORM_MS, Spec.FOCUS, f -> {
                lp.height = Math.round(f);
                clip.set(0, 0, view.getWidth(), lp.height);
                view.setClipBounds(clip);
                view.setLayoutParams(lp);
            });
            aHeight.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    view.setClipBounds(null);
                    view.setLayoutParams(lp);
                }
            });
        }

        void flipView() {
            setView(isWeek() ? NEXT : WEEK);
        }

        void start() {
            if (running || !allowed()) return;
            running = true;
            ctx.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, changed);
            main.post(tick);
        }

        void stop() {
            if (!running) return;
            running = false;
            main.removeCallbacks(tick);
            ctx.getContentResolver().unregisterContentObserver(changed);
        }

        /** How many week lines (day headers count) fit; fewer when cards stack above this one. */
        void setWeekLines(int n) {
            n = Math.max(2, n);
            if (n == weekLines) return;
            weekLines = n;
            if (running) bind(events);
        }

        private boolean allowed() {
            return ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void refresh() {
            exec.execute(() -> {
                final List<Event> list = query();
                main.post(() -> {
                    if (!running) return;
                    events = list;
                    bind(list);
                });
            });
        }

        private static final class Event {
            String title;
            long begin, end;
            boolean allDay;
            long day;   // local midnight of the day it belongs to
        }

        /** Events that have not ended, within the lookahead, by start; declined ones skipped. */
        private List<Event> query() {
            List<Event> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, now);
            ContentUris.appendId(b, now + lookaheadHours * 3600000L);
            String[] cols = {
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            };
            try (Cursor c = ctx.getContentResolver().query(b.build(), cols,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC")) {
                long today = dayOf(now, false);
                while (c != null && c.moveToNext()) {
                    if (c.getInt(4) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                    if (c.getLong(2) <= now || TextUtils.isEmpty(c.getString(0))) continue;
                    Event e = new Event();
                    e.title = c.getString(0);
                    e.begin = c.getLong(1);
                    e.end = c.getLong(2);
                    e.allDay = c.getInt(3) != 0;
                    // A multi-day event that started earlier belongs to today.
                    e.day = Math.max(dayOf(e.begin, e.allDay), today);
                    out.add(e);
                }
            } catch (RuntimeException ex) {
                Log.d("CalendarCard", "calendar unavailable: " + ex.getClass().getSimpleName());
            }
            return out;
        }

        /** Local midnight of t; all-day events are stored at UTC midnight of their date. */
        private static long dayOf(long t, boolean allDay) {
            Calendar src = Calendar.getInstance(allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
            src.setTimeInMillis(t);
            Calendar local = Calendar.getInstance();
            local.clear();
            local.set(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH));
            return local.getTimeInMillis();
        }

        private void bind(List<Event> list) {
            if (list == null || list.isEmpty()) {
                toggle.setOn(false, false);
                setShown(false);
                return;
            }
            eyebrow.setText(R.string.calendar_title);
            if (isWeek()) {
                title.setVisibility(View.GONE);
                when.setVisibility(View.GONE);
                bindWeek(list);
            } else {
                week.setVisibility(View.GONE);
                Event next = list.get(0);
                for (Event e : list) {
                    if (!e.allDay) {
                        next = e;
                        break;
                    }
                }
                bindNext(next);
            }
            setShown(true);
        }

        private void setShown(boolean shown) {
            if (shown == isShown()) return;
            view.setVisibility(shown ? View.VISIBLE : View.GONE);
            if (onShownChanged != null) onShownChanged.run();
        }

        private void bindNext(Event e) {
            long now = System.currentTimeMillis();
            if (!e.allDay && e.begin <= now) {
                when.setText(ctx.getString(R.string.calendar_now_until, time(e.end)));
            } else {
                String day = dayLabel(e.day);
                long mins = (e.begin - now + 59999L) / 60000L;
                String at = e.allDay ? ctx.getString(R.string.calendar_all_day) : time(e.begin);
                when.setText(!e.allDay && mins <= 60
                        ? at + " · " + ctx.getString(R.string.calendar_in_min, mins)
                        : day.isEmpty() ? at : day + " · " + at);
            }
            title.setText(e.title);
            title.setVisibility(View.VISIBLE);
            when.setVisibility(View.VISIBLE);
        }

        /** By day: all-day events first, then by time, cut at weekLines. */
        private void bindWeek(List<Event> list) {
            week.removeAllViews();
            List<Event> sorted = new ArrayList<>(list);
            Collections.sort(sorted, (a, b) -> a.day != b.day ? Long.compare(a.day, b.day)
                    : a.allDay != b.allDay ? (a.allDay ? -1 : 1) : Long.compare(a.begin, b.begin));
            int lines = 0;
            long shownDay = -1;
            for (Event e : sorted) {
                boolean header = e.day != shownDay;
                if (lines + (header ? 2 : 1) > weekLines) break;
                if (header) {
                    week.addView(dayHeader(e.day, week.getChildCount() == 0));
                    shownDay = e.day;
                    lines++;
                }
                week.addView(row(e));
                lines++;
            }
            week.setVisibility(View.VISIBLE);
        }

        private View dayHeader(long day, boolean first) {
            TextView t = text(ctx, semibold, 8.5f, ContinueViews.alpha(0.62f), 6, 1);
            t.setLetterSpacing(0.1f);
            t.setAllCaps(true);
            t.setIncludeFontPadding(false);
            String label = dayLabel(day);
            t.setText(label.isEmpty() ? ctx.getString(R.string.calendar_today) : label);
            t.setLayoutParams(fill(Spec.dpi(ctx, first ? 6 : 8)));
            return t;
        }

        /** "13:30  Title" (or "All day  Title"): time in a fixed column, title ellipsized. */
        private View row(Event e) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = line(ctx, regular, Spec.SIDE_SMALL_SP, Spec.APPNAME_COLOR);
            t.setFontFeatureSettings("tnum");
            t.setText(e.allDay ? ctx.getString(R.string.calendar_all_day) : time(e.begin));
            r.addView(t, new LinearLayout.LayoutParams(Spec.dpi(ctx, 44), -2));
            TextView n = line(ctx, regular, Spec.SIDE_SMALL_SP, Spec.TEXT);
            n.setText(e.title);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setLayoutParams(fill(Spec.dpi(ctx, 4)));
            return r;
        }

        private String time(long t) {
            String pattern = android.text.format.DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a";
            return new SimpleDateFormat(pattern, Locale.ENGLISH).format(new Date(t));
        }

        /** "" for today, "Tomorrow", else "Fri 31" (English, like the rest of Home). */
        private String dayLabel(long day) {
            long today = dayOf(System.currentTimeMillis(), false);
            if (day <= today) return "";
            Calendar tomorrow = Calendar.getInstance();
            tomorrow.setTimeInMillis(today);
            tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            if (day == tomorrow.getTimeInMillis()) return ctx.getString(R.string.calendar_tomorrow_day);
            return new SimpleDateFormat("EEE d", Locale.ENGLISH).format(new Date(day));
        }
    }

    /**
     * The frosted band across the top (user, 29 Oct): the art's ready-made blur
     * (no live blur) under a black tint, fading out over its bottom edge. Shown
     * only while the grid has scrolled up under the top bar, so the tiles slide
     * under it instead of the clock, weather and capsule.
     */
    static final class FrostBand extends View {
        private final Glass glass;
        private final Glass.Shape shape = new Glass.Shape();
        private final Paint tint = new Paint();
        private final Paint fade = new Paint();
        private final float fadePx;

        FrostBand(Context c, Glass glass) {
            super(c);
            this.glass = glass;
            fadePx = Spec.dp(c, Spec.TOP_BAND_FADE);
            tint.setColor(Color.argb(Math.round(Spec.TOP_BAND_TINT * 255), 0, 0, 0));
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            glass.register(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shape.set(w, h, 0f);
            fade.setShader(new LinearGradient(0, h - fadePx, 0, h, Color.BLACK, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            int save = c.saveLayer(0, 0, getWidth(), getHeight(), null);
            glass.drawArtBlur(c, this, shape);
            c.drawRect(0, 0, getWidth(), getHeight(), tint);
            c.drawRect(0, 0, getWidth(), getHeight(), fade);
            c.restoreToCount(save);
        }
    }
}
