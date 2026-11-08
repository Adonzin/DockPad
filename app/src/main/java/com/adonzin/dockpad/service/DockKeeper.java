package com.adonzin.dockpad.service;

import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Stay ready while docked" (off unless the user turns it on): while the handheld is on a
 * charger, Android's deep sleep (Doze) is switched off and the processor is kept from suspending,
 * so DockPad, Bluetooth and Wi-Fi stay responsive through long sleeps. Off the charger everything
 * goes back to normal at once. Keeps a short log of the night (screen, power, Wi-Fi, controllers)
 * for Diagnostics, in a file, so it survives the service being restarted.
 */
final class DockKeeper {
    private static final String TAG = "DockPad";
    private static final File LOG = new File("/data/local/tmp/dockpad_dock.log");
    /** Exists while DockPad has Doze switched off, so it's switched back on even after a crash. */
    private static final File DOZE_OFF = new File("/data/local/tmp/dockpad_doze_off");

    private volatile boolean enabled;
    private boolean active, lastPlugged, lastScreen = true, lastWifi = true, first = true;
    private final IBinder wakeToken = new Binder();
    private boolean wakeHeld;
    private String state = "off";
    private final Runnable onWake;

    DockKeeper(Runnable onWake) {
        this.onWake = onWake;
        // a previous run left Doze off (crash, update): put it back until we know we're docked
        if (DOZE_OFF.exists()) {
            sh("cmd deviceidle enable");
            //noinspection ResultOfMethodCallIgnored
            DOZE_OFF.delete();
            note("Deep sleep switched back on (left off by an earlier run)");
        }
        note("DockPad's service started");
    }

    synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        note(on ? "\"Stay ready while docked\" turned on" : "\"Stay ready while docked\" turned off");
        tick();
    }

    /** Every few seconds: notices docking, undocking, the screen and Wi-Fi. */
    synchronized void tick() {
        boolean plugged = plugged(), screen = screenOn(), wifi = wifiUp();
        if (first || plugged != lastPlugged) note(plugged ? "On the charger" : "Off the charger");
        if (!first && screen != lastScreen) {
            note(screen ? "Screen on" : "Screen off");
            if (screen && onWake != null) onWake.run(); // check the controllers straight away
        }
        // a screen chosen to be off: put back after waking; handheld screen back on when the TV goes
        Displays.check(!first && screen && !lastScreen);
        if (!first && wifi != lastWifi) note(wifi ? "Wi-Fi back" : "Wi-Fi dropped");
        first = false;
        lastPlugged = plugged;
        lastScreen = screen;
        lastWifi = wifi;
        boolean want = enabled && plugged;
        if (want && !active) activate();
        else if (!want && active) deactivate();
    }

    synchronized void stop() {
        if (active) deactivate();
    }

    private void activate() {
        active = true;
        String doze = sh("cmd deviceidle disable");
        boolean dozeOk = !doze.toLowerCase(Locale.ROOT).contains("exception") && !doze.toLowerCase(Locale.ROOT).contains("denied");
        if (dozeOk) touch(DOZE_OFF);
        wakeHeld = wakeLock(true);
        state = "docked: deep sleep " + (dozeOk ? "off" : "NOT switched off (" + doze.trim() + ")")
                + ", processor " + (wakeHeld ? "kept awake" : "NOT kept awake");
        note("Staying ready: " + state);
    }

    private void deactivate() {
        active = false;
        sh("cmd deviceidle enable");
        //noinspection ResultOfMethodCallIgnored
        DOZE_OFF.delete();
        if (wakeHeld) wakeLock(false);
        wakeHeld = false;
        state = "normal sleep";
        note("Back to normal sleep");
    }

    /** For Diagnostics. */
    synchronized String describe() {
        StringBuilder b = new StringBuilder("Stay ready while docked: ")
                .append(enabled ? "on" : "off").append(enabled ? " (" + (active ? state : "not on a charger") + ")" : "")
                .append('\n');
        List<String> lines = readLog();
        if (!lines.isEmpty()) {
            b.append("Dock log (newest last):\n");
            for (int i = Math.max(0, lines.size() - 40); i < lines.size(); i++) b.append("   ").append(lines.get(i)).append('\n');
        }
        return b.toString();
    }

    /** Controllers coming and going, from the bridge. */
    void controller(String name, boolean connected) {
        note((connected ? "Controller connected: " : "Controller gone: ") + name);
    }

    // ------------------------------------------------------------------ the log

    private synchronized void note(String s) {
        String line = new SimpleDateFormat("EEE HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        Log.i(TAG, "dock: " + s);
        List<String> lines = readLog();
        lines.add(line);
        while (lines.size() > 200) lines.remove(0);
        try (FileWriter w = new FileWriter(LOG)) {
            for (String l : lines) w.write(l + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static List<String> readLog() {
        List<String> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(LOG))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty()) out.add(l);
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ the handheld's state

    /** On any charger (or dock): any power source that isn't the battery itself is connected. */
    private static boolean plugged() {
        File[] all = new File("/sys/class/power_supply").listFiles();
        if (all != null) for (File d : all) {
            String n = d.getName().toLowerCase(Locale.ROOT);
            if (n.contains("battery") || n.equals("bms") || n.startsWith("sony_controller")) continue;
            if ("1".equals(read(new File(d, "online")))) return true;
        }
        String status = read(new File("/sys/class/power_supply/battery/status"));
        return "Charging".equalsIgnoreCase(status) || "Full".equalsIgnoreCase(status);
    }

    private static boolean wifiUp() {
        String s = read(new File("/sys/class/net/wlan0/operstate"));
        return s.isEmpty() || "up".equalsIgnoreCase(s);
    }

    private boolean screenOn() {
        try {
            Object pm = power();
            return pm == null || (boolean) pm.getClass().getMethod("isInteractive").invoke(pm);
        } catch (Throwable t) {
            return true;
        }
    }

    private Object powerService;

    private Object power() throws Exception {
        if (powerService != null) return powerService;
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "power");
        if (b == null) return null;
        powerService = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, b);
        return powerService;
    }

    /** A partial wake lock (processor on, screen free to turn off), held as the shell. */
    private boolean wakeLock(boolean on) {
        try {
            Object pm = power();
            if (pm == null) return false;
            if (!on) {
                for (Method m : pm.getClass().getMethods()) {
                    if (!m.getName().equals("releaseWakeLock")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    Object[] args = new Object[p.length];
                    args[0] = wakeToken;
                    for (int i = 1; i < p.length; i++) args[i] = p[i] == int.class ? 0 : null;
                    m.invoke(pm, args);
                    return true;
                }
                return false;
            }
            for (Method m : pm.getClass().getMethods()) {
                if (!m.getName().equals("acquireWakeLock")) continue;
                Class<?>[] p = m.getParameterTypes();
                Object[] args = new Object[p.length];
                int strings = 0;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == IBinder.class) args[i] = wakeToken;
                    else if (p[i] == int.class) args[i] = i == 1 ? 1 /* PARTIAL_WAKE_LOCK */ : -1 /* any display */;
                    else if (p[i] == String.class) {
                        args[i] = strings == 0 ? "DockPad:docked" : strings == 1 ? "com.android.shell" : null;
                        strings++;
                    } else args[i] = null;
                }
                m.invoke(pm, args);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "dock wake lock: " + t);
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static String read(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void touch(File f) {
        try (FileWriter w = new FileWriter(f)) {
            w.write("1");
        } catch (Throwable ignored) {
        }
    }

    private static String sh(String cmd) {
        return Actions.exec("sh", "-c", cmd + " 2>&1");
    }
}
