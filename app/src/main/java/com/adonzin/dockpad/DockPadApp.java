package com.adonzin.dockpad;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class DockPadApp extends Application {
    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        // keep the details of a crash, so Diagnostics can show what went wrong
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String trace = sw.toString();
                if (trace.length() > 6000) trace = trace.substring(0, 6000) + "\n…";
                String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date());
                try (java.io.FileOutputStream out = openFileOutput(CRASH_FILE, MODE_PRIVATE)) {
                    out.write(("Build " + BuildConfig.VERSION_CODE + ", " + when + ", thread " + t.getName() + "\n"
                            + trace).getBytes());
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    /** The last crash, or "" if none. */
    static String lastCrash(Context c) {
        try (java.io.FileInputStream in = c.openFileInput(CRASH_FILE)) {
            return new String(in.readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // The built-in ADB client uses Android's own TLS library, which needs this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L");
            } catch (Throwable ignored) {
            }
        }
    }
}
