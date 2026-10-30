package tv.yes.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Queries the system PackageManager for every LEANBACK_LAUNCHER activity. */
public class AppLoader {

    public static List<AppInfo> loadLeanbackApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);

        List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
        List<AppInfo> out = new ArrayList<>(resolved.size());
        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) continue;
            if (ctx.getPackageName().equals(ri.activityInfo.packageName)) continue;

            ActivityInfo ai = ri.activityInfo;
            CharSequence label = ri.loadLabel(pm);
            if (label == null) label = ai.packageName;

            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
            } catch (Throwable ignore) { }
            Drawable icon;
            try {
                icon = ai.loadIcon(pm);
            } catch (Throwable t) {
                icon = pm.getDefaultActivityIcon();
            }

            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            launch.setClassName(ai.packageName, ai.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            out.add(new AppInfo(ai.packageName, label, banner, icon, launch));
        }

        final Collator coll = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return coll.compare(a.label.toString(), b.label.toString());
            }
        });
        return out;
    }

    /**
     * Build the dock list in the exact order declared in config.xml.
     * Apps missing from the device are silently skipped.
     */
    public static List<AppInfo> buildDock(Context ctx, String[] packageOrder, List<AppInfo> all) {
        Map<String, AppInfo> byPkg = new LinkedHashMap<>();
        for (AppInfo a : all) byPkg.put(a.packageName, a);
        List<AppInfo> dock = new ArrayList<>();
        for (String p : packageOrder) {
            AppInfo a = byPkg.get(p);
            if (a != null) dock.add(a);
        }
        return dock;
    }
}
