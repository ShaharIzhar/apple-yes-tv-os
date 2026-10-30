package tv.yes.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The other apps' media sessions that exist right now, watched only while Home
 * is visible (start / stop). Lets Home play or pause an app without opening it.
 * Reading other apps' sessions needs MediaListener enabled as a notification
 * listener (install.sh does that); without it, nothing is live and Home falls
 * back to opening the app.
 */
final class LiveMedia {

    interface Listener {
        void onLiveMediaChanged();
    }

    private static final String TAG = "LiveMedia";

    private final Context ctx;
    private final ComponentName listenerComponent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private MediaSessionManager sessions;
    private Listener listener;
    private boolean notifyPosted;

    private final MediaController.Callback changed = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            postChanged();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            postChanged();
        }

        @Override public void onSessionDestroyed() {
            postChanged();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> {
        setControllers(list);
        postChanged();
    };

    LiveMedia(Context c) {
        ctx = c.getApplicationContext();
        listenerComponent = new ComponentName(ctx, MediaListener.class);
    }

    void start(Listener l) {
        listener = l;
        try {
            if (sessions == null) sessions = ctx.getSystemService(MediaSessionManager.class);
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, main);
            setControllers(sessions.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions not readable (MediaListener not enabled): " + e.getMessage());
        }
        postChanged();
    }

    void stop() {
        listener = null;
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        setControllers(null);
    }

    private void setControllers(List<MediaController> list) {
        for (MediaController c : controllers) c.unregisterCallback(changed);
        controllers.clear();
        if (list == null) return;
        for (MediaController c : list) {
            if (c.getPackageName().equals(ctx.getPackageName())) continue;
            c.registerCallback(changed, main);
            controllers.add(c);
        }
    }

    /** Several callbacks often arrive together (state, then metadata): one update for all of them. */
    private void postChanged() {
        if (notifyPosted) return;
        notifyPosted = true;
        main.post(() -> {
            notifyPosted = false;
            if (listener != null) listener.onLiveMediaChanged();
        });
    }

    /** The app's session, playing or not, or null. */
    MediaController forPackage(String pkg) {
        for (MediaController c : controllers) {
            if (c.getPackageName().equals(pkg)) return c;
        }
        return null;
    }

    /** The music app session to show: one that is playing, else one that is paused. */
    MediaController music() {
        MediaController paused = null;
        for (MediaController c : controllers) {
            if (!ContinueWatching.isMusic(ctx, c.getPackageName()) || c.getMetadata() == null) continue;
            int s = state(c);
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) return c;
            if (s == PlaybackState.STATE_PAUSED && paused == null) paused = c;
        }
        return paused;
    }

    static int state(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    static boolean isPlaying(MediaController c) {
        int s = state(c);
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
    }

    /** The title, as the app reports it. */
    static String title(MediaController c) {
        return text(c, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
    }

    /** The artist (or channel, for YouTube), as the app reports it; may be empty. */
    static String by(MediaController c) {
        return text(c, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    }

    private static String text(MediaController c, String first, String second) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return "";
        CharSequence t = m.getText(first);
        if (t == null || t.length() == 0) t = m.getText(second);
        return t == null ? "" : t.toString();
    }
}
