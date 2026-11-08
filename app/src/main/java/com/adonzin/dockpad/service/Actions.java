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

    /** Set by the bridge so actions can reach the virtual devices and PC mode. */
    static volatile Bridge bridge;

    static boolean isAction(int code) {
        return code <= -100;
    }

    static void run(int code, String param, Object unused) {
        Thread t = new Thread(() -> {
            try {
                runNow(code, param == null ? "" : param);
            } catch (Throwable e) {
                Log.e("DockPad", "action " + code, e);
            }
        }, "DockPad-action");
        t.setDaemon(true);
        t.start();
    }

    private static void runNow(int code, String param) throws Exception {
        Bridge b = bridge;
        Extras x = b == null ? null : b.extras();
        switch (code) {
            case Codes.ACTION_CLEAR_RECENTS: clearRecents(); break;
            case Codes.ACTION_FORCE_CLOSE: forceCloseCurrent(); break;
            case Codes.ACTION_PREVIOUS_APP:
                exec("input", "keyevent", "KEYCODE_APP_SWITCH");
                Thread.sleep(300);
                exec("input", "keyevent", "KEYCODE_APP_SWITCH");
                break;
            case Codes.ACTION_SCREENSHOT: screenshot(); break;
            case Codes.ACTION_NOTIFICATIONS: exec("cmd", "statusbar", "expand-notifications"); break;
            case Codes.ACTION_QUICK_SETTINGS: exec("cmd", "statusbar", "expand-settings"); break;
            case Codes.ACTION_CLOSE_PANELS: exec("cmd", "statusbar", "collapse"); break;
            case Codes.ACTION_TOGGLE_IME_SETTING: {
                String v = exec("settings", "get", "secure", "show_ime_with_hard_keyboard").trim();
                exec("settings", "put", "secure", "show_ime_with_hard_keyboard", "1".equals(v) ? "0" : "1");
                break;
            }
            case Codes.ACTION_OPEN_APP: openApp(param); break;
            case Codes.ACTION_TOGGLE_PC_MODE: if (b != null) b.setPcMode(!b.isPcMode()); break;
            // system-only keys: apps never see these, so Android's own injection is fine
            case Codes.ACTION_SLEEP: exec("input", "keyevent", "KEYCODE_SLEEP"); break;
            case Codes.ACTION_BRIGHTNESS_UP: exec("input", "keyevent", "KEYCODE_BRIGHTNESS_UP"); break;
            case Codes.ACTION_BRIGHTNESS_DOWN: exec("input", "keyevent", "KEYCODE_BRIGHTNESS_DOWN"); break;
            case Codes.ACTION_PLAY_PAUSE: exec("input", "keyevent", "KEYCODE_MEDIA_PLAY_PAUSE"); break;
            case Codes.ACTION_NEXT_TRACK: exec("input", "keyevent", "KEYCODE_MEDIA_NEXT"); break;
            case Codes.ACTION_PREVIOUS_TRACK: exec("input", "keyevent", "KEYCODE_MEDIA_PREVIOUS"); break;
            case Codes.ACTION_MUTE: exec("input", "keyevent", "KEYCODE_VOLUME_MUTE"); break;
            case Codes.ACTION_DOCKPAD_KEYBOARD:
                if (b == null) break;
                if (b.isKeyboardOpen()) {
                    b.setKeyboardOpen(false); // the keyboard sees this and closes
                } else {
                    b.setKeyboardOpen(true);
                    exec("am", "broadcast", "-n", BuildConfig.APPLICATION_ID + "/" + BuildConfig.APPLICATION_ID
                            + ".KeyboardReceiver");
                }
                break;
            case Codes.ACTION_TOGGLE_TURBO: if (b != null) b.setTurboOn(!b.isTurboOn()); break;
            case Codes.ACTION_HANDHELD_SCREEN: toast(Displays.toggleHandheld()); break;
            case Codes.ACTION_TV_SCREEN: toast(Displays.toggleTv()); break;
            case Codes.ACTION_SWAP_AB: if (b != null) b.toggleSwap(true, false); break;
            case Codes.ACTION_SWAP_XY: if (b != null) b.toggleSwap(false, true); break;
            case Codes.ACTION_SWAP_FACE: if (b != null) b.toggleSwap(true, true); break;
            case Codes.ACTION_MOONLIGHT_KEYBOARD: if (x != null) x.threeFingerTap(); break;
            case Codes.ACTION_QUICK_MENU:
                // a second press while it's open closes it (the menu handles that itself)
                exec("am", "start", "-n", BuildConfig.APPLICATION_ID + "/" + BuildConfig.APPLICATION_ID
                        + ".QuickMenuActivity", "--activity-no-animation");
                break;
            // Home/Back: pressed on the built-in controls, so apps see a real controller key
            // (Android's injection tool shows up as an unknown "Virtual" device in some apps)
            case Codes.ACTION_HOME:
                if (b == null || !b.tapBuiltInKey(Codes.KEY_HOME)) exec("input", "keyevent", "KEYCODE_HOME");
                break;
            case Codes.ACTION_BACK:
                if (b == null || !b.tapBuiltInKey(Codes.KEY_BACK)) exec("input", "keyevent", "KEYCODE_BACK");
                break;
        }
    }

    /** Presses a key on the virtual keyboard (fast), or falls back to the slower `input` tool. */
    private static void key(Extras x, int evdevKey, String keyName) {
        if (x != null) x.tap(evdevKey);
        else exec("input", "keyevent", keyName);
    }

    /** Saves a screenshot without sending any key (some systems pass the key on to the game). */
    private static void screenshot() {
        String name = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + "_DockPad.png";
        String dir = "/sdcard/Pictures/Screenshots";
        exec("mkdir", "-p", dir);
        String path = dir + "/" + name;
        exec("screencap", "-p", path);
        // make it show up in the gallery, and let the app show "Screenshot saved"
        exec("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + path);
        exec("am", "broadcast", "-n", BuildConfig.APPLICATION_ID + "/" + BuildConfig.APPLICATION_ID
                + ".MessageReceiver", "--es", "message", "Screenshot saved to Pictures/Screenshots");
    }

    /** Shows a short message on screen (through the app). */
    static void toast(String message) {
        new Thread(() -> exec("am", "broadcast", "-n", BuildConfig.APPLICATION_ID + "/" + BuildConfig.APPLICATION_ID
                + ".MessageReceiver", "--es", "message", message), "DockPad-toast").start();
    }

    private static String launcherPackage() {
        String out = exec("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME");
        String[] lines = out.trim().split("\n");
        String last = lines[lines.length - 1].trim();
        return last.contains("/") ? last.substring(0, last.indexOf('/')) : "";
    }

    private static void forceCloseCurrent() {
        String out = exec("dumpsys", "window", "displays");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("mCurrentFocus=Window\\{[^}]*\\s([A-Za-z0-9_.]+)/").matcher(out);
        String pkg = m.find() ? m.group(1) : null;
        if (pkg == null) {
            m = java.util.regex.Pattern.compile("mFocusedApp=.*?\\s([A-Za-z0-9_.]+)/").matcher(out);
            pkg = m.find() ? m.group(1) : null;
        }
        if (pkg == null || pkg.equals(BuildConfig.APPLICATION_ID) || pkg.equals(launcherPackage())
                || pkg.equals("com.android.systemui")) return;
        exec("am", "force-stop", pkg);
    }

    private static void openApp(String param) {
        String pkg = param.contains("|") ? param.substring(0, param.indexOf('|')) : param;
        if (pkg.isEmpty()) return;
        String out = exec("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", pkg);
        String[] lines = out.trim().split("\n");
        String comp = lines[lines.length - 1].trim();
        if (comp.contains("/")) exec("am", "start", "-n", comp);
        else exec("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
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

    static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            java.io.InputStream in = p.getInputStream();
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            p.waitFor();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
