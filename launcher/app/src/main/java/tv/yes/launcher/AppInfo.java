package tv.yes.launcher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** POJO for a launchable Leanback app. */
public class AppInfo {
    public final String packageName;
    public final CharSequence label;
    public final Drawable banner; // may be null
    public final Drawable icon;   // never null
    public final Intent launchIntent;

    public AppInfo(String packageName,
                   CharSequence label,
                   Drawable banner,
                   Drawable icon,
                   Intent launchIntent) {
        this.packageName = packageName;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launchIntent = launchIntent;
    }
}
