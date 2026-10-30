package tv.yes.launcher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/**
 * Daily prefetch of Top Shelf artwork for every row app, keeping the disk
 * cache warm; runs even when the launcher is not in the foreground. On the
 * platform JobScheduler: WorkManager kept three threads and its own database
 * in the launcher's process all the time.
 */
public class PrefetchWorker extends JobService {

    private static final int JOB_ID = 2610;

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            String[] packages = getResources().getStringArray(R.array.row_apps);
            TopShelfManager.get(this).prefetchAllBlocking(packages);
            jobFinished(params, false);
        }, "TopShelfPrefetch").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // retry later
    }

    /** Once a day on any network; kept across reboots. Already scheduled: left as it is. */
    public static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, PrefetchWorker.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setPersisted(true)
                .build());
    }
}
