package com.adonzin.dockpad.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bluetooth from the shell process (which may connect/disconnect paired devices). */
@android.annotation.TargetApi(33)
@android.annotation.SuppressLint({"MissingPermission", "NewApi", "BlockedPrivateApi", "DiscouragedPrivateApi",
        "SoonBlockedPrivateApi", "PrivateApi"})
final class Bt {
    private static final String TAG = "DockPad";

    private Bt() {
    }

    private static Method method(Class<?> c, String name, Class<?>... args) throws Exception {
        try {
            Method m = c.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            Method m = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(c, name, args);
            m.setAccessible(true);
            return m;
        }
    }

    private static boolean hooked;

    /**
     * A normal app process is connected to the Bluetooth service at startup (ActivityThread). Ours isn't,
     * so every adapter comes back empty until we do the same.
     */
    private static synchronized void hookUp(StringBuilder diag) {
        if (hooked) return;
        try {
            Class<?> init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            Field f = null;
            try {
                f = init.getDeclaredField("sBluetoothServiceManager");
                f.setAccessible(true);
            } catch (Throwable ignored) {
            }
            if (f == null || f.get(null) == null) {
                Class<?> mgr = Class.forName("android.os.BluetoothServiceManager");
                java.lang.reflect.Constructor<?> c = mgr.getDeclaredConstructor();
                c.setAccessible(true);
                Object manager = c.newInstance();
                method(init, "setBluetoothServiceManager", mgr).invoke(null, manager);
            }
            hooked = true;
        } catch (Throwable t) {
            diag.append("hook-up: ").append(cause(t)).append("; ");
            try {
                method(Class.forName("android.app.ActivityThread"), "initializeMainlineModules").invoke(null);
                hooked = true;
            } catch (Throwable t2) {
                diag.append("mainline init: ").append(cause(t2)).append("; ");
            }
        }
    }

    /** Ways to get an adapter that speaks for the shell. */
    private static List<BluetoothAdapter> adapters(StringBuilder diag) {
        hookUp(diag);
        List<BluetoothAdapter> list = new ArrayList<>();
        try {
            AttributionSource src = new AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell").build();
            BluetoothAdapter a = (BluetoothAdapter) method(BluetoothAdapter.class, "createAdapter",
                    AttributionSource.class).invoke(null, src);
            if (a != null) list.add(a);
            else diag.append("shell adapter: none; ");
        } catch (Throwable t) {
            diag.append("shell adapter: ").append(cause(t)).append("; ");
        }
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) list.add(a);
            else diag.append("default adapter: none; ");
        } catch (Throwable t) {
            diag.append("default adapter: ").append(cause(t)).append("; ");
        }
        return list;
    }

    private static String cause(Throwable t) {
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 80 ? m.substring(0, 80) : m));
    }

    private static boolean hasService(BluetoothAdapter a) {
        try {
            Field f = BluetoothAdapter.class.getDeclaredField("mService");
            f.setAccessible(true);
            return f.get(a) != null;
        } catch (Throwable t) {
            return true; // can't tell
        }
    }

    private static Boolean isConnected(BluetoothDevice d) {
        try {
            return (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Paired devices (address -> name) as listed by dumpsys, in case the API won't list them. */
    private static Map<String, String> dumpsysBonded() {
        Map<String, String> m = new LinkedHashMap<>();
        String out = Actions.exec("dumpsys", "bluetooth_manager");
        Pattern p = Pattern.compile("([0-9A-F]{2}(?::[0-9A-F]{2}){5})\\s*\\[?[^\\]\\n]*\\]?\\s+(.+)");
        boolean inBonded = false;
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.startsWith("Bonded devices")) {
                inBonded = true;
                continue;
            }
            if (inBonded) {
                Matcher mm = p.matcher(l);
                if (mm.find()) m.put(mm.group(1), mm.group(2).trim());
                else if (l.isEmpty() || l.endsWith(":")) inBonded = false;
            }
        }
        return m;
    }

    /** Disconnects the paired Bluetooth controller with this name. Returns null on success, else why not. */
    static String disconnect(String name) {
        if (Build.VERSION.SDK_INT < 33) return "Needs Android 13 or newer";
        StringBuilder diag = new StringBuilder();
        String want = norm(name);
        for (BluetoothAdapter a : adapters(diag)) {
            try {
                diag.append("adapter[state ").append(a.getState()).append(hasService(a) ? "" : ", no service")
                        .append("] ");
                List<BluetoothDevice> devices = new ArrayList<>();
                try {
                    Set<BluetoothDevice> bonded = a.getBondedDevices();
                    if (bonded != null) devices.addAll(bonded);
                } catch (Throwable t) {
                    diag.append("paired list: ").append(cause(t)).append(' ');
                }
                if (devices.isEmpty()) {
                    for (Map.Entry<String, String> e : dumpsysBonded().entrySet()) {
                        try {
                            devices.add(a.getRemoteDevice(e.getKey()));
                        } catch (Throwable ignored) {
                        }
                    }
                    diag.append("from dumpsys ");
                }
                diag.append(devices.size()).append(" paired; ");
                BluetoothDevice pick = pick(devices, want, diag);
                if (pick == null) continue;
                Object r = method(BluetoothDevice.class, "disconnect").invoke(pick);
                Log.i(TAG, "Bluetooth disconnect " + name + ": " + r);
                if (r instanceof Integer && (Integer) r != 0) {
                    diag.append("refused (code ").append(r).append("); ");
                    continue;
                }
                return null;
            } catch (Throwable t) {
                diag.append(cause(t)).append("; ");
            }
        }
        Log.w(TAG, "Bluetooth disconnect failed: " + diag);
        return "Couldn't disconnect " + name + ".\n\nDetails: " + diag.toString().trim();
    }

    private static BluetoothDevice pick(List<BluetoothDevice> devices, String want, StringBuilder diag) {
        List<BluetoothDevice> connected = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (BluetoothDevice d : devices) {
            Boolean c = isConnected(d);
            String n;
            try {
                n = d.getName();
            } catch (Throwable t) {
                n = "?";
            }
            names.append(n).append(c == null ? "(?)" : c ? "(on)" : "(off)").append(", ");
            if (c == null || c) connected.add(d);
        }
        if (names.length() > 0) diag.append("devices: ").append(names).append(' ');
        for (BluetoothDevice d : connected) if (norm(safeName(d)).equals(want)) return d;
        for (BluetoothDevice d : connected) {
            String n = norm(safeName(d));
            if (!n.isEmpty() && !want.isEmpty() && (n.contains(want) || want.contains(n))) return d;
        }
        // the system may rename controllers: the only connected controller-type device
        List<BluetoothDevice> pads = new ArrayList<>();
        for (BluetoothDevice d : connected) {
            try {
                BluetoothClass c = d.getBluetoothClass();
                if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL
                        && Boolean.TRUE.equals(isConnected(d))) pads.add(d);
            } catch (Throwable ignored) {
            }
        }
        if (pads.size() == 1) return pads.get(0);
        diag.append("no match; ");
        return null;
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (Throwable t) {
            return "";
        }
    }
}
