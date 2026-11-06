package com.adonzin.dockpad.service;

import android.hardware.BatteryState;
import android.os.Build;
import android.view.InputDevice;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller battery levels, from whichever source answers:
 * the input device's own battery (Android 12+), the kernel's power supply (PlayStation / Nintendo
 * drivers), or Bluetooth's battery report.
 */
final class Battery {
    /** percent (0-100) and charging state; percent -1 = unknown */
    static final class Level {
        final int percent;
        final boolean charging;

        Level(int percent, boolean charging) {
            this.percent = percent;
            this.charging = charging;
        }
    }

    private Battery() {
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Reads levels for the given controller names. */
    @android.annotation.SuppressLint("NewApi")
    static Map<String, Level> read(List<String> names) {
        Map<String, Level> out = new HashMap<>();
        // 1. Android's input devices (any copy with the same name, e.g. the original behind the system's copy)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice d = InputDevice.getDevice(id);
                    if (d == null) continue;
                    String match = matchName(names, d.getName());
                    if (match == null || out.containsKey(match)) continue;
                    BatteryState b = d.getBatteryState();
                    if (b == null || !b.isPresent()) continue;
                    float cap = b.getCapacity();
                    if (Float.isNaN(cap) || cap < 0) continue;
                    out.put(match, new Level(Math.round(cap * 100),
                            b.getStatus() == BatteryState.STATUS_CHARGING));
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.size() == names.size()) return out;

        // 2 + 3. Bluetooth devices: kernel power supply by address, or Bluetooth's own report
        Map<String, Level> sysfs = powerSupplies();
        for (String[] bt : Bt.connectedDevices()) { // name, address, bluetooth battery level
            String match = matchName(names, bt[0]);
            if (match == null || out.containsKey(match)) continue;
            Level l = sysfs.get(bt[1].toLowerCase());
            if (l == null) {
                int lvl = Integer.parseInt(bt[2]);
                if (lvl >= 0 && lvl <= 100) l = new Level(lvl, false);
            }
            if (l != null) out.put(match, l);
        }
        // the system may rename controllers: one unknown controller + one unclaimed battery
        if (names.size() - out.size() == 1) {
            Level spare = null;
            int spares = 0;
            for (String[] bt : Bt.connectedDevices()) {
                if (matchName(names, bt[0]) != null || !"1".equals(bt[3])) continue; // only controller-type devices
                Level l = sysfs.get(bt[1].toLowerCase());
                if (l == null) {
                    int lvl = Integer.parseInt(bt[2]);
                    if (lvl >= 0 && lvl <= 100) l = new Level(lvl, false);
                }
                if (l != null) {
                    spare = l;
                    spares++;
                }
            }
            if (spares == 1) for (String n : names) if (!out.containsKey(n)) out.put(n, spare);
        }
        return out;
    }

    private static String matchName(List<String> names, String candidate) {
        String c = norm(candidate);
        if (c.isEmpty()) return null;
        for (String n : names) if (norm(n).equals(c)) return n;
        for (String n : names) {
            String w = norm(n);
            if (!w.isEmpty() && (w.contains(c) || c.contains(w))) return n;
        }
        return null;
    }

    /** /sys/class/power_supply entries that belong to a device (not the handheld), keyed by MAC address. */
    private static Map<String, Level> powerSupplies() {
        Map<String, Level> m = new HashMap<>();
        File[] all = new File("/sys/class/power_supply").listFiles();
        if (all == null) return m;
        java.util.regex.Pattern mac = java.util.regex.Pattern.compile("([0-9a-f]{2}([:_-]?)[0-9a-f]{2}(\\2[0-9a-f]{2}){4})");
        for (File f : all) {
            String name = f.getName().toLowerCase();
            String scope = read(new File(f, "scope"));
            if (!"device".equalsIgnoreCase(scope) && !name.contains("controller")) continue;
            java.util.regex.Matcher mm = mac.matcher(name);
            if (!mm.find()) continue;
            String hex = mm.group(1).replaceAll("[^0-9a-f]", "");
            StringBuilder addr = new StringBuilder();
            for (int i = 0; i < 12; i += 2) addr.append(i == 0 ? "" : ":").append(hex, i, i + 2);
            int cap = -1;
            try {
                cap = Integer.parseInt(read(new File(f, "capacity")));
            } catch (NumberFormatException ignored) {
            }
            if (cap < 0) {
                String lvl = read(new File(f, "capacity_level")).toLowerCase(); // some drivers only say "Full"/"Low"
                cap = lvl.equals("full") ? 100 : lvl.equals("high") ? 80 : lvl.equals("normal") ? 50
                        : lvl.equals("low") ? 20 : lvl.equals("critical") ? 5 : -1;
            }
            if (cap < 0) continue;
            String status = read(new File(f, "status"));
            m.put(addr.toString(), new Level(Math.min(100, cap), status.equalsIgnoreCase("charging")));
        }
        return m;
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath())).trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
