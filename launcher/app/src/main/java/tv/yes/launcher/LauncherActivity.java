package tv.yes.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen per design/LAUNCHER-DESIGN.md. Focus is a small state machine
 * (row, capsule, grid, Inputs menu) driven from dispatchKeyEvent, not Android's
 * view focus search, so every D-pad press has exactly one defined result and no
 * press is ever ignored because an animation is running.
 */
public class LauncherActivity extends Activity {

    private static final String TAG = "LauncherActivity";
    private static final String YES_PACKAGE = "il.co.yes.yesplus";
    private static final String TV_SETTINGS_PACKAGE = "com.android.tv.settings";
    private static final String PREFS = "launcher";
    private static final String PREF_ROW_ORDER = "row_order";
    private static final int CAP_INPUTS = 0, CAP_WIDGETS = 1, CAP_SETTINGS = 2;   // Settings last (user, 30 Oct)
    // Old and new colours alternate, so two neighbours never share a hue (user, 30 Oct).
    private static final int[] WALLPAPERS = {
            R.raw.wallpaper_1, R.raw.wallpaper_7, R.raw.wallpaper_2, R.raw.wallpaper_8,
            R.raw.wallpaper_3, R.raw.wallpaper_9, R.raw.wallpaper_4, R.raw.wallpaper_10,
            R.raw.wallpaper_5, R.raw.wallpaper_11, R.raw.wallpaper_6, R.raw.wallpaper_12
    };

    private enum Zone { ROW, CAPSULE, GRID, MENU, RESUME, NOW, SPEAKER, CALENDAR, WIDGETS }

    /** A grid entry: an app, or the Inputs / Settings system tile. */
    private static final class Entry {
        static final int APP = 0, INPUTS = 1, SETTINGS = 2;
        final int kind;
        final AppInfo app;

        Entry(int kind, AppInfo app) {
            this.kind = kind;
            this.app = app;
        }
    }

    private static final class TitleBlock {
        GlassPanel view;
        ImageView logo;
        TextView title;
        TextView appName;
        LinearLayout header;       // continue layout only: eyebrow and the app badge
        TextView eyebrow;
        ContinueViews.AppBadge badge;
        TextView episode;
        LinearLayout progressRow;
        ContinueViews.Track track;
        TextView remaining;
        ContinueViews.Resume resume;
        ContinueWatching.Info cont;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private float density;
    private int screenW, screenH;
    private Typeface regular, medium, semibold;

    private FrameLayout root, titleLayer, page, strip, gridLayer;
    private View topBand;
    private ArtView art;
    private Glass glass;
    private GlassPanel capsule;
    private WeatherClock weather;
    private final GlassPanel.CapButton[] caps = new GlassPanel.CapButton[3];   // indexed by CAP_*
    private final TitleBlock[] titles = new TitleBlock[3];
    private TitleBlock currentTitle;
    private String currentTitleSig;

    private List<AppInfo> rowApps = new ArrayList<>();
    private final List<TileView> rowTiles = new ArrayList<>();
    private final List<Entry> gridEntries = new ArrayList<>();
    private final List<TileView> gridTiles = new ArrayList<>();

    private Zone zone = Zone.ROW;
    private int rowIndex, rowScroll, capIndex, gridIndex;
    private boolean gridOpen;
    private boolean selectArmed;

    private GlassPanel menu;
    private final List<GlassPanel.MenuRow> menuRows = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private int menuIndex;
    private Zone menuReturn = Zone.ROW;

    private TopShelfManager topShelf;
    private LiveMedia liveMedia;
    private CastClient cast;
    private GlassCards.MediaCard nowCard, speakerCard;
    private GlassCards.CalendarCard calendarCard;
    private boolean nowEnabled, speakerEnabled, calendarEnabled;
    private String nowPkg;                 // music app in the now playing card
    private CastClient.Playing castPlaying;
    private String launchedPkg;            // last app opened from Home: its card is re-read on return
    private String artPkg;            // row app whose art is wanted
    private boolean artIsFallback;
    private int wallpaperIndex;
    private long wallpaperIntervalMs;
    private static final int WALLPAPER_FADE_MS = 10000;   // user, 30 Oct

    private final Runnable wallpaperTick = new Runnable() {
        @Override public void run() {
            wallpaperIndex = (wallpaperIndex + 1) % WALLPAPERS.length;
            if (artIsFallback && artPkg != null) showFallback(artPkg);
            main.postDelayed(this, wallpaperIntervalMs);
        }
    };

    /** MediaListener saved a snapshot (playback paused or stopped): the focused app's card follows it. */
    private final SharedPreferences.OnSharedPreferenceChangeListener snapshotSaved = (prefs, key) -> {
        if (key != null && key.equals(artPkg)) refreshArt();
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        regular = font(R.font.outfit_regular, 400, "sans-serif", Typeface.NORMAL);
        medium = font(R.font.outfit_medium, 500, "sans-serif-medium", Typeface.NORMAL);
        semibold = font(R.font.outfit_semibold, 600, "sans-serif-medium", Typeface.BOLD);
        wallpaperIntervalMs = Math.max(5000L, getResources().getInteger(R.integer.wallpaper_interval_ms));

        root = new FrameLayout(this);
        root.setClipChildren(false);
        setContentView(root);

        art = new ArtView(this);
        root.addView(art, new FrameLayout.LayoutParams(-1, -1));
        glass = new Glass(art, density);
        art.setFrameListener(glass::invalidateAll);

        titleLayer = new FrameLayout(this);
        titleLayer.setClipChildren(false);
        root.addView(titleLayer, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < titles.length; i++) titles[i] = buildTitleBlock();
        buildCards();

        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, screenH));
        strip = new FrameLayout(this);
        strip.setClipChildren(false);
        gridLayer = new FrameLayout(this);
        gridLayer.setClipChildren(false);
        gridLayer.setAlpha(0f);
        gridLayer.setVisibility(View.INVISIBLE);
        page.addView(strip);
        page.addView(gridLayer);
        topBand = new GlassCards.FrostBand(this, glass);
        topBand.setAlpha(0f);
        topBand.setVisibility(View.INVISIBLE);
        root.addView(topBand, new FrameLayout.LayoutParams(-1, px(Spec.TOP_BAND_H)));

        buildCapsule();
        weather = new WeatherClock(this, regular);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        wp.leftMargin = px(Spec.EDGE);
        wp.topMargin = px(Spec.CAPSULE_TOP);
        root.addView(weather.view, wp);

        topShelf = TopShelfManager.get(this);
        reloadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);

        getSharedPreferences(ContinueWatching.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(snapshotSaved);
        PrefetchWorker.schedule(this);
    }

    /**
     * Outfit at this weight, with Rubik (same weight) for the Hebrew Outfit lacks:
     * Hebrew app names and calendar events then match the Latin text instead of
     * the system's heavier Hebrew font (user, 29 Oct).
     */
    private Typeface font(int res, int weight, String fallback, int style) {
        try {
            android.graphics.fonts.Font latin = new android.graphics.fonts.Font.Builder(getResources(), res).build();
            android.graphics.fonts.Font hebrew = new android.graphics.fonts.Font.Builder(getResources(), R.font.rubik)
                    .setWeight(weight).setFontVariationSettings("'wght' " + weight).build();
            return new Typeface.CustomFallbackBuilder(new android.graphics.fonts.FontFamily.Builder(latin).build())
                    .addCustomFallback(new android.graphics.fonts.FontFamily.Builder(hebrew).build())
                    .setSystemFallback("sans-serif")
                    .build();
        } catch (Throwable ignore) { }
        try {
            Typeface t = getResources().getFont(res);
            if (t != null) return t;
        } catch (Throwable ignore) { }
        return Typeface.create(fallback, style);
    }

    private float dp(float v) {
        return v * density;
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }

    private TitleBlock buildTitleBlock() {
        TitleBlock b = new TitleBlock();
        b.view = new GlassPanel(this, glass, LinearLayout.VERTICAL, Spec.CARD_RADIUS);
        b.view.setAlpha(0f);
        b.view.setClipChildren(false);
        b.view.setClipToPadding(false);
        int maxW = px(Spec.TITLE_MAX_W);

        // Eyebrow on the left, the app's logo in a circle at the card's top right.
        b.header = new LinearLayout(this);
        b.header.setOrientation(LinearLayout.HORIZONTAL);
        b.header.setGravity(Gravity.CENTER_VERTICAL);
        b.eyebrow = GlassCards.eyebrow(this, semibold, 10);
        b.eyebrow.setEllipsize(TextUtils.TruncateAt.END);
        b.header.addView(b.eyebrow, new LinearLayout.LayoutParams(0, -2, 1f));
        b.badge = new ContinueViews.AppBadge(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(px(BADGE_DP), px(BADGE_DP));
        bp.leftMargin = px(12);
        b.header.addView(b.badge, bp);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.bottomMargin = px(6);
        b.view.addView(b.header, ep);

        b.logo = new ImageView(this);
        b.logo.setAdjustViewBounds(true);
        b.logo.setMaxWidth(maxW);
        b.logo.setMaxHeight(px(Spec.LOGO_MAX_H));
        b.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.view.addView(b.logo, wrapLp(0));

        b.title = text(semibold, Spec.TITLE_SP, Spec.TEXT, 14, 2);
        b.title.setLetterSpacing(Spec.TITLE_TRACKING);
        b.title.setLineHeight(Math.round(b.title.getTextSize() * Spec.TITLE_LEADING));
        b.title.setMaxWidth(maxW);
        b.title.setMaxLines(2);
        b.title.setEllipsize(TextUtils.TruncateAt.END);
        b.view.addView(b.title, wrapLp(0));

        b.appName = text(regular, Spec.APPNAME_SP, Spec.APPNAME_COLOR, 8, 1);
        b.appName.setLetterSpacing(Spec.APPNAME_TRACKING);
        b.appName.setIncludeFontPadding(false);
        b.appName.setMaxWidth(maxW);
        b.appName.setSingleLine(true);
        b.view.addView(b.appName, wrapLp(px(Spec.TITLE_GAP)));

        b.episode = text(medium, 13, ContinueViews.alpha(0.90f), 8, 1);
        b.episode.setIncludeFontPadding(false);
        b.episode.setMaxWidth(maxW);
        b.episode.setSingleLine(true);
        b.episode.setEllipsize(TextUtils.TruncateAt.END);
        b.view.addView(b.episode, wrapLp(px(7)));

        b.progressRow = new LinearLayout(this);
        b.progressRow.setOrientation(LinearLayout.HORIZONTAL);
        b.progressRow.setGravity(Gravity.CENTER_VERTICAL);
        b.track = new ContinueViews.Track(this);
        b.progressRow.addView(b.track, new LinearLayout.LayoutParams(px(Spec.CARD_TRACK_W), px(4)));
        b.remaining = text(regular, 11, Spec.APPNAME_COLOR, 8, 1);
        b.remaining.setFontFeatureSettings("tnum");
        b.remaining.setIncludeFontPadding(false);
        LinearLayout.LayoutParams rp = wrapLp(0);
        rp.leftMargin = px(10);
        b.progressRow.addView(b.remaining, rp);
        b.view.addView(b.progressRow, wrapLp(px(8)));

        b.resume = new ContinueViews.Resume(this, glass, getString(R.string.resume), semibold);
        b.view.addView(b.resume, wrapLp(px(12)));
        showContinue(b, false);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = px(Spec.EDGE);
        lp.bottomMargin = px(Spec.TITLE_BOTTOM);
        titleLayer.addView(b.view, lp);
        return b;
    }

    /** A title-block line: typeface, size, colour and its drop shadow (blur and offset in dp). */
    private TextView text(Typeface face, float sp, int color, float shadowBlur, float shadowDy) {
        return GlassCards.text(this, face, sp, color, shadowBlur, shadowDy);
    }

    /**
     * The cards stack in one column under the capsule (user, 29 Oct: now playing
     * on the right, not over the continue card): now playing, speaker, calendar.
     * Each keeps CARD_GAP below it; a hidden card takes no space. All in the
     * title layer, so they fade with it when the grid opens.
     */
    private void buildCards() {
        liveMedia = new LiveMedia(this);
        if (getResources().getBoolean(R.bool.speaker_card)) cast = new CastClient(this);   // its thread only when used
        nowEnabled = getResources().getBoolean(R.bool.now_playing_card);
        speakerEnabled = getResources().getBoolean(R.bool.speaker_card);
        calendarEnabled = getResources().getBoolean(R.bool.calendar_card);

        loadWidgetPrefs();
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        nowCard = new GlassCards.MediaCard(this, glass, regular, semibold);
        speakerCard = new GlassCards.MediaCard(this, glass, regular, semibold);
        calendarCard = new GlassCards.CalendarCard(this, glass, regular, semibold);
        calendarCard.onShownChanged = () -> {
            if (zone == Zone.CALENDAR && !calendarCard.isShown()) downFrom(Zone.CALENDAR);
        };
        for (View card : new View[]{nowCard.view, speakerCard.view, calendarCard.view}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(Spec.SIDE_CARD_W), -2);
            lp.bottomMargin = px(Spec.CARD_GAP);
            card.setLayoutParams(lp);
        }
        addWidget = new ContinueViews.Resume(this, glass, getString(R.string.add_widget), semibold, true);
        addWidget.setPlusIcon(true);
        addWidget.setVisibility(View.GONE);
        applyColumnOrder();
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rp.rightMargin = px(Spec.CAPSULE_RIGHT);
        rp.topMargin = px(Spec.CAPSULE_TOP + Spec.CAP_BUTTON + 2 * Spec.CAPSULE_PADDING + Spec.CARD_GAP);
        titleLayer.addView(column, rp);
    }

    /** The calendar's week list gives up lines for each media card in the column, so it stays clear of the row. */
    private void fitCalendar() {
        int others = (nowCard.isShown() ? 1 : 0) + (speakerCard.isShown() ? 1 : 0);
        calendarCard.setWeekLines(GlassCards.CalendarCard.WEEK_LINES - 4 * others);
    }

    private static LinearLayout.LayoutParams wrapLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = topMargin;
        return lp;
    }

    private static final float BADGE_DP = 26;
    private final java.util.HashMap<String, Bitmap> badges = new java.util.HashMap<>();

    /** The app's icon as a circle for the continue card's badge; cached per app. */
    private Bitmap badgeFor(String pkg) {
        Bitmap b = badges.get(pkg);
        if (b != null) return b;
        android.graphics.drawable.Drawable icon = null;
        for (AppInfo a : installedApps) {
            if (a.packageName.equals(pkg)) icon = a.icon;
        }
        if (icon == null) {
            try {
                icon = getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        b = Plates.circle(icon, px(BADGE_DP) * 2);   // twice the size: crisp when scaled by focus
        badges.put(pkg, b);
        return b;
    }

    private void buildCapsule() {
        capsule = new GlassPanel(this, glass, LinearLayout.HORIZONTAL, -1);
        int[] icons = {R.drawable.ic_inputs, R.drawable.ic_widgets, R.drawable.ic_settings};
        int b = px(Spec.CAP_BUTTON);
        for (int i = 0; i < caps.length; i++) {
            caps[i] = new GlassPanel.CapButton(this, getDrawable(icons[i]));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(b, b);
            if (i > 0) cp.leftMargin = px(Spec.CAPSULE_GAP);
            capsule.addView(caps[i], cp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = px(Spec.CAPSULE_TOP);
        lp.rightMargin = px(Spec.CAPSULE_RIGHT);
        root.addView(capsule, lp);

        // Tooltip under the focused capsule button (user, 29 Oct): a small glass pill.
        capTip = new GlassPanel(this, glass, LinearLayout.HORIZONTAL, -1);
        capTip.setPadding(px(10), px(4), px(10), px(4));
        capTipText = text(medium, 11, Spec.TEXT, 6, 1);
        capTipText.setIncludeFontPadding(false);
        capTip.addView(capTipText);
        capTip.setAlpha(0f);
        capTip.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.topMargin = px(Spec.CAPSULE_TOP + Spec.CAP_BUTTON + 2 * Spec.CAPSULE_PADDING + 6);
        root.addView(capTip, tp);
    }

    // ------------------------------------------------------------- apps/tiles

    private float tileX(int column) {
        return (Spec.EDGE + column * (Spec.TILE_W + Spec.TILE_GAP)) * density;
    }

    private float rowTop() {
        return screenH - (Spec.ROW_BOTTOM + Spec.TILE_H) * density;
    }

    /** Distance from one grid row's top to the next. */
    private float gridPitch() {
        return (Spec.TILE_H + Spec.GRID_ROW_GAP) * density;
    }

    private float gridRowTop(int r) {
        return rowTop() + (r + 1) * gridPitch() + (Spec.ALL_APPS_GAP + Spec.ALL_APPS_TITLE_H) * density;
    }

    /** Apps and their pre-rendered plates, built off the main thread. */
    private static final class Loaded {
        List<AppInfo> all, row;
        final HashMap<String, Bitmap> plates = new HashMap<>();
        Bitmap add, inputs, settings;
    }

    private final ExecutorService appsExec = Executors.newSingleThreadExecutor();
    private int loadGen;

    /** Loads apps and renders plates in the background (banners are slow to decode), then rebuilds tiles. */
    private void reloadApps() {
        final int gen = ++loadGen;
        appsExec.execute(() -> {
            long t0 = SystemClock.uptimeMillis();
            Loaded l = new Loaded();
            l.all = AppLoader.loadLeanbackApps(this);
            l.row = AppLoader.buildDock(this, rowOrder(), l.all);
            for (AppInfo a : l.all) l.plates.put(a.packageName, Plates.forApp(this, a));
            l.add = Plates.forSystem(this, getDrawable(R.drawable.ic_add));
            l.inputs = Plates.forSystem(this, getDrawable(R.drawable.ic_inputs));
            l.settings = Plates.forSystem(this, getDrawable(R.drawable.ic_settings));
            ContinueWatching.probe(this, new String[]{"com.netflix.ninja", "com.disney.disneyplus", YES_PACKAGE});
            Log.d(TAG, "apps loaded in " + (SystemClock.uptimeMillis() - t0) + " ms");
            main.post(() -> {
                if (gen == loadGen && !isDestroyed()) applyApps(l);
            });
        });
    }

    private void applyApps(Loaded l) {
        endMove();
        String rowPkg = rowIndex < rowApps.size() ? rowApps.get(rowIndex).packageName : null;
        boolean onAddTile = !rowTiles.isEmpty() && rowIndex == rowTiles.size() - 1;
        String gridKey = gridIndex < gridEntries.size() ? entryKey(gridEntries.get(gridIndex)) : null;
        if (zone == Zone.MENU) closeMenu(false);

        List<AppInfo> all = l.all;
        installedApps = l.all;
        rowApps = l.row;
        if (focusAfterLoad != null) {
            rowPkg = focusAfterLoad;
            onAddTile = false;
            focusAfterLoad = null;
        }

        gridEntries.clear();
        for (AppInfo a : all) {
            if (!TV_SETTINGS_PACKAGE.equals(a.packageName)) gridEntries.add(new Entry(Entry.APP, a));
        }
        gridEntries.add(new Entry(Entry.INPUTS, null));
        gridEntries.add(new Entry(Entry.SETTINGS, null));

        for (TileView t : rowTiles) t.detach();
        for (TileView t : gridTiles) t.detach();
        rowTiles.clear();
        gridTiles.clear();
        strip.removeAllViews();
        gridLayer.removeAllViews();
        TextView allApps = text(semibold, 18, Spec.TEXT, 8, 1);
        allApps.setText(R.string.all_apps);
        allApps.setLetterSpacing(Spec.TITLE_TRACKING);
        allApps.setIncludeFontPadding(false);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2);
        ap.leftMargin = Math.round(tileX(0));
        gridLayer.addView(allApps, ap);

        for (int i = 0; i < rowApps.size(); i++) {
            AppInfo a = rowApps.get(i);
            addTile(strip, rowTiles, l.plates.get(a.packageName), a.label, i, 0);
        }
        // The last tile is always "Add apps".
        addTile(strip, rowTiles, l.add, getString(R.string.add_apps), rowApps.size(), 0);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                Math.round(tileX(rowTiles.size()) + Spec.EDGE * density), px(Spec.TILE_H));
        sp.topMargin = Math.round(rowTop());
        strip.setLayoutParams(sp);

        for (int i = 0; i < gridEntries.size(); i++) {
            Entry e = gridEntries.get(i);
            int col = i % Spec.ROW_VISIBLE;
            int top = Math.round(Spec.ALL_APPS_TITLE_H * density + i / Spec.ROW_VISIBLE * gridPitch());
            if (e.kind == Entry.APP) {
                addTile(gridLayer, gridTiles, l.plates.get(e.app.packageName), e.app.label, col, top);
            } else if (e.kind == Entry.INPUTS) {
                // It opens Live TV (the yes app) on this box, which has no HDMI inputs (user, 30 Oct).
                addTile(gridLayer, gridTiles, l.inputs, getString(R.string.live_tv), col, top);
            } else {
                addTile(gridLayer, gridTiles, l.settings, getString(R.string.settings), col, top);
            }
        }
        int gridRows = (gridEntries.size() + Spec.ROW_VISIBLE - 1) / Spec.ROW_VISIBLE;
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1,
                Math.round(Spec.ALL_APPS_TITLE_H * density + gridRows * gridPitch()));
        gp.topMargin = Math.round(gridRowTop(0) - Spec.ALL_APPS_TITLE_H * density);
        gridLayer.setLayoutParams(gp);
        page.getLayoutParams().height = Math.max(screenH, Math.round(gridRowTop(gridRows) + Spec.ROW_BOTTOM * density));
        page.requestLayout();

        // Restore indices by identity; fall back to the nearest valid slot.
        rowIndex = onAddTile ? rowTiles.size() - 1 : clamp(indexOfRow(rowPkg, rowIndex), rowTiles.size());
        gridIndex = clamp(indexOfGrid(gridKey, gridIndex), gridEntries.size());
        keepRowIndexVisible(false);

        if (zone == Zone.ROW) rowTiles.get(rowIndex).setFocusedTile(true, false);
        if (zone == Zone.GRID) gridTiles.get(gridIndex).setFocusedTile(true, false);
        if (zone == Zone.CAPSULE) focusCapsule(capIndex, false);
        if (gridOpen) {
            float y = Spec.GRID_FOCUS_TOP * density - gridRowTop(gridIndex / Spec.ROW_VISIBLE);
            page.animate().cancel();
            page.setTranslationY(y);
            showTopBand(y);
        }
        if (!rowApps.isEmpty() && (artPkg == null || indexOfRow(artPkg, -1) < 0)) {
            artPkg = null;
            requestArt(rowIndex);
        }
    }

    private void addTile(FrameLayout parent, List<TileView> into, Bitmap plate, CharSequence label,
                         int column, int top) {
        TileView t = new TileView(this, glass, plate, label, medium);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(Spec.TILE_W), px(Spec.TILE_H));
        lp.leftMargin = Math.round(tileX(column));
        lp.topMargin = top;
        parent.addView(t, lp);
        into.add(t);
    }

    private static String entryKey(Entry e) {
        return e.kind == Entry.APP ? e.app.packageName : ("#" + e.kind);
    }

    private int indexOfRow(String pkg, int fallback) {
        if (pkg != null) for (int i = 0; i < rowApps.size(); i++) {
            if (rowApps.get(i).packageName.equals(pkg)) return i;
        }
        return fallback;
    }

    private int indexOfGrid(String key, int fallback) {
        if (key != null) for (int i = 0; i < gridEntries.size(); i++) {
            if (entryKey(gridEntries.get(i)).equals(key)) return i;
        }
        return fallback;
    }

    private static int clamp(int i, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(i, size - 1));
    }

    // ------------------------------------------------------------- D-pad

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean select = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                || kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean back = kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE;
        if (!select && !nav && !back) return super.dispatchKeyEvent(e);
        main.removeCallbacks(doze);
        main.postDelayed(doze, IDLE_MS);
        if (dozing) {
            // The remote is back: the first press only shows focus again, where it was.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                dozing = false;
                showFocus(true);
            }
            selectArmed = false;
            return true;
        }

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (select) {
                if (e.getRepeatCount() == 0) {
                    selectArmed = true;
                    // Hold Select on a row app to reorder. A timer, not key repeats: the Home-key
                    // accessibility helper filters keys, and some firmware drops repeats then.
                    if (zone == Zone.ROW && !moving && rowIndex < rowApps.size()) {
                        main.postDelayed(holdToMove, Spec.HOLD_TO_MOVE_MS);
                    } else if (zone == Zone.NOW) {
                        // Hold Play / Pause: its ring fills, then the app opens (user, 30 Oct).
                        main.postDelayed(startHoldFill, HOLD_FILL_DELAY_MS);
                    } else if (zone == Zone.RESUME || zone == Zone.CALENDAR) {
                        // Hold Select on a widget: edit widgets, like holding an app.
                        main.postDelayed(holdToEditWidgets, Spec.HOLD_TO_MOVE_MS);
                    }
                }
            } else if (back) {
                if (e.getRepeatCount() == 0) onBackKey();
            } else {
                move(kc);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && select) {
            // Commit on release, and only for a press that started here.
            main.removeCallbacks(holdToMove);
            main.removeCallbacks(holdToEditWidgets);
            cancelHoldFill();
            boolean armed = selectArmed;
            selectArmed = false;
            if (armed && !e.isCanceled()) {
                if (moving && zone == Zone.ROW) openEditMenu();
                else activate();
            }
        }
        return true;
    }

    private static final long IDLE_MS = 10000;   // user, 30 Oct: 10 s
    private boolean dozing;

    /**
     * IDLE_MS without a key: the focus look goes away (user, 30 Oct), except
     * on an app tile (the row or the grid), in a menu and in edit mode.
     */
    private final Runnable doze = () -> {
        if (zone == Zone.ROW || zone == Zone.GRID || zone == Zone.MENU || zone == Zone.WIDGETS) return;
        dozing = true;
        showFocus(false);
    };

    /** Shows or hides the current stop's focus look without moving focus. */
    private void showFocus(boolean on) {
        switch (zone) {
            case CAPSULE:
                if (on) focusCapsule(capIndex, true);
                else capsuleOff();
                break;
            case RESUME:
                if (on) focusResume();
                else leaveResume();
                break;
            case NOW:
            case SPEAKER:
            case CALENDAR:
                stopOn(zone, on);
                break;
            default:
                break;
        }
    }

    private void move(int kc) {
        if (rowTiles.isEmpty() && (zone == Zone.ROW || zone == Zone.GRID)) return;   // apps still loading
        main.removeCallbacks(holdToMove);
        main.removeCallbacks(holdToEditWidgets);
        cancelHoldFill();
        selectArmed = false;
        if (moving && zone == Zone.ROW) {
            moveTile(kc);
            return;
        }
        switch (zone) {
            case ROW: moveRow(kc); break;
            case CAPSULE: moveCapsule(kc); break;
            case RESUME: moveResume(kc); break;
            case NOW:
            case SPEAKER:
            case CALENDAR: moveStop(kc); break;
            case WIDGETS: moveWidgetFocus(kc); break;
            case GRID: moveGrid(kc); break;
            case MENU: moveMenu(kc); break;
        }
    }

    private void moveRow(int kc) {
        int n = rowTiles.size();
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (rowIndex > 0) focusRow(rowIndex - 1);
        } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (rowIndex < n - 1) focusRow(rowIndex + 1);
        } else if (kc == KeyEvent.KEYCODE_DPAD_UP) {
            if (hasResume()) {
                focusRowTile(false);
                focusResume();
            } else {
                focusRowTile(false);
                upFromLeft();
            }
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            openGrid(rowIndex - rowScroll);
        }
    }

    private void focusRowTile(boolean on) {
        rowTiles.get(rowIndex).setFocusedTile(on, true);
    }

    private void focusRow(int i) {
        focusRowTile(false);
        rowIndex = i;
        focusRowTile(true);
        keepRowIndexVisible(true);
        requestArt(rowIndex);
    }

    /** Scrolls the row so the focused tile sits in the first slot, under the title block. */
    private void keepRowIndexVisible(boolean animate) {
        // Carousel (user, 30 Oct): the focused tile is always the leftmost one in view.
        int s = Math.max(0, Math.min(rowIndex, rowTiles.size() - 1));
        float x = -s * (Spec.TILE_W + Spec.TILE_GAP) * density;
        rowScroll = s;
        if (!animate) {
            strip.animate().cancel();
            strip.setTranslationX(x);
            glass.invalidateAll();
        } else if (strip.getTranslationX() != x) {
            strip.animate().translationX(x).setDuration(Spec.TRANSFORM_MS).setInterpolator(Spec.FOCUS)
                    .setUpdateListener(a -> glass.invalidateAll()).start();
        }
    }

    private void moveCapsule(int kc) {
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
            focusCapsule(Math.max(CAP_INPUTS, capIndex - 1), true);
        } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            focusCapsule(Math.min(caps.length - 1, capIndex + 1), true);
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (anyStopShown() || hasResume() || !rowTiles.isEmpty() || !gridTiles.isEmpty()) {
                capsuleOff();
                downFrom(Zone.CAPSULE);
            }
        }
    }

    /*
     * One vertical chain, the same both ways:
     *   capsule <-> now playing <-> speaker <-> calendar switch <-> Resume <-> tile
     * Cards that are hidden are skipped. Only the focused stop looks focused:
     * no tile is lit under Resume or a card (user, 29 Oct).
     */
    private Zone[] stops = {Zone.NOW, Zone.SPEAKER, Zone.CALENDAR};   // rebuilt from the widget order

    private boolean stopShown(Zone z) {
        switch (z) {
            case NOW: return nowCard.isShown();
            case SPEAKER: return speakerCard.isShown();
            default: return calendarCard.isShown();
        }
    }

    private boolean anyStopShown() {
        for (Zone z : stops) if (stopShown(z)) return true;
        return false;
    }

    private void stopOn(Zone z, boolean on) {
        switch (z) {
            case NOW: nowCard.setFocus(on); break;
            case SPEAKER: speakerCard.setFocus(on); break;
            default: calendarCard.toggle.setOn(on, true); break;
        }
    }

    private int stopIndex(Zone z) {
        for (int i = 0; i < stops.length; i++) if (stops[i] == z) return i;
        return z == Zone.CAPSULE ? -1 : stops.length;   // anything else counts as the left side
    }

    /** Down from the capsule or a card: the next card shown, else Resume / the tile. */
    private void downFrom(Zone from) {
        for (int i = stopIndex(from) + 1; i < stops.length; i++) {
            if (stopShown(stops[i])) {
                zone = stops[i];
                stopOn(zone, true);
                return;
            }
        }
        enterLeft();
    }

    /** Up from a card or the left side (tile already unfocused): the previous card shown, else the capsule. */
    private void upFrom(Zone from) {
        for (int i = stopIndex(from) - 1; i >= 0; i--) {
            if (stopShown(stops[i])) {
                zone = stops[i];
                stopOn(zone, true);
                return;
            }
        }
        focusCapsule(CAP_INPUTS, true);
    }

    /**
     * Up / Down along the chain. Left goes back across to Resume / the tile; on
     * the calendar switch Left first picks Next and Right picks Week.
     */
    private void moveStop(int kc) {
        if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            Zone from = zone;
            stopOn(from, false);
            if (kc == KeyEvent.KEYCODE_DPAD_UP) upFrom(from);
            else downFrom(from);
        } else if (zone == Zone.CALENDAR && (kc == KeyEvent.KEYCODE_DPAD_RIGHT || calendarCard.isWeek())) {
            calendarCard.setView(kc == KeyEvent.KEYCODE_DPAD_RIGHT
                    ? GlassCards.CalendarCard.WEEK : GlassCards.CalendarCard.NEXT);
        } else if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
            stopOn(zone, false);
            enterLeft();
        }
    }

    /** Down from the right column: Resume, else the tile, else the grid. */
    private void enterLeft() {
        if (hasResume()) {
            focusResume();
        } else if (!rowTiles.isEmpty()) {
            zone = Zone.ROW;
            focusRowTile(true);
        } else if (!gridTiles.isEmpty()) {
            openGrid(0);
        }
    }

    /** Up from Resume or a tile without one (tile already unfocused). */
    private void upFromLeft() {
        upFrom(Zone.ROW);
    }

    private void focusCapsule(int i, boolean animate) {
        zone = Zone.CAPSULE;
        capIndex = i;
        for (int c = 0; c < caps.length; c++) caps[c].setOn(c == i, animate);
        showCapTip(i);
    }

    private void capsuleOff() {
        for (GlassPanel.CapButton c : caps) c.setOn(false, true);
        hideCapTip();
    }

    private GlassPanel capTip;
    private TextView capTipText;
    private static final int[] CAP_TIPS = {R.string.tip_yes, R.string.tip_widgets, R.string.settings};

    /** Fades the tooltip in under button i (after the tile labels' delay), or slides it there. */
    private void showCapTip(int i) {
        capTipText.setText(CAP_TIPS[i]);
        capTip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        View b = caps[i];
        float x = capsule.getLeft() + b.getLeft() + b.getWidth() / 2f - capTip.getMeasuredWidth() / 2f;
        x = Math.min(x, screenW - px(Spec.CAPSULE_RIGHT) - capTip.getMeasuredWidth());   // never off the right edge
        boolean shown = capTip.getVisibility() == View.VISIBLE && capTip.getAlpha() > 0f;
        capTip.setVisibility(View.VISIBLE);
        if (shown) {
            capTip.animate().translationX(x).alpha(1f).setStartDelay(0).setDuration(Spec.CAP_MS)
                    .setInterpolator(Spec.FOCUS).setUpdateListener(a -> capTip.invalidate()).start();
        } else {
            capTip.animate().cancel();
            capTip.setTranslationX(x);
            capTip.animate().alpha(1f).setStartDelay(Spec.LABEL_DELAY_MS).setDuration(Spec.LABEL_MS)
                    .setInterpolator(Spec.EASE).setUpdateListener(null).start();
        }
    }

    private void hideCapTip() {
        if (capTip.getVisibility() != View.VISIBLE) return;
        capTip.animate().alpha(0f).setStartDelay(0).setDuration(Spec.LABEL_MS).setInterpolator(Spec.EASE)
                .setUpdateListener(null).withEndAction(() -> {
                    if (zone != Zone.CAPSULE) capTip.setVisibility(View.INVISIBLE);
                }).start();
    }

    private void moveGrid(int kc) {
        int n = gridEntries.size();
        int col = gridIndex % Spec.ROW_VISIBLE;
        int next = gridIndex;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (col > 0) next = gridIndex - 1;
        } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (col < Spec.ROW_VISIBLE - 1 && gridIndex + 1 < n) next = gridIndex + 1;
        } else if (kc == KeyEvent.KEYCODE_DPAD_UP) {
            if (gridIndex < Spec.ROW_VISIBLE) {
                closeGrid();
                return;
            }
            next = gridIndex - Spec.ROW_VISIBLE;
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            int lastRow = (n - 1) / Spec.ROW_VISIBLE;
            if (gridIndex / Spec.ROW_VISIBLE < lastRow) next = Math.min(gridIndex + Spec.ROW_VISIBLE, n - 1);
        }
        if (next != gridIndex) {
            gridTiles.get(gridIndex).setFocusedTile(false, true);
            gridIndex = next;
            gridTiles.get(gridIndex).setFocusedTile(true, true);
            scrollPage();
        }
    }

    private void openGrid(int column) {
        if (gridTiles.isEmpty()) return;
        if (!rowTiles.isEmpty()) focusRowTile(false);
        zone = Zone.GRID;
        gridOpen = true;
        gridIndex = clamp(column, gridTiles.size());
        gridTiles.get(gridIndex).setFocusedTile(true, true);
        gridLayer.setVisibility(View.VISIBLE);
        gridLayer.animate().alpha(1f).setDuration(Spec.GRID_FADE_MS).setInterpolator(Spec.EASE).start();
        titleLayer.animate().alpha(0f).setDuration(Spec.LABEL_MS).setInterpolator(Spec.EASE).start();
        scrollPage();
    }

    private void closeGrid() {
        if (!gridOpen) return;
        gridTiles.get(gridIndex).setFocusedTile(false, true);
        gridOpen = false;
        gridLayer.animate().alpha(0f).setDuration(Spec.GRID_FADE_MS).setInterpolator(Spec.EASE)
                .withEndAction(() -> {
                    if (!gridOpen) gridLayer.setVisibility(View.INVISIBLE);
                }).start();
        titleLayer.animate().alpha(1f).setDuration(Spec.LABEL_MS).setInterpolator(Spec.EASE).start();
        if (!rowTiles.isEmpty()) {
            zone = Zone.ROW;
            focusRowTile(true);
        } else {
            focusCapsule(0, true);
        }
        scrollPage();
    }

    private boolean topBandOn;

    /** The frosted band fades in once the page offset y puts tiles under the clock, weather and capsule. */
    private void showTopBand(float y) {
        boolean under = rowTop() + y < (Spec.TOP_BAND_H - Spec.TOP_BAND_FADE) * density;
        if (under == topBandOn) return;
        topBandOn = under;
        if (under) {
            topBand.setVisibility(View.VISIBLE);
            topBand.animate().alpha(1f).setDuration(Spec.GRID_FADE_MS).setInterpolator(Spec.EASE)
                    .withEndAction(null).start();
        } else {
            topBand.animate().alpha(0f).setDuration(Spec.GRID_FADE_MS).setInterpolator(Spec.EASE)
                    .withEndAction(() -> {
                        if (!topBandOn) topBand.setVisibility(View.INVISIBLE);
                    }).start();
        }
    }

    /** Page offset: 0 on the row; in the grid the focused grid row's top sits at GRID_FOCUS_TOP. */
    private void scrollPage() {
        float y = gridOpen ? Spec.GRID_FOCUS_TOP * density - gridRowTop(gridIndex / Spec.ROW_VISIBLE) : 0f;
        showTopBand(y);
        if (page.getTranslationY() == y) return;
        page.animate().translationY(y).setDuration(Spec.TRANSFORM_MS).setInterpolator(Spec.FOCUS)
                .setUpdateListener(a -> glass.invalidateAll()).start();
    }

    // ------------------------------------------------------------- reorder

    private boolean moving;
    private final List<ValueAnimator> wiggles = new ArrayList<>();

    /** Hold Select on a row app: edit mode. Every app wiggles (iPhone style) and shows a remove badge. */
    private final Runnable holdToMove = () -> {
        if (!selectArmed || zone != Zone.ROW || rowIndex >= rowApps.size()) return;
        selectArmed = false;   // this press starts editing; its release must not open the app
        moving = true;
        for (int i = 0; i < rowApps.size(); i++) {
            final TileView t = rowTiles.get(i);
            t.setEditBadge(true);
            ValueAnimator w = ValueAnimator.ofFloat(-Spec.WIGGLE_DEG, Spec.WIGGLE_DEG);
            w.setDuration(Spec.WIGGLE_MS);
            w.setRepeatMode(ValueAnimator.REVERSE);
            w.setRepeatCount(ValueAnimator.INFINITE);
            w.setCurrentPlayTime((i * 47L) % Spec.WIGGLE_MS);   // out of phase, like the home screen
            w.addUpdateListener(a -> {
                t.setRotation((float) a.getAnimatedValue());
                t.frame.invalidate();
            });
            w.start();
            wiggles.add(w);
        }
    };

    /** Left/Right move the held app (never past Add apps); Up/Down finish. */
    private void moveTile(int kc) {
        int to = rowIndex;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) to = rowIndex - 1;
        else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) to = rowIndex + 1;
        else {
            endMove();
            return;
        }
        if (to < 0 || to >= rowApps.size()) return;
        int from = rowIndex;
        Collections.swap(rowApps, from, to);
        Collections.swap(rowTiles, from, to);
        placeTile(rowTiles.get(from), to, from);
        placeTile(rowTiles.get(to), from, to);
        rowIndex = to;
        keepRowIndexVisible(true);
    }

    /** Re-lays a tile at slot newSlot and slides it there from oldSlot. */
    private void placeTile(TileView t, int oldSlot, int newSlot) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
        lp.leftMargin = Math.round(tileX(newSlot));
        t.setLayoutParams(lp);
        t.setTranslationX(t.getTranslationX() + tileX(oldSlot) - tileX(newSlot));
        t.animate().translationX(0f).setDuration(Spec.TRANSFORM_MS).setInterpolator(Spec.FOCUS).start();
    }

    /** Select while editing: Done, Remove from row, and Uninstall for apps that can be uninstalled. */
    private void openEditMenu() {
        final AppInfo app = rowApps.get(rowIndex);
        beginMenu();
        addMenuRow(getString(R.string.edit_done), this::endMove);
        addMenuRow(getString(R.string.edit_remove), () -> removeFromRow(app.packageName));
        if (canUninstall(app.packageName)) {
            addMenuRow(getString(R.string.edit_uninstall), () -> {
                endMove();
                // Android shows its own confirmation before anything is removed.
                startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }
        showMenu(rowTiles.get(rowIndex), Zone.ROW);
    }

    private boolean canUninstall(String pkg) {
        try {
            int f = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (f & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void endMove() {
        if (!moving) return;
        moving = false;
        for (ValueAnimator w : wiggles) w.cancel();
        wiggles.clear();
        for (TileView t : rowTiles) {
            t.setRotation(0f);
            t.setEditBadge(false);
        }
        saveRowOrder(rowPackages());
    }

    private List<String> rowPackages() {
        List<String> out = new ArrayList<>();
        for (AppInfo a : rowApps) out.add(a.packageName);
        return out;
    }

    private void saveRowOrder(List<String> pkgs) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_ROW_ORDER, TextUtils.join(",", pkgs)).apply();
    }

    /** Once saved (reorder, add or remove), the row is exactly the saved list; before that, config.xml. */
    private String[] rowOrder() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ROW_ORDER, null);
        if (saved == null) return getResources().getStringArray(R.array.row_apps);
        return saved.isEmpty() ? new String[0] : saved.split(",");
    }

    private void removeFromRow(String pkg) {
        endMove();
        List<String> pkgs = rowPackages();
        pkgs.remove(pkg);
        saveRowOrder(pkgs);
        reloadApps();
    }

    // ------------------------------------------------------------- add apps

    private List<AppInfo> installedApps = new ArrayList<>();
    private String focusAfterLoad;

    /** The "+" tile: installed apps that are not in the row yet; Select adds one before the "+". */
    private void openAddApps() {
        beginMenu();
        List<String> inRow = rowPackages();
        for (AppInfo a : installedApps) {
            if (inRow.contains(a.packageName) || TV_SETTINGS_PACKAGE.equals(a.packageName)) continue;
            final String pkg = a.packageName;
            addMenuRow(a.label, a.icon, () -> {
                List<String> pkgs = rowPackages();
                pkgs.add(pkg);
                saveRowOrder(pkgs);
                focusAfterLoad = pkg;
                reloadApps();
            });
        }
        if (menuRows.isEmpty()) addMenuRow(getString(R.string.all_apps_in_row), null);
        showMenu(rowTiles.get(rowIndex), Zone.ROW);
    }

    /**
     * Up: the lowest card on the right, else the capsule. Down: back to the tile.
     * Right: across to the nearest card on the right (user, 29 Oct; no longer
     * the next app). Left: nothing.
     */
    private void moveResume(int kc) {
        if (kc == KeyEvent.KEYCODE_DPAD_UP) {
            leaveResume();
            focusRowTile(false);
            upFromLeft();
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            leaveResume();
            zone = Zone.ROW;
            focusRowTile(true);
        } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT && anyStopShown()) {
            leaveResume();
            focusRowTile(false);
            upFromLeft();   // the lowest card shown is the nearest one
        }
    }

    private void onBackKey() {
        if (moving && zone == Zone.ROW) {
            endMove();
            return;
        }
        if (zone == Zone.WIDGETS) {
            exitWidgetEdit();
            return;
        }
        switch (zone) {
            case MENU: closeMenu(true); break;
            case GRID: closeGrid(); break;
            case CAPSULE: moveCapsule(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case RESUME: moveResume(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case NOW:
            case SPEAKER:
            case CALENDAR:
                stopOn(zone, false);
                enterLeft();
                break;
            case ROW: break;   // Home has nowhere to go back to.
        }
    }

    private void activate() {
        switch (zone) {
            case ROW:
                if (rowTiles.isEmpty()) break;   // apps still loading
                if (rowIndex < rowApps.size()) launch(rowApps.get(rowIndex));
                else openAddApps();
                break;
            case CAPSULE:
                if (capIndex == CAP_INPUTS) openInputs(capsule, Zone.CAPSULE);
                else if (capIndex == CAP_SETTINGS) openSettings();
                else enterWidgetEdit();
                break;
            case GRID: {
                Entry e = gridEntries.get(gridIndex);
                if (e.kind == Entry.APP) launch(e.app);
                else if (e.kind == Entry.INPUTS) openInputs(gridTiles.get(gridIndex), Zone.GRID);
                else openSettings();
                break;
            }
            case RESUME:
                resume();
                break;
            case NOW:
                toggleNow();
                break;
            case SPEAKER:
                if (castPlaying != null && cast != null) cast.toggle(castPlaying);
                break;
            case CALENDAR:
                calendarCard.flipView();
                break;
            case WIDGETS:
                openWidgetMenu();
                break;
            case MENU:
                if (menuIndex < menuActions.size()) {
                    Runnable r = menuActions.get(menuIndex);
                    if (r == null) break;   // "No inputs found" row
                    closeMenu(false);
                    r.run();
                }
                break;
        }
    }

    // ------------------------------------------------------------- Inputs menu

    /** One press: Live TV. The dropdown only when the TV also has HDMI/AV inputs to choose from. */
    private void openInputs(View anchor, Zone from) {
        Intent live = liveTvIntent();
        if (live != null && tvInputs().isEmpty()) startSafely(live);
        else openMenu(anchor, from);
    }

    private ScrollView menuScroll;
    private LinearLayout menuList;
    private static final int MENU_MAX_ROWS = 7;

    private void beginMenu() {
        closeMenu(false);
        menuRows.clear();
        menuActions.clear();
        menu = new GlassPanel(this, glass, LinearLayout.VERTICAL, Spec.TILE_RADIUS);
        menuScroll = new ScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        menuScroll.addView(menuList);
    }

    private void openMenu(View anchor, Zone from) {
        beginMenu();
        for (TvInputInfo info : tvInputs()) {
            final String id = info.getId();
            CharSequence label = info.loadCustomLabel(this);
            if (TextUtils.isEmpty(label)) label = info.loadLabel(this);
            addMenuRow(label, () -> startSafely(new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        final Intent live = liveTvIntent();
        if (live != null) addMenuRow(getString(R.string.live_tv), () -> startSafely(live));
        if (menuRows.isEmpty()) addMenuRow(getString(R.string.no_inputs), null);
        showMenu(anchor, from);
    }

    /** Places the menu next to its anchor (below if it fits, else above), at most 7 rows tall, scrolling. */
    private void showMenu(View anchor, Zone from) {
        hideCapTip();
        int rows = Math.min(menuRows.size(), MENU_MAX_ROWS);
        menu.addView(menuScroll, new LinearLayout.LayoutParams(px(200), rows * px(36) + (rows - 1) * px(2)));
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = menu.getMeasuredWidth(), mh = menu.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int gap = px(8);
        int left = loc[0] + anchor.getWidth() - mw;
        left = Math.max(px(Spec.CAPSULE_RIGHT), Math.min(left, screenW - px(Spec.CAPSULE_RIGHT) - mw));
        int top = loc[1] + anchor.getHeight() + gap;
        boolean below = top + mh <= screenH - px(Spec.CAPSULE_TOP);
        if (!below) top = loc[1] - gap - mh;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = left;
        lp.topMargin = top;
        root.addView(menu, lp);

        // Grow out of the trigger: pivot at the menu corner nearest the anchor.
        menu.setPivotX(mw);
        menu.setPivotY(below ? 0 : mh);
        menu.setAlpha(0f);
        menu.setScaleX(0.96f);
        menu.setScaleY(0.96f);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Spec.CAP_MS).setInterpolator(Spec.FOCUS)
                .setUpdateListener(a -> glass.invalidateAll()).start();

        menuReturn = from;
        zone = Zone.MENU;
        menuIndex = 0;
        menuRows.get(0).setOn(true, false);
    }

    private void addMenuRow(CharSequence label, Runnable action) {
        addMenuRow(label, null, action);
    }

    private void addMenuRow(CharSequence label, Drawable icon, Runnable action) {
        GlassPanel.MenuRow row = new GlassPanel.MenuRow(this, label, icon, medium);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(200), px(36));
        if (!menuRows.isEmpty()) lp.topMargin = px(2);
        menuList.addView(row, lp);
        menuRows.add(row);
        menuActions.add(action);
    }

    private void moveMenu(int kc) {
        int next = menuIndex;
        if (kc == KeyEvent.KEYCODE_DPAD_UP) next = Math.max(0, menuIndex - 1);
        else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) next = Math.min(menuRows.size() - 1, menuIndex + 1);
        if (next == menuIndex) return;
        menuRows.get(menuIndex).setOn(false, true);
        menuIndex = next;
        menuRows.get(menuIndex).setOn(true, true);
        // Keep the focused row inside the visible window.
        View r = menuRows.get(menuIndex);
        int y = menuScroll.getScrollY(), h = menuScroll.getHeight();
        if (r.getTop() < y) menuScroll.smoothScrollTo(0, r.getTop());
        else if (r.getBottom() > y + h) menuScroll.smoothScrollTo(0, r.getBottom() - h);
    }

    /** Focus returns to where the menu was opened; with animate, it shrinks back into its trigger. */
    private void closeMenu(boolean animate) {
        if (menu == null) return;
        final GlassPanel m = menu;
        menu = null;
        glass.unregister(m);
        if (animate) {
            m.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(Spec.LABEL_MS)
                    .setInterpolator(Spec.EASE).setUpdateListener(null)
                    .withEndAction(() -> root.removeView(m)).start();
        } else {
            m.animate().cancel();
            root.removeView(m);
        }
        if (zone == Zone.MENU) zone = menuReturn;
    }

    private List<TvInputInfo> tvInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_TUNER) continue;   // covered by Live TV
                if (info.isHidden(this)) continue;
                if (info.getParentId() != null) continue;               // e.g. CEC devices under an HDMI port
                out.add(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "TV inputs unavailable: " + t.getMessage());
        }
        return out;
    }

    /** Live TV on this box is the yes app; elsewhere, whatever handles the channel list. */
    private Intent liveTvIntent() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(YES_PACKAGE);
        if (i == null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            if (channels.resolveActivity(pm) != null) i = channels;
        }
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // ------------------------------------------------------------- launching

    private void launch(AppInfo app) {
        if (app == null || app.launchIntent == null) return;
        launchedPkg = app.packageName;
        startSafely(app.launchIntent);
    }

    private void openSettings() {
        startSafely(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void startSafely(Intent i) {
        HomeKeyService.followUpUntil = 0;   // the user's own choice: never bounced back
        try {
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "launch failed: " + i + " " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- Top Shelf

    private boolean artReady;
    /** Until a show's art is ready, the app's own name over the wallpaper, never the previous app's title. */
    private final Runnable artNotReady = () -> {
        if (artPkg != null && !artReady) showFallback(artPkg);
    };

    private void requestArt(int index) {
        requestArt(index, false);
    }

    /** The focused app's card again (back on Home, or a new snapshot): no wallpaper in between, same show. */
    private void refreshArt() {
        if (rowIndex < rowApps.size()) requestArt(rowIndex, true);
    }

    private void requestArt(int index, boolean refresh) {
        if (index < 0 || index >= rowApps.size()) return;
        final String pkg = rowApps.get(index).packageName;
        boolean sameApp = pkg.equals(artPkg);
        if (sameApp && !refresh) return;
        artPkg = pkg;
        if (!sameApp) {
            artReady = false;
            fallbackCont = null;
            main.removeCallbacks(artNotReady);
            main.postDelayed(artNotReady, Spec.ART_WAIT_MS);
        }
        TopShelfManager.Callback cb = new TopShelfManager.Callback() {
            @Override public void onArt(String p, TopShelfManager.ArtPayload payload) {
                main.post(() -> {
                    if (!claimArt(p)) return;
                    if (artIsFallback || !payload.art.key.equals(art.key())) art.show(payload.art);
                    artIsFallback = false;
                    CharSequence name = labelOf(p);
                    ContinueWatching.Info cont = shownCont(p, payload.cont);
                    swapTitle(payload.clearLogo, payload.clearLogo == null ? payload.title : null, name,
                            cont, "show:" + payload.title + "|" + name + contSig(cont));
                });
            }

            @Override public void onNoArt(String p, ContinueWatching.Info cont) {
                main.post(() -> {
                    if (!claimArt(p)) return;
                    fallbackCont = cont;
                    showFallback(p);
                });
            }
        };
        if (refresh) topShelf.refreshFor(pkg, cb);
        else topShelf.requestFor(pkg, cb);
    }

    /** A music app in the now playing card: that card is its Resume, so no second card for it. */
    private ContinueWatching.Info shownCont(String pkg, ContinueWatching.Info cont) {
        if (hiddenWidgets.contains(W_CONTINUE)) return null;
        return cont != null && pkg.equals(nowPkg) ? null : cont;
    }

    // ------------------------------------------------------------- edit widgets

    /*
     * Edit widgets (user, 29 Oct), like editing the apps: from the capsule's
     * widgets button or by holding Select on a widget. Every widget wiggles
     * with a "−" badge; the focused one has a white ring. Select opens a menu
     * (move up / down for the right column, hide, done); "+ Add widget" shows
     * hidden ones again. Order and hidden widgets are saved.
     */
    private static final String W_CONTINUE = "continue", W_NOW = "now", W_CALENDAR = "calendar", W_ADD = "+";
    private static final String PREF_WIDGETS_HIDDEN = "widgets_hidden", PREF_WIDGETS_ORDER = "widgets_order";
    private final java.util.Set<String> hiddenWidgets = new java.util.HashSet<>();
    private final List<String> columnOrder = new ArrayList<>();
    private LinearLayout column;
    private ContinueViews.Resume addWidget;
    private boolean editingWidgets;
    private final List<String> editStops = new ArrayList<>();
    private int editIndex;
    private final List<ValueAnimator> widgetWiggles = new ArrayList<>();
    private final List<GlassPanel> wiggling = new ArrayList<>();

    private final Runnable holdToEditWidgets = () -> {
        if (!selectArmed) return;
        selectArmed = false;   // this press starts editing; its release must not act
        enterWidgetEdit();
    };

    private void loadWidgetPrefs() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        hiddenWidgets.clear();
        hiddenWidgets.addAll(p.getStringSet(PREF_WIDGETS_HIDDEN, java.util.Collections.emptySet()));
        columnOrder.clear();
        for (String id : p.getString(PREF_WIDGETS_ORDER, W_NOW + "," + W_CALENDAR).split(",")) {
            if ((id.equals(W_NOW) || id.equals(W_CALENDAR)) && !columnOrder.contains(id)) columnOrder.add(id);
        }
        if (!columnOrder.contains(W_NOW)) columnOrder.add(W_NOW);
        if (!columnOrder.contains(W_CALENDAR)) columnOrder.add(W_CALENDAR);
    }

    private void saveWidgetPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(PREF_WIDGETS_HIDDEN, new java.util.HashSet<>(hiddenWidgets))
                .putString(PREF_WIDGETS_ORDER, TextUtils.join(",", columnOrder)).apply();
    }

    /** The column (and the focus chain) in the saved order; the speaker card follows now playing. */
    private void applyColumnOrder() {
        column.removeAllViews();
        List<Zone> order = new ArrayList<>();
        for (String id : columnOrder) {
            if (id.equals(W_NOW)) {
                column.addView(nowCard.view);
                column.addView(speakerCard.view);
                order.add(Zone.NOW);
                order.add(Zone.SPEAKER);
            } else {
                column.addView(calendarCard.view);
                order.add(Zone.CALENDAR);
            }
        }
        column.addView(addWidget);
        stops = order.toArray(new Zone[0]);
    }

    private GlassPanel widgetView(String id) {
        switch (id) {
            case W_CONTINUE: return currentTitle != null ? currentTitle.view : null;
            case W_NOW: return nowCard.view;
            default: return calendarCard.view;
        }
    }

    private boolean widgetOnScreen(String id) {
        switch (id) {
            case W_CONTINUE: return !hiddenWidgets.contains(W_CONTINUE) && currentTitle != null && currentTitle.cont != null;
            case W_NOW: return nowCard.isShown();
            default: return calendarCard.isShown();
        }
    }

    private String widgetName(String id) {
        switch (id) {
            case W_CONTINUE: return getString(R.string.widget_continue);
            case W_NOW: return getString(R.string.widget_now_playing);
            default: return getString(R.string.widget_calendar);
        }
    }

    private void enterWidgetEdit() {
        if (editingWidgets) return;
        closeMenu(false);
        endMove();
        if (gridOpen) closeGrid();
        capsuleOff();
        for (Zone z : stops) stopOn(z, false);
        leaveResume();
        if (!rowTiles.isEmpty()) focusRowTile(false);
        editingWidgets = true;
        zone = Zone.WIDGETS;
        editIndex = 0;
        refreshWidgetEdit();
    }

    /** Rebuilds the focus list, badges, ring and wiggle after any change. */
    private void refreshWidgetEdit() {
        String focused = editIndex < editStops.size() ? editStops.get(editIndex) : null;
        editStops.clear();
        if (widgetOnScreen(W_CONTINUE)) editStops.add(W_CONTINUE);
        for (String id : columnOrder) if (widgetOnScreen(id)) editStops.add(id);
        boolean anyHidden = !hiddenWidgets.isEmpty();
        addWidget.setVisibility(anyHidden ? View.VISIBLE : View.GONE);
        if (anyHidden) editStops.add(W_ADD);
        int i = focused == null ? -1 : editStops.indexOf(focused);
        editIndex = editStops.isEmpty() ? 0 : i >= 0 ? i : Math.min(editIndex, editStops.size() - 1);

        stopWiggles();
        for (TitleBlock b : titles) b.view.setEdit(false, false);
        nowCard.view.setEdit(false, false);
        calendarCard.view.setEdit(false, false);
        for (int k = 0; k < editStops.size(); k++) {
            String id = editStops.get(k);
            if (id.equals(W_ADD)) continue;
            GlassPanel v = widgetView(id);
            if (v == null) continue;
            v.setEdit(true, k == editIndex);
            wiggle(v, k);
        }
        addWidget.setOn(editIndex < editStops.size() && editStops.get(editIndex).equals(W_ADD), true);
    }

    private void wiggle(GlassPanel v, int phase) {
        ValueAnimator w = ValueAnimator.ofFloat(-WIDGET_WIGGLE_DEG, WIDGET_WIGGLE_DEG);
        w.setDuration(Spec.WIGGLE_MS * 2);
        w.setRepeatMode(ValueAnimator.REVERSE);
        w.setRepeatCount(ValueAnimator.INFINITE);
        w.setCurrentPlayTime((phase * 61L) % (Spec.WIGGLE_MS * 2));
        w.addUpdateListener(a -> {
            v.setRotation((float) a.getAnimatedValue());
            v.invalidate();
        });
        w.start();
        widgetWiggles.add(w);
        wiggling.add(v);
    }

    /** Big cards turn less than the small tiles do for the same wiggle. */
    private static final float WIDGET_WIGGLE_DEG = 0.5f;

    private void stopWiggles() {
        for (ValueAnimator w : widgetWiggles) w.cancel();
        widgetWiggles.clear();
        for (GlassPanel v : wiggling) v.setRotation(0f);
        wiggling.clear();
    }

    private void moveWidgetFocus(int kc) {
        if (editStops.isEmpty()) return;
        int step = kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
        int next = Math.max(0, Math.min(editStops.size() - 1, editIndex + step));
        if (next == editIndex) return;
        editIndex = next;
        refreshWidgetEdit();
    }

    /** Select on a widget: move / hide / done; on "+ Add widget": the hidden widgets. */
    private void openWidgetMenu() {
        if (editIndex >= editStops.size()) return;
        final String id = editStops.get(editIndex);
        beginMenu();
        if (id.equals(W_ADD)) {
            for (String hidden : new String[]{W_CONTINUE, W_NOW, W_CALENDAR}) {
                if (hiddenWidgets.contains(hidden)) addMenuRow(widgetName(hidden), () -> setWidgetHidden(hidden, false));
            }
            showMenu(addWidget, Zone.WIDGETS);
            return;
        }
        int pos = columnOrder.indexOf(id);
        if (pos > 0) addMenuRow(getString(R.string.widget_move_up), () -> moveWidget(id, -1));
        if (pos >= 0 && pos < columnOrder.size() - 1) addMenuRow(getString(R.string.widget_move_down), () -> moveWidget(id, 1));
        addMenuRow(getString(R.string.widget_hide), () -> setWidgetHidden(id, true));
        addMenuRow(getString(R.string.edit_done), this::exitWidgetEdit);
        View anchor = widgetView(id);
        showMenu(anchor != null ? anchor : capsule, Zone.WIDGETS);
    }

    private void moveWidget(String id, int by) {
        int from = columnOrder.indexOf(id), to = from + by;
        if (from < 0 || to < 0 || to >= columnOrder.size()) return;
        Collections.swap(columnOrder, from, to);
        saveWidgetPrefs();
        applyColumnOrder();
        refreshWidgetEdit();
    }

    private void setWidgetHidden(String id, boolean hide) {
        if (hide) hiddenWidgets.add(id);
        else hiddenWidgets.remove(id);
        saveWidgetPrefs();
        switch (id) {
            case W_CONTINUE:
                refreshArt();   // the focused app's card comes back or goes plain
                break;
            case W_NOW:
                onLiveMediaChanged();
                break;
            default:
                if (hide) {
                    calendarCard.stop();
                    calendarCard.view.setVisibility(View.GONE);
                } else if (calendarEnabled) {
                    calendarCard.start();
                }
                break;
        }
        fitCalendar();
        // Shown widgets arrive a moment later (art, media, calendar): refresh the edit look then too.
        refreshWidgetEdit();
        main.postDelayed(() -> {
            if (editingWidgets) refreshWidgetEdit();
        }, 800);
    }

    private void exitWidgetEdit() {
        if (!editingWidgets) return;
        editingWidgets = false;
        stopWiggles();
        for (TitleBlock b : titles) b.view.setEdit(false, false);
        nowCard.view.setEdit(false, false);
        calendarCard.view.setEdit(false, false);
        addWidget.setOn(false, false);
        addWidget.setVisibility(View.GONE);
        if (zone == Zone.WIDGETS || zone == Zone.MENU) {
            closeMenu(false);
            if (!rowTiles.isEmpty()) {
                zone = Zone.ROW;
                focusRowTile(true);
            } else {
                focusCapsule(CAP_INPUTS, true);
            }
        }
    }

    // ------------------------------------------------------------- media cards

    /** Music started, paused or stopped on this box. */
    private void onLiveMediaChanged() {
        MediaController m = nowEnabled && !hiddenWidgets.contains(W_NOW) ? liveMedia.music() : null;
        if (m == null) nowCard.show(null, null, null, false, null);
        else nowCard.show(getString(R.string.now_playing), LiveMedia.title(m), LiveMedia.by(m),
                LiveMedia.isPlaying(m), badgeFor(m.getPackageName()));
        String pkg = m == null ? null : m.getPackageName();
        if (!Objects.equals(pkg, nowPkg)) {
            String old = nowPkg;
            nowPkg = pkg;
            // The focused app gains or loses its own continue card.
            if (artPkg != null && (artPkg.equals(old) || artPkg.equals(pkg))) refreshArt();
        }
        if (zone == Zone.NOW && !nowCard.isShown()) downFrom(Zone.NOW);
        fitCalendar();
    }

    private static final long HOLD_FILL_DELAY_MS = 150;   // a normal press never shows the ring
    private static final int HOLD_FILL_MS = 900;
    private ValueAnimator holdFill;

    private final Runnable startHoldFill = () -> {
        if (!selectArmed || zone != Zone.NOW) return;
        holdFill = Spec.tween(holdFill, 0f, 1f, HOLD_FILL_MS, new android.view.animation.LinearInterpolator(),
                f -> nowCard.button.setHoldProgress(f));
        holdFill.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean canceled;

            @Override public void onAnimationCancel(android.animation.Animator a) {
                canceled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (canceled) return;
                selectArmed = false;   // the release must not also play / pause
                nowCard.button.setHoldProgress(0f);
                MediaController m = liveMedia.music();
                if (m != null) openPlayer(m.getPackageName(), m);
            }
        });
    };

    /** Released or moved before the ring is full: it drains away, nothing opens. */
    private void cancelHoldFill() {
        main.removeCallbacks(startHoldFill);
        if (holdFill == null || !holdFill.isRunning()) return;
        holdFill.cancel();
        float from = nowCard.button.holdProgress();
        holdFill = Spec.tween(null, from, 0f, Spec.LABEL_MS, Spec.EASE, f -> nowCard.button.setHoldProgress(f));
    }

    /** The app's own player (live session, else remembered), else the app itself. */
    private void openPlayer(String pkg, MediaController m) {
        if (sendPlayer(pkg, m)) return;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i != null) startSafely(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The app's own player (its live session's, else remembered from when it last played); false if none. */
    private boolean sendPlayer(String pkg, MediaController m) {
        PendingIntent player = m != null ? m.getSessionActivity() : null;
        if (player == null) player = MediaListener.lastPlayer(pkg);
        if (player == null) return false;
        HomeKeyService.followUpUntil = 0;
        try {
            player.send();
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "player link gone: " + pkg);
            return false;
        }
    }

    private static final long PLAY_CHECK_MS = 1500;
    private Runnable playCheck;

    /**
     * Pause, or play from Home. A player that has let go of its media in the
     * background ignores Play (Spotify, after a while paused): if nothing is
     * playing PLAY_CHECK_MS later, its own player opens, told to play.
     */
    private void toggleNow() {
        final MediaController m = liveMedia.music();
        if (m == null) return;
        if (LiveMedia.isPlaying(m)) {
            m.getTransportControls().pause();
            return;
        }
        m.getTransportControls().play();
        if (playCheck != null) main.removeCallbacks(playCheck);
        playCheck = () -> {
            if (LiveMedia.isPlaying(m) || isFinishing()) return;
            Log.d(TAG, m.getPackageName() + " ignored Play from Home; opening its player");
            openPlayer(m.getPackageName(), m);
            main.postDelayed(() -> m.getTransportControls().play(), PLAY_CHECK_MS);
        };
        main.postDelayed(playCheck, PLAY_CHECK_MS);
    }

    /** A Google Cast speaker started, paused or stopped. */
    private void onCastChanged(CastClient.Playing p) {
        castPlaying = p;
        if (p == null) {
            speakerCard.show(null, null, null, false, null);
            if (zone == Zone.SPEAKER) downFrom(Zone.SPEAKER);
        } else {
            // Uppercased here: the eyebrow's all-caps transform skips the formatted name.
            speakerCard.show(getString(p.paused ? R.string.paused_on : R.string.playing_on,
                    p.deviceName.toUpperCase(java.util.Locale.ROOT)),
                    p.title, p.by, !p.paused, null);
        }
        fitCalendar();
    }

    /** True when pkg is still the app art was wanted for; marks its art as arrived. */
    private boolean claimArt(String pkg) {
        if (!pkg.equals(artPkg)) return false;
        artReady = true;
        main.removeCallbacks(artNotReady);
        return true;
    }

    private CharSequence labelOf(String pkg) {
        int i = indexOfRow(pkg, -1);
        return i >= 0 ? rowApps.get(i).label : pkg;
    }

    /**
     * No show art for this app: the aerial wallpaper, with no title (or the
     * continue layout when there is progress data). The wallpaper is only
     * decoded when it actually changes.
     */
    private void showFallback(final String pkg) {
        artIsFallback = true;
        final ContinueWatching.Info cont = shownCont(pkg, fallbackCont);
        // With progress data but no art: the show's title in the continue layout, over the wallpaper.
        // With neither, no title at all: the focused tile's label already names the app.
        final CharSequence name = cont != null ? cont.title : null;
        final String key = "wallpaper:" + wallpaperIndex;
        final String sig = "fallback:" + name + contSig(cont);
        if (key.equals(art.key())) {
            swapTitle(null, name, null, cont, sig);
            return;
        }
        final int res = WALLPAPERS[wallpaperIndex];
        topShelf.runInBackground(() -> {
            ArtView.Art a = decodeWallpaper(key, res);
            main.post(() -> {
                if (a == null || !artIsFallback || !pkg.equals(artPkg)) return;
                if (!key.equals("wallpaper:" + wallpaperIndex)) return;
                // Wallpaper to wallpaper: a long, slow fade, so the colours flow (user, 30 Oct).
                String shown = art.key();
                art.show(a, shown != null && shown.startsWith("wallpaper:") ? WALLPAPER_FADE_MS : Spec.ART_MS);
                swapTitle(null, name, null, cont, sig);
            });
        });
    }

    /**
     * The wallpapers are smooth gradients: decoded and kept at a quarter of the
     * screen (480x270, ~0.5 MB) and scaled up by ArtView, they look the same as
     * at full size (two 8 MB bitmaps and an 8 MB texture per change, before).
     */
    private static final int WALLPAPER_SCALE = 4;

    private ArtView.Art decodeWallpaper(String key, int res) {
        // openRawResource + decodeStream: raw/ has no density, so decodeResource would upscale it.
        try (InputStream in = getResources().openRawResource(res)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = WALLPAPER_SCALE;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return null;
            ArtView.Art a = ArtComposer.compose(key, b, screenW / WALLPAPER_SCALE, screenH / WALLPAPER_SCALE);
            b.recycle();
            return a;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper decode failed: " + t.getMessage());
            return null;
        }
    }

    private ContinueWatching.Info fallbackCont;

    private static String contSig(ContinueWatching.Info c) {
        return c == null ? "" : "|cont:" + c.title + ContinueWatching.episodeLine(c) + c.position + "/" + c.duration + c.upNext;
    }

    /** Plain layout (title + app name) or the continue layout (eyebrow, episode, progress, Resume). */
    private void showContinue(TitleBlock b, boolean on) {
        int v = on ? View.VISIBLE : View.GONE;
        b.header.setVisibility(v);
        b.episode.setVisibility(on && !TextUtils.isEmpty(b.episode.getText()) ? View.VISIBLE : View.GONE);
        b.progressRow.setVisibility(on && b.cont != null && b.cont.hasBar() ? View.VISIBLE : View.GONE);
        b.resume.setVisibility(v);
        if (!on) b.resume.setOn(false, false);
        // Continue layout: a smaller glass card; plain layout: bare title over the art.
        b.view.setGlass(on);
        int pad = on ? px(Spec.CARD_PADDING) : 0;
        b.view.setPadding(pad, pad, pad, pad);
        b.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? Spec.CARD_TITLE_SP : Spec.TITLE_SP);
        b.title.setLineHeight(Math.round(b.title.getTextSize() * Spec.TITLE_LEADING));
        int maxW = px(on ? Spec.CARD_LOGO_W : Spec.TITLE_MAX_W);
        b.logo.setMaxWidth(maxW);
        b.logo.setMaxHeight(px(on ? Spec.CARD_LOGO_H : Spec.LOGO_MAX_H));
        b.title.setMaxWidth(maxW);
        b.episode.setMaxWidth(maxW);
        b.episode.setTextSize(TypedValue.COMPLEX_UNIT_SP, on ? Spec.CARD_EPISODE_SP : 13);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.view.getLayoutParams();
        if (lp != null) {
            lp.bottomMargin = px(on ? Spec.CONTINUE_BOTTOM : Spec.TITLE_BOTTOM);
            b.view.setLayoutParams(lp);
        }
    }

    /** Resume exists for the focused row app right now. */
    private boolean hasResume() {
        return currentTitle != null && currentTitle.cont != null && rowIndex < rowApps.size()
                && rowApps.get(rowIndex).packageName.equals(artPkg);
    }

    /**
     * Resume names its app with the card's badge, which turns from gray to
     * colour; the row tile is no longer lit underneath (user, 29 Oct).
     */
    private void focusResume() {
        zone = Zone.RESUME;
        currentTitle.resume.setOn(true, true);
        currentTitle.badge.setOn(true, true);
    }

    private void leaveResume() {
        if (currentTitle == null) return;
        currentTitle.resume.setOn(false, true);
        currentTitle.badge.setOn(false, true);
    }

    /**
     * Music: play right here, Home stays. Video: the Watch Next deep link; else
     * the app's own player (its session activity, live or remembered from when
     * it last played), told to play when the session is still alive; else just
     * the app.
     */
    private void resume() {
        AppInfo app = rowApps.get(rowIndex);
        ContinueWatching.Info c = currentTitle.cont;
        MediaController live = liveMedia.forPackage(app.packageName);
        if (c != null && c.music && live != null) {
            live.getTransportControls().play();
            return;
        }
        launchedPkg = app.packageName;
        if (c != null && c.intentUri != null) {
            try {
                startActivity(Intent.parseUri(c.intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                Log.w(TAG, "resume deep link failed: " + e.getMessage());
            }
        }
        boolean opened = sendPlayer(app.packageName, live);
        // Else a deep link built from what the app reported (YouTube video at the saved time, Stremio episode).
        Intent link = !opened && c != null ? ContinueWatching.deepLink(c, app.packageName) : null;
        if (link != null && link.resolveActivity(getPackageManager()) != null) {
            Log.d(TAG, "resume via deep link: " + link.getData());
            startSafely(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            opened = true;
        }
        if (!opened) launch(app);
        if (live != null) live.getTransportControls().play();
    }

    /** Crossfades the title block with the art (600ms). sig skips a swap to identical content. */
    private void swapTitle(Bitmap logo, CharSequence title, CharSequence appName,
                           ContinueWatching.Info cont, String sig) {
        if (sig.equals(currentTitleSig)) return;
        currentTitleSig = sig;
        TitleBlock prev = currentTitle;
        TitleBlock next = null;
        for (TitleBlock b : titles) {
            if (b == currentTitle) continue;
            if (next == null || b.view.getAlpha() < next.view.getAlpha()) next = b;
        }
        next.view.animate().cancel();
        next.view.setAlpha(0f);
        next.logo.setImageBitmap(logo);
        next.logo.setVisibility(logo != null ? View.VISIBLE : View.GONE);
        next.title.setText(title);
        // A show's title is semibold; the app's own name (no show) is lighter and calmer.
        next.title.setTypeface(appName == null ? medium : semibold);
        next.title.setVisibility(logo == null && !TextUtils.isEmpty(title) ? View.VISIBLE : View.GONE);
        next.appName.setText(appName);
        // The focused tile's label already names the app (user, 27 Oct): no app-name line here.
        next.appName.setVisibility(View.GONE);
        next.cont = cont;
        next.badge.setIcon(cont != null && artPkg != null ? badgeFor(artPkg) : null);
        if (cont != null) {
            next.title.setTypeface(semibold);
            // With the app's name (user, 29 Oct), uppercased here like the speaker card's.
            String app = artPkg == null ? "" : labelOf(artPkg).toString().toUpperCase(java.util.Locale.ROOT);
            next.eyebrow.setText(getString(cont.upNext ? R.string.up_next_app
                    : cont.music ? R.string.continue_playing_app : R.string.continue_watching_app, app).trim());
            next.episode.setText(ContinueWatching.episodeLine(cont));
            if (cont.hasBar()) {
                next.remaining.setText(ContinueWatching.remaining(cont));
                // Between two apps with progress, the bar grows or shrinks from the previous value.
                boolean prevBar = prev != null && prev.cont != null && prev.cont.hasBar();
                next.track.setFraction(prevBar ? prev.track.fraction() : -1f, cont.fraction());
            }
        }
        // One line in the continue layout: a two-line title pushes the block into the clock row.
        next.title.setMaxLines(cont != null ? 1 : 2);
        showContinue(next, cont != null);
        currentTitle = next;
        // Focus on Resume follows the block that replaces it, or falls back to the tile.
        if (zone == Zone.RESUME) {
            if (prev != null) {
                prev.resume.setOn(false, false);
                prev.badge.setOn(false, false);
            }
            if (cont != null) {
                next.resume.setOn(true, false);
                next.badge.setOn(true, false);
            } else {
                zone = Zone.ROW;
                focusRowTile(true);
            }
        }
        if (editingWidgets) refreshWidgetEdit();
        for (TitleBlock b : titles) {
            b.view.animate().alpha(b == next ? 1f : 0f).setDuration(Spec.ART_MS)
                    .setInterpolator(Spec.ART).setStartDelay(0).start();
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed (from an app, or on Home itself): back to the row.
        closeMenu(false);
        exitWidgetEdit();
        if (gridOpen) closeGrid();
        if (zone == Zone.CAPSULE) moveCapsule(KeyEvent.KEYCODE_DPAD_DOWN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(wallpaperTick);
        main.postDelayed(wallpaperTick, wallpaperIntervalMs);
        main.removeCallbacks(doze);
        main.postDelayed(doze, IDLE_MS);
        weather.resume();
        liveMedia.start(this::onLiveMediaChanged);
        if (cast != null) cast.start(this::onCastChanged);
        if (calendarEnabled && !hiddenWidgets.contains(W_CALENDAR)) calendarCard.start();
        // Back from an app opened here: its continue card shows where it was left.
        if (artPkg == null) requestArt(rowIndex);
        else if (artPkg.equals(launchedPkg)) refreshArt();
        launchedPkg = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(wallpaperTick);
        main.removeCallbacks(holdToMove);
        if (playCheck != null) main.removeCallbacks(playCheck);
        main.removeCallbacks(holdToEditWidgets);
        main.removeCallbacks(doze);
        if (dozing) {
            dozing = false;
            showFocus(true);
        }
        cancelHoldFill();
        exitWidgetEdit();
        weather.pause();
        liveMedia.stop();
        if (cast != null) cast.stop();
        calendarCard.stop();
        endMove();
        selectArmed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        art.releaseHidden();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) art.releaseHidden();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        getSharedPreferences(ContinueWatching.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(snapshotSaved);
        try {
            unregisterReceiver(packagesChanged);
        } catch (IllegalArgumentException ignore) { }
    }
}
