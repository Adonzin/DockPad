package com.adonzin.dockpad.service;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Turning one screen off while docked: the handheld's own screen (playing on the TV) or the TV
 * (playing on the handheld, e.g. someone else wants the TV). Uses the same system call screen
 * mirroring tools use to blank the device's screen: the picture keeps going, only that panel is
 * powered down. Android powers screens back on when the handheld wakes, so a choice is put back
 * after waking; and both screens are never left off.
 */
final class Displays {
    private static final String TAG = "DockPad";
    private static final int POWER_OFF = 0, POWER_ON = 2;

    /** What the user chose (re-applied after waking). */
    static volatile boolean handheldOff, tvOff;

    private Displays() {
    }

    /** Switches the handheld's own screen on/off. Returns a message for the user. */
    static String toggleHandheld() {
        long[] ids = ids();
        if (ids.length < 2) return "Connect a TV or monitor first: the handheld's screen only turns off while docked.";
        if (!handheldOff && tvOff) return "The TV is off: turn it back on first, so one screen stays on.";
        boolean off = !handheldOff;
        if (!set(internal(ids), off)) return "This handheld didn't allow turning its screen off.";
        handheldOff = off;
        return off ? "Handheld screen off (the picture stays on the TV)" : "Handheld screen on";
    }

    /** Switches the TV (every external screen) on/off. */
    static String toggleTv() {
        long[] ids = ids();
        if (ids.length < 2) return "No TV or monitor is connected.";
        if (!tvOff && handheldOff) return "The handheld's screen is off: turn it back on first, so one screen stays on.";
        boolean off = !tvOff;
        long in = internal(ids);
        boolean ok = true;
        for (long id : ids) if (id != in) ok &= set(id, off);
        if (!ok) return "The TV didn't allow being turned off from here.";
        tvOff = off;
        return off ? "TV screen off (the picture stays on the handheld)" : "TV screen on";
    }

    /**
     * Keeps choices sensible: on waking, puts a choice back (Android turns every screen on);
     * when the TV is unplugged, turns the handheld's screen back on.
     */
    static void check(boolean justWoke) {
        if (!handheldOff && !tvOff) return;
        long[] ids = ids();
        if (ids.length < 2) { // undocked: the handheld screen must be on
            if (handheldOff) set(internal(ids), false);
            handheldOff = false;
            tvOff = false;
            return;
        }
        if (justWoke) {
            long in = internal(ids);
            if (handheldOff) set(in, true);
            if (tvOff) for (long id : ids) if (id != in) set(id, true);
        }
    }

    // ------------------------------------------------------------------ the system calls

    private static Class<?> sc() throws ClassNotFoundException {
        return Class.forName("android.view.SurfaceControl");
    }

    private static Method method(String name, Class<?>... args) throws Exception {
        try {
            Method m = sc().getMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            Method m = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(sc(), name, args);
            m.setAccessible(true);
            return m;
        }
    }

    static long[] ids() {
        try {
            long[] ids = (long[]) method("getPhysicalDisplayIds").invoke(null);
            return ids == null ? new long[0] : ids;
        } catch (Throwable t) {
            Log.w(TAG, "displays: " + t);
            return new long[0];
        }
    }

    /** The handheld's own screen: the one Android marks as internal (else the first). */
    private static long internal(long[] ids) {
        for (long id : ids) {
            try {
                Object info = method("getStaticDisplayInfo", long.class).invoke(null, id);
                if (info != null && info.getClass().getField("isInternal").getBoolean(info)) return id;
            } catch (Throwable ignored) {
            }
        }
        return ids.length > 0 ? ids[0] : -1;
    }

    private static boolean set(long id, boolean off) {
        try {
            IBinder token = (IBinder) method("getPhysicalDisplayToken", long.class).invoke(null, id);
            if (token == null) return false;
            method("setDisplayPowerMode", IBinder.class, int.class).invoke(null, token, off ? POWER_OFF : POWER_ON);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "display power: " + t);
            return false;
        }
    }

    static String describe() {
        long[] ids = ids();
        return "Screens: " + ids.length + (ids.length > 1 ? " (docked)" : "")
                + (handheldOff ? ", handheld screen off" : "") + (tvOff ? ", TV off" : "");
    }
}
