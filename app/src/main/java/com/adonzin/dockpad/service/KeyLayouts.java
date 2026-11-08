package com.adonzin.dockpad.service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Android turns a controller's raw button codes into buttons (A, B, X, Y…) with a "key layout"
 * file, chosen per device. The built-in controls and DockPad's virtual controllers can end up
 * with different files, so the same raw code would mean X on one and Y on the other. This reads
 * which file Android uses for each and works out the raw codes the virtual controller must send
 * so every button means what it means on the built-in controls.
 */
final class KeyLayouts {
    private KeyLayouts() {
    }

    /** From `dumpsys input`: the key layout file of the device at {@code path}, or named {@code name}. */
    static String layoutFile(String dump, String path, String name) {
        String curName = null, curPath = null, found = null;
        boolean inHub = false;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Event Hub State")) inHub = true;
            else if (inHub && (t.startsWith("Input Reader State") || t.startsWith("Input Dispatcher State"))) break;
            if (!inHub) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^-?\\d+: (.+)$").matcher(t);
            if (m.matches()) {
                curName = m.group(1).trim();
                curPath = null;
                continue;
            }
            if (t.startsWith("Path:")) curPath = t.substring(5).trim();
            if (t.startsWith("KeyLayoutFile:")) {
                String f = t.substring(14).trim();
                if (path != null && path.equals(curPath)) return f;
                if (name != null && name.equals(curName)) found = f;
            }
        }
        return found;
    }

    /** raw code → Android key name (BUTTON_A…), from a .kl file. */
    static Map<Integer, String> parse(String file) {
        Map<Integer, String> out = new HashMap<>();
        if (file == null || file.isEmpty()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3 || !p[0].equals("key") || p[1].equals("usage")) continue;
                try {
                    int code = p[1].startsWith("0x") ? Integer.parseInt(p[1].substring(2), 16) : Integer.parseInt(p[1]);
                    out.put(code, p[2]);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * For each raw code the built-in controls use: the raw code that gives the same button on the
     * virtual controller (only where it differs). {@code allowed}: codes the virtual controller has.
     */
    static Map<Integer, Integer> remap(Map<Integer, String> builtIn, Map<Integer, String> pad,
                                       java.util.Set<Integer> codes, java.util.Set<Integer> allowed) {
        Map<Integer, Integer> out = new HashMap<>();
        if (builtIn.isEmpty() || pad.isEmpty()) return out;
        Map<String, Integer> byName = new HashMap<>();
        for (Map.Entry<Integer, String> e : pad.entrySet()) {
            if (!allowed.contains(e.getKey())) continue;
            Integer had = byName.get(e.getValue());
            if (had == null || e.getKey() < had) byName.put(e.getValue(), e.getKey());
        }
        for (int code : codes) {
            String want = builtIn.get(code);
            if (want == null || want.equals(pad.get(code))) continue;
            Integer other = byName.get(want);
            if (other != null && other != code) out.put(code, other);
        }
        return out;
    }
}
