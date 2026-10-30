package tv.yes.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

/**
 * "Continue watching" data for one app, from real data only (design spec,
 * Continue watching): 1) Android TV Watch Next, 2) the launcher's snapshot of
 * the app's last media session, 3) nothing. Never invents fields.
 */
final class ContinueWatching {
    private ContinueWatching() {}

    private static final String TAG = "ContinueWatching";
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    static final String PREFS = "media_snapshots";

    /** One app's progress. Fields the source doesn't report stay null / 0. */
    static final class Info {
        String title;            // series (Watch Next) or the title as the app reported it (snapshot)
        String season;
        String episode;
        String episodeTitle;
        long position;
        long duration;
        String intentUri;        // Watch Next only: straight into the episode
        String artUri;           // snapshot only: the app's own artwork (file:, http(s): or content:)
        String mediaId;          // snapshot only: the app's id for the item (Resume's deep link)
        String mediaUri;         // snapshot only: the app's uri for the item, when it reports one
        boolean upNext;          // WATCH_NEXT_TYPE_NEXT: "UP NEXT", no bar
        boolean fromWatchNext;
        boolean music;           // a music app: "CONTINUE PLAYING", Play from Home

        boolean hasBar() {
            return !upNext && duration > 0 && position >= 0;
        }

        float fraction() {
            return hasBar() ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
        }
    }

    static Info find(Context ctx, String pkg) {
        Info i = watchNext(ctx, pkg);
        if (i == null) i = snapshot(ctx, pkg);
        if (i != null) i.music = isMusic(ctx, pkg);
        return i;
    }

    private static java.util.Set<String> musicApps;

    /** config.xml music_apps, or an app that declares itself an audio app. */
    static boolean isMusic(Context ctx, String pkg) {
        synchronized (ContinueWatching.class) {
            if (musicApps == null) {
                musicApps = new java.util.HashSet<>(java.util.Arrays.asList(
                        ctx.getResources().getStringArray(R.array.music_apps)));
            }
        }
        if (musicApps.contains(pkg)) return true;
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).category
                    == android.content.pm.ApplicationInfo.CATEGORY_AUDIO;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The app's most recent Watch Next row, or null when there is none or the provider refuses. */
    static Info watchNext(Context ctx, String pkg) {
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
        };
        try (Cursor c = ctx.getContentResolver().query(WATCH_NEXT, cols,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME + "=?", new String[]{pkg},
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            Info i = new Info();
            i.fromWatchNext = true;
            i.title = c.getString(0);
            i.season = c.getString(1);
            i.episode = c.getString(2);
            i.episodeTitle = c.getString(3);
            i.position = c.isNull(4) ? 0 : c.getLong(4);
            i.duration = c.isNull(5) ? 0 : c.getLong(5);
            i.intentUri = c.getString(6);
            i.upNext = !c.isNull(7) && c.getInt(7) == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT;
            return TextUtils.isEmpty(i.title) ? null : i;
        } catch (RuntimeException e) {
            Log.d(TAG, "Watch Next unavailable for " + pkg + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Logs what Watch Next returns for each package, without any titles (a one-line probe). */
    static void probe(Context ctx, String[] pkgs) {
        StringBuilder sb = new StringBuilder("Watch Next probe:");
        try (Cursor c = ctx.getContentResolver().query(WATCH_NEXT,
                new String[]{TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME}, null, null, null)) {
            sb.append(" visible rows=").append(c == null ? "null" : c.getCount());
            java.util.HashMap<String, Integer> by = new java.util.HashMap<>();
            while (c != null && c.moveToNext()) {
                String p = c.getString(0);
                by.put(p, by.getOrDefault(p, 0) + 1);
            }
            for (String p : pkgs) sb.append(", ").append(p).append('=').append(by.getOrDefault(p, 0));
        } catch (RuntimeException e) {
            sb.append(" refused: ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
        }
        Log.i(TAG, sb.toString());
    }

    /** The last media-session snapshot MediaListener saved for this app. */
    static Info snapshot(Context ctx, String pkg) {
        String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pkg, null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            Info i = new Info();
            i.title = o.optString("title", null);
            i.episodeTitle = o.isNull("subtitle") ? null : o.optString("subtitle", null);
            i.position = o.optLong("position", 0);
            i.duration = o.optLong("duration", 0);
            i.artUri = o.isNull("art") ? null : o.optString("art", null);
            i.mediaId = o.isNull("mediaId") ? null : o.optString("mediaId", null);
            i.mediaUri = o.isNull("mediaUri") ? null : o.optString("mediaUri", null);
            return TextUtils.isEmpty(i.title) ? null : i;
        } catch (Exception e) {
            return null;
        }
    }

    static void saveSnapshot(Context ctx, String pkg, String title, String subtitle, long position, long duration,
                             String artUri, String mediaId, String mediaUri) {
        if (TextUtils.isEmpty(title)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            if (!TextUtils.isEmpty(subtitle)) o.put("subtitle", subtitle);
            o.put("position", position);
            o.put("duration", duration);
            o.put("saved", System.currentTimeMillis());
            if (artUri != null) o.put("art", artUri);
            if (!TextUtils.isEmpty(mediaId)) o.put("mediaId", mediaId);
            if (!TextUtils.isEmpty(mediaUri)) o.put("mediaUri", mediaUri);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString(pkg, o.toString()).apply();
        } catch (Exception ignore) { }
    }

    /** Stores a reported artwork bitmap as a JPEG (at most screen width); returns its file: URI. */
    static String saveArt(Context ctx, String pkg, android.graphics.Bitmap art) {
        try {
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "snapshot_art");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, pkg + ".jpg");
            android.graphics.Bitmap b = art;
            int max = ctx.getResources().getDisplayMetrics().widthPixels;
            if (b.getWidth() > max) {
                b = android.graphics.Bitmap.createScaledBitmap(b, max, Math.round(b.getHeight() * max / (float) b.getWidth()), true);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            }
            return Uri.fromFile(f).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** "S2, E5 · Title", "E3 · Title", or the title alone (films, snapshots). */
    static String episodeLine(Info i) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(i.season) && !TextUtils.isEmpty(i.episode)) {
            sb.append('S').append(i.season).append(", E").append(i.episode);
        } else if (!TextUtils.isEmpty(i.episode)) {
            sb.append('E').append(i.episode);
        }
        if (!TextUtils.isEmpty(i.episodeTitle)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(i.episodeTitle);
        }
        return sb.toString();
    }

    /**
     * A link straight into the item from the snapshot, for the apps whose ids say
     * what they are: YouTube (an 11-character video id, at the saved second) and
     * Stremio (an IMDb id, "tt…" or "tt…:season:episode", its episode page).
     * Otherwise the app's own media uri when it reported one; else null.
     */
    static android.content.Intent deepLink(Info i, String pkg) {
        String id = i.mediaId;
        if (id != null && pkg.contains("youtube") && id.matches("[A-Za-z0-9_-]{11}")) {
            return new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=" + id + "&t=" + (i.position / 1000) + "s")).setPackage(pkg);
        }
        if (id != null && pkg.contains("stremio") && id.matches("tt\\d+(:\\d+:\\d+)?")) {
            String imdb = id.split(":")[0];
            String type = id.contains(":") ? "series" : "movie";
            return new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    Uri.parse("stremio:///detail/" + type + "/" + imdb + "/" + id)).setPackage(pkg);
        }
        if (i.mediaUri != null && !i.mediaUri.startsWith("file:")) {
            return new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(i.mediaUri)).setPackage(pkg);
        }
        return null;
    }

    /** "21 min left" / "1 h 5 min left". */
    static String remaining(Info i) {
        long mins = Math.max(1, Math.round((i.duration - i.position) / 60000.0));
        if (mins < 60) return mins + " min left";
        long h = mins / 60, m = mins % 60;
        return m == 0 ? h + " h left" : h + " h " + m + " min left";
    }
}
