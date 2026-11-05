package com.adonzin.dockpad;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.adonzin.dockpad.adb.Starter;

/**
 * Checks every 15 minutes (and when a network connects) that DockPad's background service is
 * still running, and starts it again if it should be on. Built-in mode only.
 */
public class Watchdog extends JobService {
    private static final int JOB_ID = 4242;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Watchdog.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Prefs prefs = new Prefs(this);
        if (!prefs.paired() || !prefs.bridgeWanted() || !prefs.autoStart()) return false;
        new Thread(() -> {
            boolean retry = false;
            try {
                if (!Starter.isRunning(this)) {
                    Starter.Result r = Starter.start(this, prefs.turnOffWirelessDebugging());
                    if (r.ok) prefs.applyTo(Starter.bridge(this), true);
                    else retry = !r.needsPairing;
                } else {
                    // running: make sure the bridge itself is on
                    prefs.applyTo(Starter.bridge(this), true);
                }
            } catch (Exception ignored) {
            }
            jobFinished(params, retry);
        }, "DockPad-watchdog").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
