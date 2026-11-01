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

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final boolean needsPairing;

        Result(boolean ok, String message, boolean needsPairing) {
            this.ok = ok;
            this.message = message;
            this.needsPairing = needsPairing;
        }
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
        SocketBridge b = bridge(c);
        int running = b.version();
        if (running == BuildConfig.VERSION_CODE) return new Result(true, "already running", false);
        if (running >= 0) { // an older version: replace it
            b.exit();
            sleep(500);
        }

        boolean weTurnedOn = false;
        if (!wirelessDebuggingOn(c)) {
            if (!canControlWirelessDebugging(c)) {
                return new Result(false, "Turn on Wireless debugging in Developer options (connect to Wi-Fi first), then try again.", false);
            }
            setWirelessDebugging(c, true);
            weTurnedOn = true;
            sleep(1500);
        }

        AdbManager m;
        try {
            m = AdbManager.get(c);
            boolean connected = false;
            for (int attempt = 0; attempt < 3 && !connected; attempt++) {
                try {
                    connected = m.isConnected() || m.autoConnect(c, 8000);
                } catch (AdbPairingRequiredException e) {
                    return new Result(false, "DockPad needs to be paired with Wireless debugging first.", true);
                } catch (Exception e) {
                    Log.w(TAG, "connect attempt failed", e);
                    sleep(1500);
                }
            }
            if (!connected) {
                return new Result(false, "Couldn't connect to Wireless debugging. Make sure Wi-Fi is connected "
                        + "and Wireless debugging is on, then try again.", false);
            }
            String pkg = c.getPackageName();
            // Lets DockPad switch wireless debugging on by itself after a restart.
            run(m, "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            String apk = c.getApplicationInfo().sourceDir;
            String cmd = "CLASSPATH=" + apk + " nohup setsid app_process /system/bin "
                    + "com.adonzin.dockpad.service.ShellMain " + token(c) + " " + BuildConfig.VERSION_CODE
                    + " >/dev/null 2>&1 &";
            run(m, cmd);
            try {
                m.disconnect();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            return new Result(false, "Couldn't start: " + e.getMessage(), false);
        }

        for (int i = 0; i < 40; i++) { // wait up to 8 s for the service
            if (b.version() == BuildConfig.VERSION_CODE) {
                if (turnOffWirelessDebuggingAfter && canControlWirelessDebugging(c)) setWirelessDebugging(c, false);
                return new Result(true, "started", false);
            }
            sleep(200);
        }
        if (weTurnedOn) setWirelessDebugging(c, false);
        return new Result(false, "The service didn't start. Open Diagnostics and send them to the developer.", false);
    }

    static String run(AdbManager m, String command) throws Exception {
        AdbStream s = m.openStream("shell:" + command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = s.openInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
        try {
            s.close();
        } catch (Exception ignored) {
        }
        return out.toString("UTF-8");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
