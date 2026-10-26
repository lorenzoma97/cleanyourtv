package it.lorenzoma97.casa;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Refreshes shelf.json every 6 hours on the network, and once on demand when the launcher finds it stale. */
public class RefreshJob extends JobService {
    private static final int PERIODIC = 1, NOW = 2;
    private volatile Thread worker;

    /** Idempotent: the periodic job is scheduled once and survives reboots. */
    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(PERIODIC) != null) return;
        js.schedule(new JobInfo.Builder(PERIODIC, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L * 3600 * 1000)
                .setPersisted(true)
                .build());
    }

    /** One refresh as soon as there is network; asking again while one is pending does nothing. */
    static void kick(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(NOW) != null) return;
        js.schedule(new JobInfo.Builder(NOW, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean force = params.getJobId() == PERIODIC;
        worker = new Thread(() -> {
            try {
                Shelf.refresh(getApplicationContext(), force);
            } catch (Exception | LinkageError e) {
                android.util.Log.w("Casa", "refresh failed: " + e);
            }
            jobFinished(params, false);
        }, "casa-refresh");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread w = worker;
        if (w != null) w.interrupt();
        return true;
    }
}
