package com.adonzin.dockpad.service;

import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.adonzin.dockpad.BuildConfig;
import com.adonzin.dockpad.core.Codes;

import java.lang.reflect.Method;
import java.util.List;

/** Special shortcut actions that aren't button presses. Runs with ADB ("shell") privileges. */
final class Actions {
    private Actions() {}

    static boolean isAction(int code) {
        return code <= -100;
    }

    static void run(int code) {
        Thread t = new Thread(() -> {
            try {
                if (code == Codes.ACTION_CLEAR_RECENTS) clearRecents();
            } catch (Throwable e) {
                Log.e("DockPad", "action " + code, e);
            }
        }, "DockPad-action");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Like "Clear all" in the recent apps screen, then go home. DockPad itself (and the home
     * screen) are left alone, so DockPad keeps running.
     */
    private static void clearRecents() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity_task");
        Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        String self = BuildConfig.APPLICATION_ID;
        boolean done = false;
        try {
            Object slice = atm.getClass().getMethod("getRecentTasks", int.class, int.class, int.class)
                    .invoke(atm, 100, 2 /* RECENT_IGNORE_UNAVAILABLE */, 0);
            List<?> tasks = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
            Method remove = atm.getClass().getMethod("removeTask", int.class);
            for (Object t : tasks) {
                Intent base = (Intent) t.getClass().getField("baseIntent").get(t);
                String pkg = base != null && base.getComponent() != null ? base.getComponent().getPackageName() : null;
                if (self.equals(pkg)) continue;
                if (base != null && base.hasCategory(Intent.CATEGORY_HOME)) continue;
                int id = t.getClass().getField("taskId").getInt(t);
                try {
                    remove.invoke(atm, id);
                } catch (Throwable e) {
                    Log.w("DockPad", "couldn't remove task " + id, e);
                }
            }
            done = true;
        } catch (Throwable e) {
            Log.w("DockPad", "per-task clearing failed", e);
        }
        if (!done) {
            // Fallback: clears everything; DockPad's background service keeps running regardless.
            atm.getClass().getMethod("removeAllVisibleRecentTasks").invoke(atm);
        }
        exec("input", "keyevent", "KEYCODE_HOME");
    }

    private static void exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().close();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }
}
