package tv.yes.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home button to this launcher. On this Google TV build the
 * stock launcher's HOME filter has priority 2, which Android picks before any
 * saved Home preference, so set-home-activity and the Home role cannot win.
 * This service takes the Home key (press and release) and opens this launcher
 * itself; every other key passes through untouched. The capsule has no
 * Google TV button any more (user, 30 Oct): the stock home is never wanted.
 *
 * Backstop: the key filter is sometimes not armed (after the process is killed
 * and the service rebinds), and Home can arrive without a key press. So when
 * the stock home screen itself comes to the front, this launcher replaces it. On this
 * firmware a Home also makes the partner customizer open yes+ about half a
 * second later, on top of everything: for a few seconds after a backstop
 * those follow-up windows are sent back here too.
 *
 * Enable over adb (append to any services already listed):
 *   settings put secure enabled_accessibility_services tv.yes.launcher/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKeyService";

    private static final long FOLLOW_UP_MS = 6000L;   // yes+ can take over 2 s to show its first window
    /** Until then, the firmware's Home follow-ups are bounced; cleared when the user opens an app from here. */
    static volatile long followUpUntil;

    private ComponentName stockHome;

    @Override
    protected void onServiceConnected() {
        // The activity Android itself opens for Home: only that window is replaced,
        // never the stock launcher's other screens.
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (ri != null && ri.activityInfo != null && !getPackageName().equals(ri.activityInfo.packageName)) {
            stockHome = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        }
        Log.d(TAG, "connected, stock home " + stockHome);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        // On the press, not the release: Home starts opening while the button is still down.
        // The release (and any repeats of a held button) is still consumed.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) openHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (stockHome == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName());
        if (stockHome.getPackageName().equals(pkg)
                && stockHome.getClassName().equals(String.valueOf(event.getClassName()))) {
            Log.d(TAG, "stock home came to the front; opening this launcher");
            followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS;
            openHome();
        } else if (System.currentTimeMillis() < followUpUntil) {
            Log.d(TAG, pkg + " came up right after Home; opening this launcher again");
            openHome();
        }
    }

    /**
     * Started like an app, not with a Home intent: Android's Home stays the
     * stock launcher, and making this launcher the home process would take
     * the remote's app buttons away from it (cleanyourtv #1274).
     */
    private void openHome() {
        try {
            startActivity(new Intent(this, LauncherActivity.class)
                    .addCategory(Intent.CATEGORY_HOME)
                    // No window transition: Home appears at once instead of after Android's open animation.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "open home failed: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
    }
}
