package tv.yes.launcher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves and fetches Top Shelf artwork for a dock package.
 *
 * Priority order:
 *   1) Continue-watching data (Watch Next, else the media-session snapshot)
 *   2) config.xml top_shelf_<alias> comma-separated titles (rotated)
 *   3) TMDB discover (watch_region from config.xml tmdb_region, with_watch_providers={pid})
 *
 * For the picked title:
 *   TMDB search/tv -> images (text-free backdrop, original size) -> external_ids (tvdb_id)
 *   fanart.tv tv/{tvdb_id} -> highest-voted showbackground + hdtvlogo/clearlogo
 *   (TMDB text-free backdrop when fanart.tv has no background)
 *
 * Network only on row focus change (plus the daily prefetch). Everything cached
 * on disk via DiskLruCache (up to 50 MB); discover results expire daily. Art is
 * decoded at screen size and composed into one bitmap; ArtView keeps at most two.
 */
public class TopShelfManager {

    private static final String TAG = "TopShelfManager";
    private static final long CACHE_MAX = 50L * 1024L * 1024L;
    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String TMDB_IMG = "https://image.tmdb.org/t/p/original";
    private static final String FANART = "https://webservice.fanart.tv/v3/tv/";

    public static class ArtPayload {
        public ArtView.Art art;      // composed backdrop + character, never null when delivered
        public Bitmap clearLogo;     // may be null
        public String title;
        public ContinueWatching.Info cont;   // real progress data, may be null
    }

    public interface Callback {
        void onArt(String packageName, ArtPayload payload);
        void onNoArt(String packageName, ContinueWatching.Info cont);
    }

    private static TopShelfManager instance;

    /** One per process: the activity and the daily prefetch share the disk cache. */
    public static synchronized TopShelfManager get(Context ctx) {
        if (instance == null) instance = new TopShelfManager(ctx);
        return instance;
    }

    private final Context ctx;
    private final OkHttpClient http;
    private final DiskLruCache cache;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final String tmdbKey;
    private final String fanartKey;
    private final int screenW;
    private final int screenH;
    private final int logoW;
    private final int logoH;

    // Rotating title indices per package (so repeated focuses rotate).
    private final HashMap<String, Integer> rotation = new HashMap<>();
    // The title each package showed last, so a refresh keeps the same show.
    private final Map<String, String> lastTitle = new java.util.concurrent.ConcurrentHashMap<>();

    // Only the newest request is worth decoding; older queued ones are skipped.
    private volatile String latestRequest;

    private TopShelfManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.http = Net.CLIENT;

        // Keys are compiled in from the TMDB_API_KEY / FANART_API_KEY environment variables.
        this.tmdbKey = BuildConfig.TMDB_API_KEY.trim();
        this.fanartKey = BuildConfig.FANART_API_KEY.trim();
        if (tmdbKey.isEmpty()) Log.w(TAG, "TMDB_API_KEY was not set at build time");

        DisplayMetrics dm = this.ctx.getResources().getDisplayMetrics();
        this.screenW = dm.widthPixels;
        this.screenH = dm.heightPixels;
        this.logoW = Math.round(Spec.TITLE_MAX_W * dm.density);
        this.logoH = Math.round(Spec.LOGO_MAX_H * dm.density);

        DiskLruCache cacheTmp = null;
        try {
            File dir = new File(this.ctx.getCacheDir(), "topshelf_cache");
            if (!dir.exists()) dir.mkdirs();
            cacheTmp = DiskLruCache.open(dir, 1, 1, CACHE_MAX);
        } catch (IOException e) {
            Log.w(TAG, "DiskLruCache open failed: " + e.getMessage());
        }
        this.cache = cacheTmp;
    }

    /**
     * Resolve, fetch and compose art for a row app off the main thread. Called on
     * focus change only. A request superseded before it starts is dropped without
     * a callback; the caller ignores results for anything but its current app.
     */
    public void requestFor(String packageName, Callback cb) {
        request(packageName, cb, false);
    }

    /** Same as requestFor, for the app already showing: fresh continue data, the same show as before. */
    public void refreshFor(String packageName, Callback cb) {
        request(packageName, cb, true);
    }

    private void request(final String packageName, final Callback cb, final boolean keepTitle) {
        latestRequest = packageName;
        exec.execute(() -> {
            if (!packageName.equals(latestRequest)) return;
            try {
                long t0 = System.currentTimeMillis();
                // Continue-watching data first: its show drives the art (design spec).
                ContinueWatching.Info cont = ContinueWatching.find(ctx, packageName);
                // The show's TMDB / fanart.tv art first (spec); else the artwork the app reported.
                // A music app has no show: a song title would find some unrelated series, so the wallpaper.
                ArtPayload p = null;
                if (!ContinueWatching.isMusic(ctx, packageName)) {
                    p = resolveBlocking(packageName, cont != null ? cont.title : null, keepTitle);
                    if (p == null && cont != null && cont.artUri != null) p = snapshotArt(cont);
                }
                Log.d(TAG, packageName + ": " + (p == null ? "no art" : p.title + ", logo=" + (p.clearLogo != null))
                        + ", continue=" + (cont == null ? "none" : cont.fromWatchNext ? "watch next" : "snapshot")
                        + " in " + (System.currentTimeMillis() - t0) + " ms");
                if (!packageName.equals(latestRequest)) return;
                if (p == null) {
                    cb.onNoArt(packageName, cont);
                } else {
                    p.cont = cont;
                    cb.onArt(packageName, p);
                }
            } catch (Throwable t) {
                Log.w(TAG, "requestFor " + packageName + " failed: " + t.getMessage(), t);
                cb.onNoArt(packageName, null);
            }
        });
    }

    /** Runs a decode job on the same thread, after any art request already queued. */
    public void runInBackground(Runnable job) {
        exec.execute(job);
    }

    /** Daily prefetch: warms the disk cache for every row app's titles. No decoding. */
    public void prefetchAllBlocking(String[] packages) {
        for (String pkg : packages) {
            List<String> titles = new ArrayList<>();
            String[] cfg = loadConfigTitles(pkg);
            if (cfg != null) for (String t : cfg) titles.add(t);
            if (titles.isEmpty()) {
                String t = discoverTitle(pkg);
                if (t != null) titles.add(t);
            }
            for (String title : titles) {
                try {
                    fetchTitle(title, false);
                } catch (Throwable t) {
                    Log.w(TAG, "prefetch " + pkg + " / " + title + " failed: " + t.getMessage());
                }
            }
        }
    }

    /** The artwork the app itself reported for what was playing, full screen behind the card. */
    private ArtPayload snapshotArt(ContinueWatching.Info cont) {
        try {
            byte[] bytes;
            android.net.Uri u = android.net.Uri.parse(cont.artUri);
            String scheme = u.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                bytes = fetchCached(cont.artUri);
            } else {
                try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                    bytes = in == null ? null : readAll(in);
                }
            }
            if (bytes == null) return null;
            Bitmap b = decodeToScreen(bytes, screenW, screenH);
            if (b == null) return null;
            ArtPayload p = new ArtPayload();
            p.title = cont.title;
            p.art = ArtComposer.compose("snapshot:" + cont.artUri, b, screenW, screenH);
            b.recycle();
            return p;
        } catch (Exception e) {
            Log.d(TAG, "snapshot art unavailable: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private ArtPayload resolveBlocking(String packageName, String continueTitle, boolean keepTitle) {
        String title = continueTitle;
        if (title == null && keepTitle) title = lastTitle.get(packageName);
        if (title == null) title = pickTitle(packageName);
        if (title == null || title.isEmpty()) return null;
        lastTitle.put(packageName, title);
        ArtPayload p = fetchTitle(title, true);
        if (p == null || p.art == null) return null;
        return p;
    }

    /** Fetches (and with decode=true, composes) the art for one show title. */
    private ArtPayload fetchTitle(String title, boolean decode) {
        TvInfo info = tmdbResolve(title);
        if (info == null) return null;

        // One clean, text-free landscape still per show: fanart.tv's showbackground,
        // else TMDB's text-free backdrop. No character cut-out.
        byte[] backdropBytes = null, logoBytes = null;
        if (info.tvdbId != null && !fanartKey.isEmpty()) {
            FanartPicks fp = fanartResolve(info.tvdbId);
            if (fp != null) {
                if (fp.backgroundUrl != null) backdropBytes = fetchCached(fp.backgroundUrl);
                if (fp.clearLogoUrl != null) logoBytes = fetchCached(fp.clearLogoUrl);
            }
        }
        if (backdropBytes == null && info.backdropPath != null) backdropBytes = fetchCached(TMDB_IMG + info.backdropPath);
        if (!decode) return null;

        Bitmap backdrop = backdropBytes != null ? decodeToScreen(backdropBytes, screenW, screenH) : null;
        if (backdrop == null) return null;

        ArtPayload p = new ArtPayload();
        p.title = title;
        p.art = ArtComposer.compose("show:" + title, backdrop, screenW, screenH);
        backdrop.recycle();
        if (logoBytes != null) p.clearLogo = decodeToScreen(logoBytes, logoW, logoH);
        return p;
    }

    // ---------- title selection ------------------------------------------

    private String pickTitle(String packageName) {
        String[] cfg = loadConfigTitles(packageName);

        // 2) If UsageStats shows this package was used in the last 24h, pin to
        //    the first config entry — "most recently relevant" surrogate for
        //    an actual Watch Next feed.
        if (cfg != null && cfg.length > 0 && wasUsedRecently(packageName)) {
            Log.d(TAG, "pickTitle: recent-usage -> " + cfg[0] + " for " + packageName);
            return cfg[0];
        }

        // 3) Config strings — rotate
        if (cfg != null && cfg.length > 0) {
            synchronized (rotation) {
                int idx = rotation.getOrDefault(packageName, -1);
                idx = (idx + 1) % cfg.length;
                rotation.put(packageName, idx);
                return cfg[idx];
            }
        }

        // 4) TMDB discover popular IL for the matching provider
        return discoverTitle(packageName);
    }

    /** The most popular IL show on this app's first TMDB watch provider, or null. */
    private String discoverTitle(String packageName) {
        int[] providers = loadWatchProviderIds(packageName);
        if (providers == null || providers.length == 0 || tmdbKey.isEmpty()) return null;
        return tmdbDiscoverTopTitle(providers[0]);
    }

    /** Returns true if the given package has a UsageStats lastTimeUsed within
     *  the last 24h. Requires PACKAGE_USAGE_STATS granted (appops). Silent on failure. */
    private boolean wasUsedRecently(String packageName) {
        try {
            Map<String, Long> map = queryUsageMap();
            if (map == null) return false;
            Long t = map.get(packageName);
            if (t == null) return false;
            long age = System.currentTimeMillis() - t;
            return age >= 0 && age < 24L * 60L * 60L * 1000L;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns map of package -> lastTimeUsed over the past week, or null if
     *  UsageStats is unavailable (permission denied etc). */
    public Map<String, Long> queryUsageMap() {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long end = System.currentTimeMillis();
            long begin = end - 7L * 24L * 60L * 60L * 1000L;
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_WEEKLY, begin, end);
            if (stats == null || stats.isEmpty()) {
                return null;
            }
            Map<String, Long> out = new HashMap<>();
            for (UsageStats s : stats) {
                String pkg = s.getPackageName();
                long t = s.getLastTimeUsed();
                Long existing = out.get(pkg);
                if (existing == null || t > existing) out.put(pkg, t);
            }
            return out;
        } catch (Throwable t) {
            Log.d(TAG, "queryUsageMap failed: " + t.getMessage());
            return null;
        }
    }

    /** config.xml resource {prefix}{alias} for this package, or 0. */
    private int aliasResId(String packageName, String prefix, String type) {
        String alias = aliasFor(packageName);
        if (alias == null) return 0;
        return ctx.getResources().getIdentifier(prefix + alias, type, ctx.getPackageName());
    }

    private String[] loadConfigTitles(String packageName) {
        int id = aliasResId(packageName, "top_shelf_", "string");
        if (id == 0) return null;
        String csv = ctx.getResources().getString(id);
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] parts = csv.split(",");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private int[] loadWatchProviderIds(String packageName) {
        int id = aliasResId(packageName, "watch_providers_", "array");
        if (id == 0) return null;
        String[] arr = ctx.getResources().getStringArray(id);
        if (arr == null || arr.length == 0) return null;
        int[] out = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            try { out[i] = Integer.parseInt(arr[i].trim()); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    /** Map package -> alias used in config.xml resource names. */
    private String aliasFor(String pkg) {
        if (pkg == null) return null;
        if (pkg.contains("yesplus"))      return "yesplus";
        if (pkg.contains("netflix"))      return "netflix";
        if (pkg.contains("disney"))       return "disneyplus";
        if (pkg.contains("appletv"))      return "appletv";
        if (pkg.contains("youtube"))      return "youtube";
        if (pkg.contains("stremio"))      return "stremio";
        return null;
    }

    // ---------- TMDB -----------------------------------------------------

    private static class TvInfo {
        String backdropPath;
        String tvdbId;
    }

    private TvInfo tmdbResolve(String title) {
        if (tmdbKey.isEmpty()) {
            Log.w(TAG, "tmdbResolve: tmdbKey empty, skipping");
            return null;
        }
        try {
            String q = URLEncoder.encode(title, "UTF-8");
            String searchUrl = TMDB + "/search/tv?api_key=" + tmdbKey +
                    "&query=" + q + "&include_adult=false";
            JSONObject js = fetchJson(searchUrl);
            if (js == null) return null;
            JSONArray results = js.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.d(TAG, "tmdbResolve: zero results for title=" + title);
                return null;
            }
            JSONObject first = results.getJSONObject(0);
            int id = first.optInt("id", 0);
            if (id == 0) return null;

            TvInfo info = new TvInfo();
            // JSONObject.optString(key, null) returns the string "null" when the
            // field is JSON null — not Java null. Guard explicitly.
            info.backdropPath = first.isNull("backdrop_path")
                    ? null : first.optString("backdrop_path", null);
            if (info.backdropPath != null && info.backdropPath.isEmpty()) {
                info.backdropPath = null;
            }

            // Prefer a backdrop with no language, i.e. no title text burned in.
            JSONObject images = fetchJson(TMDB + "/tv/" + id + "/images?api_key=" + tmdbKey
                    + "&include_image_language=null");
            if (images != null) {
                JSONArray backdrops = images.optJSONArray("backdrops");
                String best = null;
                double bestVote = -1;
                for (int i = 0; backdrops != null && i < backdrops.length(); i++) {
                    JSONObject b = backdrops.optJSONObject(i);
                    if (b == null || !b.isNull("iso_639_1") || b.isNull("file_path")) continue;
                    if (b.optInt("width", 0) < 1920) continue;
                    double v = b.optDouble("vote_average", 0);
                    if (v > bestVote) {
                        bestVote = v;
                        best = b.optString("file_path", null);
                    }
                }
                if (best != null && !best.isEmpty()) info.backdropPath = best;
            }

            String extUrl = TMDB + "/tv/" + id + "/external_ids?api_key=" + tmdbKey;
            JSONObject ext = fetchJson(extUrl);
            if (ext != null) {
                int tvdb = ext.optInt("tvdb_id", 0);
                if (tvdb > 0) info.tvdbId = Integer.toString(tvdb);
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "tmdbResolve failed: " + t.getMessage(), t);
            return null;
        }
    }

    private String tmdbDiscoverTopTitle(int providerId) {
        try {
            String url = TMDB + "/discover/tv?api_key=" + tmdbKey +
                    "&watch_region=" + ctx.getString(R.string.tmdb_region) +
                    "&with_watch_providers=" + providerId +
                    "&sort_by=popularity.desc";
            JSONObject js = fetchJson(url, url + "#" + (System.currentTimeMillis() / 86400000L));
            if (js == null) return null;
            JSONArray results = js.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.d(TAG, "tmdbDiscoverTopTitle: zero results for provider=" + providerId);
                return null;
            }
            JSONObject first = results.getJSONObject(0);
            if (first.isNull("name")) return null;
            String name = first.optString("name", null);
            return (name == null || name.isEmpty()) ? null : name;
        } catch (Throwable t) {
            Log.w(TAG, "tmdbDiscoverTopTitle failed: " + t.getMessage(), t);
            return null;
        }
    }

    // ---------- fanart.tv ------------------------------------------------

    private static class FanartPicks {
        String backgroundUrl;
        String clearLogoUrl;
    }

    private FanartPicks fanartResolve(String tvdbId) {
        if (fanartKey.isEmpty() || tvdbId == null) return null;
        try {
            String url = FANART + tvdbId + "?api_key=" + fanartKey;
            JSONObject root = fetchJson(url);
            if (root == null) return null;

            FanartPicks fp = new FanartPicks();
            fp.backgroundUrl = pickHighestVoted(root.optJSONArray("showbackground"));
            fp.clearLogoUrl = pickHighestVoted(root.optJSONArray("hdtvlogo"));
            if (fp.clearLogoUrl == null) {
                fp.clearLogoUrl = pickHighestVoted(root.optJSONArray("clearlogo"));
            }
            return fp;
        } catch (Throwable t) {
            Log.w(TAG, "fanartResolve failed: " + t.getMessage());
            return null;
        }
    }

    private static String pickHighestVoted(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        String bestUrl = null;
        int bestLikes = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.isNull("url")) continue;
            String u = o.optString("url", null);
            if (u == null || u.isEmpty()) continue;
            int likes = 0;
            try { likes = Integer.parseInt(o.optString("likes", "0")); } catch (Throwable ignore) {}
            if (likes > bestLikes) {
                bestLikes = likes;
                bestUrl = u;
            }
        }
        return bestUrl;
    }

    // ---------- caching / networking ------------------------------------

    private byte[] fetchCached(String url) {
        return fetchCached(url, url);
    }

    private JSONObject fetchJson(String url) throws JSONException {
        return fetchJson(url, url);
    }

    private JSONObject fetchJson(String url, String cacheId) throws JSONException {
        byte[] body = fetchCached(url, cacheId);
        return body == null ? null : new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    /** cacheId lets a caller expire an entry (e.g. one per day) without changing the URL. */
    private byte[] fetchCached(String url, String cacheId) {
        if (url == null) return null;
        String key = sha1(cacheId);
        if (cache != null) {
            try {
                DiskLruCache.Snapshot snap = cache.get(key);
                if (snap != null) {
                    try (InputStream in = snap.getInputStream(0)) {
                        return readAll(in);
                    } finally {
                        snap.close();
                    }
                }
            } catch (IOException ignore) {}
        }
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return null;
                ResponseBody rb = resp.body();
                if (rb == null) return null;
                byte[] bytes = rb.bytes();
                if (cache != null && bytes != null) {
                    try {
                        DiskLruCache.Editor ed = cache.edit(key);
                        if (ed != null) {
                            try (OutputStream out = ed.newOutputStream(0)) {
                                out.write(bytes);
                            }
                            ed.commit();
                            cache.flush();
                        }
                    } catch (IOException ignore) {}
                }
                return bytes;
            }
        } catch (Throwable t) {
            Log.w(TAG, "HTTP failed: " + t.getMessage());   // no URL: it carries the API key
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap decodeToScreen(byte[] bytes, int wTarget, int hTarget) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        o.inSampleSize = calcInSample(o.outWidth, o.outHeight, wTarget, hTarget);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    private static int calcInSample(int w, int h, int wTarget, int hTarget) {
        int sample = 1;
        while (w / (sample * 2) >= wTarget && h / (sample * 2) >= hTarget) sample *= 2;
        return Math.max(1, sample);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[bytes[i] & 0xF];
            }
            return new String(hex);
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
