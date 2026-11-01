package com.adonzin.dockpad.service;

import android.os.IBinder;
import android.util.Log;

import com.adonzin.dockpad.core.Codes;

import java.lang.reflect.Method;

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

    /** Like "Clear all" in the recent apps screen, then go home. */
    private static void clearRecents() throws Exception {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity_task");
            Object atm = Class.forName("android.app.IActivityTaskManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            Method m = atm.getClass().getMethod("removeAllVisibleRecentTasks");
            m.invoke(atm);
        } catch (Throwable e) {
            Log.w("DockPad", "removeAllVisibleRecentTasks failed, falling back", e);
        }
        exec("am", "kill-all");
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
