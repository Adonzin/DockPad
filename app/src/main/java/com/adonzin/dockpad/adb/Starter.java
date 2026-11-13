package com.adonzin.dockpad.adb;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.adonzin.dockpad.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Starts DockPad's background service through the built-in ADB connection (no Shizuku).
 * Call from a background thread.
 */
public final class Starter {
    private static final String TAG = "DockPad";
    private Starter() {}

    /** Why starting failed, so each failure can offer the one fix that fits. */
    public enum Problem {
        NONE,
        /** Developer options are off. */
        DEVELOPER_OPTIONS,
        /** No Wi-Fi connection (Ethernet alone can't run Wireless debugging). */
        NO_WIFI,
        /** Wireless debugging is off and DockPad isn't allowed to turn it on yet (before the first pairing). */
        WIRELESS_DEBUGGING_OFF,
        /** DockPad turned Wireless debugging on, but Android switched it back off (usually "Allow on this network?"). */
        NETWORK_NOT_ALLOWED,
        /** Wireless debugging is on, but its connection couldn't be found or opened. */
        CONNECT,
        /** Android doesn't accept DockPad's pairing any more. */
        PAIRING,
        /** Connected, but the background service didn't come up. */
        SERVICE,
        OTHER
    }

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final boolean needsPairing;
        public final Problem problem;

        Result(boolean ok, String message, boolean needsPairing) {
            this(ok, message, needsPairing ? Problem.PAIRING : ok ? Problem.NONE : Problem.OTHER);
        }

        Result(boolean ok, String message, Problem problem) {
            this.ok = ok;
            this.message = message;
            this.problem = problem;
            this.needsPairing = problem == Problem.PAIRING;
        }

        static Result fail(Problem p, String message) {
            return new Result(false, message, p);
        }
    }

    /** Live progress while starting, for the connection screen. Called on the starting thread. */
    public interface Progress {
        void step(String text);
    }

    // ---- networks

    public static boolean wifiConnected(Context c) {
        return hasTransport(c, android.net.NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static boolean ethernetConnected(Context c) {
        return hasTransport(c, android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    public static boolean wifiSwitchedOn(Context c) {
        try {
            android.net.wifi.WifiManager w = c.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            return w != null && w.isWifiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean hasTransport(Context c, int transport) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(transport)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean developerOptionsOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    /** What to tell someone without Wi-Fi, worded for Ethernet when that's what they're on. */
    public static String noWifiMessage(Context c) {
        if (ethernetConnected(c)) {
            return "You're on Ethernet. Android only runs Wireless debugging over Wi-Fi, so DockPad needs Wi-Fi "
                    + "joined to any network for a few seconds while it starts (a phone hotspot works, no internet "
                    + "needed). Once DockPad is on, Ethernet alone is fine.";
        }
        if (!wifiSwitchedOn(c)) return "Wi-Fi is switched off. Turn it on and join a network: DockPad starts through "
                + "Wireless debugging, which only works over Wi-Fi.";
        return "Not connected to Wi-Fi. Join a network (no internet needed): DockPad starts through Wireless "
                + "debugging, which only works over Wi-Fi.";
    }

    static String token(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("dockpad", Context.MODE_PRIVATE);
        String t = p.getString("serviceToken", null);
        if (t == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            p.edit().putString("serviceToken", t).apply();
        }
        return t;
    }

    public static SocketBridge bridge(Context c) {
        return new SocketBridge(token(c));
    }

    /** True if our service of the current version is already running. */
    public static boolean isRunning(Context c) {
        return bridge(c).version() == BuildConfig.VERSION_CODE;
    }

    public static boolean canControlWirelessDebugging(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static boolean wirelessDebuggingOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    public static void setWirelessDebugging(Context c, boolean on) {
        try {
            if (on) Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
        } catch (SecurityException e) {
            Log.w(TAG, "can't change wireless debugging", e);
        }
    }

    /** Connects over wireless debugging and launches the service. */
    public static Result start(Context c, boolean turnOffWirelessDebuggingAfter) {
        return start(c, turnOffWirelessDebuggingAfter, null);
    }

    public static Result start(Context c, boolean turnOffWirelessDebuggingAfter, Progress progress) {
        StringBuilder log = new StringBuilder();
        long t0 = System.currentTimeMillis();
        Progress logged = t -> {
            log.append("  +").append(System.currentTimeMillis() - t0).append(" ms  ").append(t).append('\n');
            if (progress != null) progress.step(t);
        };
        Result r;
        try {
            r = startLogged(c, turnOffWirelessDebuggingAfter, logged);
        } catch (RuntimeException e) {
            r = Result.fail(Problem.OTHER, "Couldn't start: " + e);
        }
        remember(c, r, log.toString(), t0);
        return r;
    }

    /** The last start attempt, for Diagnostics: when, what happened and each step it got to. */
    private static void remember(Context c, Result r, String steps, long t0) {
        if (r.ok && "already running".equals(r.message)) return;
        StringBuilder sb = new StringBuilder();
        sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(t0)))
                .append(" (").append(System.currentTimeMillis() - t0).append(" ms): ")
                .append(r.ok ? "started" : r.problem + " - " + r.message).append('\n');
        sb.append("  Wi-Fi ").append(wifiConnected(c) ? "connected" : wifiSwitchedOn(c) ? "on, not connected" : "off")
                .append(", Ethernet ").append(ethernetConnected(c) ? "connected" : "no")
                .append(", Developer options ").append(developerOptionsOn(c) ? "on" : "off")
                .append(", Wireless debugging ").append(wirelessDebuggingOn(c) ? "on" : "off")
                .append(", may switch it ").append(canControlWirelessDebugging(c) ? "yes" : "no").append('\n');
        sb.append(steps);
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit().putString("lastStart", sb.toString()).apply();
    }

    public static String lastStart(Context c) {
        return c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).getString("lastStart", "");
    }

    private static Result startLogged(Context c, boolean turnOffWirelessDebuggingAfter, Progress say) {
        SocketBridge b = bridge(c);
        int running = b.version();
        if (running == BuildConfig.VERSION_CODE) return new Result(true, "already running", false);
        if (running >= 0) { // an older version: replace it
            say.step("Replacing an older DockPad service…");
            b.exit();
            sleep(500);
        }

        boolean weTurnedOn = false;
        if (!wirelessDebuggingOn(c)) {
            if (!developerOptionsOn(c) && !canControlWirelessDebugging(c)) {
                return Result.fail(Problem.DEVELOPER_OPTIONS, "Developer options are off. In Settings → About, "
                        + "tap \"Build number\" 7 times, then try again.");
            }
            if (!wifiConnected(c)) return Result.fail(Problem.NO_WIFI, noWifiMessage(c));
            if (!canControlWirelessDebugging(c)) {
                return Result.fail(Problem.WIRELESS_DEBUGGING_OFF, "Wireless debugging is off. Turn it on in "
                        + "Developer options, then try again. (After pairing once, DockPad turns it on by itself.)");
            }
            say.step("Turning on Wireless debugging…");
            setWirelessDebugging(c, true);
            weTurnedOn = true;
            // Android switches it straight back off when it won't run on this network
            // (no Wi-Fi, or it's asking "Allow wireless debugging on this network?").
            boolean stayed = false;
            for (int i = 0; i < 10; i++) {
                sleep(300);
                stayed = wirelessDebuggingOn(c);
                if (!stayed) break;
            }
            if (!stayed) {
                if (!wifiConnected(c)) return Result.fail(Problem.NO_WIFI, noWifiMessage(c));
                return Result.fail(Problem.NETWORK_NOT_ALLOWED, "Android didn't let Wireless debugging run on this "
                        + "Wi-Fi network. If it asked \"Allow wireless debugging on this network?\", tick \"Always "
                        + "allow on this network\" and tap Allow, then try again.");
            }
        }
        say.step("Looking for Wireless debugging's connection…");

        AdbManager m;
        try {
            m = AdbManager.get(c);
            boolean connected = false;
            int pairingErrors = 0;
            // A connection left open from before may point at Wireless debugging's old port (it
            // changes whenever Wireless debugging restarts): always start from a fresh one.
            if (m.isConnected()) {
                say.step("Closing an old connection");
                try {
                    m.disconnect();
                } catch (Exception ignored) {
                }
            }
            for (int attempt = 0; attempt < 4 && !connected; attempt++) {
                if (attempt > 0) say.step("Connecting to Wireless debugging (try " + (attempt + 1) + " of 4)…");
                try {
                    connected = m.autoConnect(c, 8000);
                    if (connected) {
                        String probe = run(m, "echo dockpad-ok").trim();
                        if (!probe.contains("dockpad-ok")) {
                            say.step("Connected, but the shell didn't answer (" + shorten(probe) + "): reconnecting");
                            connected = false;
                            try {
                                m.disconnect();
                            } catch (Exception ignored) {
                            }
                            sleep(1000);
                        }
                    }
                } catch (AdbPairingRequiredException e) {
                    // Can also happen briefly while Wireless debugging is still starting: retry first.
                    pairingErrors++;
                    say.step("Pairing not accepted (try " + (attempt + 1) + ")");
                    Log.w(TAG, "connect: pairing required (attempt " + attempt + ")", e);
                    sleep(2000);
                } catch (Exception e) {
                    say.step("Try " + (attempt + 1) + " failed: " + e);
                    Log.w(TAG, "connect attempt failed", e);
                    sleep(1500);
                }
            }
            if (!connected) {
                if (weTurnedOn) setWirelessDebugging(c, false);
                if (pairingErrors >= 3) {
                    return Result.fail(Problem.PAIRING, "Android no longer accepts DockPad's pairing (this happens "
                            + "when debugging authorizations are revoked or expire, or after a reset). Pair again: "
                            + "it takes a minute.");
                }
                if (!wifiConnected(c)) return Result.fail(Problem.NO_WIFI, noWifiMessage(c));
                if (!wirelessDebuggingOn(c)) {
                    return Result.fail(Problem.NETWORK_NOT_ALLOWED, "Wireless debugging switched itself off. If "
                            + "Android asked \"Allow wireless debugging on this network?\", tick \"Always allow on this "
                            + "network\" and tap Allow, then try again.");
                }
                return Result.fail(Problem.CONNECT, "Wireless debugging is on, but DockPad couldn't find or open "
                        + "its connection. This is usually brief: try again. If it keeps happening, turn Wireless "
                        + "debugging off and on, or turn Wi-Fi off and on.");
            }
            say.step("Connected. Starting DockPad's service…");
            String pkg = c.getPackageName();
            // Keep Android's debugging service running even when Wi-Fi (and so Wireless debugging)
            // drops during sleep: if it stops, it takes DockPad's service down with it.
            note(say, "adb_enabled", run(m, "settings put global adb_enabled 1"));
            // Lets DockPad switch wireless debugging on by itself after a restart.
            note(say, "grant", run(m, "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS"));
            String apk = c.getApplicationInfo().sourceDir;
            say.step("App file: " + shorten(run(m, "ls -l " + apk).trim()));
            // A DockPad service that didn't answer (stuck, or left from another install) would keep
            // the new one from opening its port: stop any that are left first.
            String left = run(m, "pgrep -f " + SERVICE_MATCH).trim();
            if (!left.isEmpty()) {
                say.step("Stopping a DockPad service that didn't answer (pid " + left.replace('\n', ' ') + ")");
                run(m, "pkill -9 -f " + SERVICE_MATCH);
                sleep(500);
            }
            String cmd = "CLASSPATH=" + apk + " nohup setsid app_process /system/bin "
                    + SERVICE_CLASS + " " + token(c) + " " + BuildConfig.VERSION_CODE
                    + " >" + SERVICE_LOG + " 2>&1 &";
            note(say, "launch", run(m, cmd));
            for (int i = 0; i < 75; i++) { // wait up to 15 s for the service
                if (b.version() == BuildConfig.VERSION_CODE) {
                    say.step("Service is up");
                    try {
                        m.disconnect();
                    } catch (Exception ignored) {
                    }
                    if (turnOffWirelessDebuggingAfter && canControlWirelessDebugging(c)) setWirelessDebugging(c, false);
                    return new Result(true, "started", false);
                }
                sleep(200);
            }
            // Didn't come up: note why, for Diagnostics.
            String procs = run(m, "pgrep -fl " + SERVICE_MATCH).trim();
            say.step("Service processes: " + (procs.isEmpty() ? "none (it exited)" : procs.replace('\n', ' ')));
            String out = run(m, "tail -n 25 " + SERVICE_LOG).trim();
            say.step("Service output: " + (out.isEmpty() ? "(none)" : "\n" + out));
            say.step("Answers on its port: " + b.version());
            try {
                m.disconnect();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            say.step("Failed: " + e);
            Log.e(TAG, "start failed", e);
            return Result.fail(Problem.OTHER, "Couldn't start: " + e.getMessage());
        }
        if (weTurnedOn) setWirelessDebugging(c, false);
        return Result.fail(Problem.SERVICE, "DockPad connected, but its background service didn't start. Try "
                + "again; if it keeps failing, send Diagnostics (Settings) to the developer.");
    }

    private static final String SERVICE_CLASS = "com.adonzin.dockpad.service.ShellMain";
    // "[S]" so the pattern doesn't match the shell running pgrep/pkill itself
    private static final String SERVICE_MATCH = "'com.adonzin.dockpad.service.[S]hellMain'";
    private static final String SERVICE_LOG = "/data/local/tmp/dockpad_service.log";

    static String run(AdbManager m, String command) throws Exception {
        AdbStream s = m.openStream("shell:" + command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = s.openInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            out.write(("[read error: " + e + "]").getBytes("UTF-8"));
        }
        try {
            s.close();
        } catch (Exception ignored) {
        }
        return out.toString("UTF-8");
    }

    private static void note(Progress say, String what, String output) {
        String o = output == null ? "" : output.trim();
        if (!o.isEmpty()) say.step(what + ": " + shorten(o));
    }

    private static String shorten(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    public static void sleepQuietly(long ms) {
        sleep(ms);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
