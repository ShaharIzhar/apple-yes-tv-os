package tv.yes.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Continue-watching fallback when Watch Next is unavailable: keeps a snapshot of
 * each app's last media session (title, subtitle, position, duration exactly as
 * the app reports them), saved when playback pauses or stops or the session ends.
 * Exists only so MediaSessionManager lets the launcher see other apps' sessions;
 * it ignores notifications. Enable with:
 *   cmd notification allow_listener tv.yes.launcher/tv.yes.launcher.MediaListener
 */
public class MediaListener extends NotificationListenerService {

    private static final String TAG = "MediaListener";

    /**
     * Each app's own way back into its player (the session activity), kept from
     * the last time it played. Resume uses it after the session itself has
     * ended; in memory only, so it is gone after the launcher process restarts.
     */
    private static final Map<String, PendingIntent> players = new ConcurrentHashMap<>();

    static PendingIntent lastPlayer(String pkg) {
        return players.get(pkg);
    }
    private MediaSessionManager sessions;
    private final Map<MediaSession.Token, Watcher> watchers = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    private final class Watcher extends MediaController.Callback {
        final MediaController controller;
        boolean playing;

        Watcher(MediaController c) {
            controller = c;
            PlaybackState s = c.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            boolean nowPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            if (nowPlaying && !playing) {
                PendingIntent player = controller.getSessionActivity();
                if (player != null) players.put(controller.getPackageName(), player);
                MediaMetadata md = controller.getMetadata();
                Log.d(TAG, controller.getPackageName() + " playing, metadata keys="
                        + (md == null ? "none" : new java.util.ArrayList<>(md.keySet()).toString()));
            }
            if (playing && !nowPlaying) save(controller);
            playing = nowPlaying;
        }

        @Override public void onSessionDestroyed() {
            if (playing) save(controller);
            playing = false;
        }
    }

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions not readable: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Watcher w : watchers.values()) w.controller.unregisterCallback(w);
        watchers.clear();
    }

    private void track(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            MediaSession.Token key = c.getSessionToken();
            if (watchers.containsKey(key)) continue;
            Watcher w = new Watcher(c);
            c.registerCallback(w);
            watchers.put(key, w);
        }
    }

    private void save(MediaController c) {
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        if (m == null) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = 0;
        if (s != null && s.getPosition() >= 0) {
            position = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
        }
        // The artwork the app reports for what is playing: a bitmap, else a URI.
        String artUri = null;
        android.graphics.Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) {
            artUri = ContinueWatching.saveArt(this, c.getPackageName(), art);
        } else {
            for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                String u = m.getString(k);
                if (u != null && !u.isEmpty()) {
                    artUri = u;
                    break;
                }
            }
        }
        // What the app calls this item: YouTube and Stremio put a video / episode id here (Resume's deep link).
        String mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String mediaUri = m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        ContinueWatching.saveSnapshot(this, c.getPackageName(), title, subtitle, position, Math.max(0, duration), artUri,
                mediaId, mediaUri);
        Log.d(TAG, "snapshot saved for " + c.getPackageName() + ": keys=" + new java.util.ArrayList<>(m.keySet())
                + ", mediaId=" + mediaId + ", mediaUri=" + mediaUri
                + ", duration " + (duration > 0 ? "yes" : "no") + ", art " + (art != null ? "bitmap" : artUri != null ? "uri" : "none"));
    }

    private static String text(MediaMetadata m, String first, String second) {
        CharSequence t = m.getText(first);
        if (t == null || t.length() == 0) t = m.getText(second);
        return t == null ? null : t.toString();
    }
}
