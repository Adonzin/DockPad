package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.adonzin.dockpad.core.Codes.*;

/**
 * Per-controller button fixes: swap A/B, swap X/Y, and individual button changes.
 * Applied to the controller's raw buttons before anything else.
 */
public final class ControllerLayout {
    public String device = "";
    public boolean swapAB, swapXY;
    /** raw button -> button it should act as (custom changes, applied after the swaps) */
    public final LinkedHashMap<Integer, Integer> custom = new LinkedHashMap<>();

    public boolean isEmpty() {
        return !swapAB && !swapXY && custom.isEmpty();
    }

    /** The final raw -> new button table. */
    public Map<Integer, Integer> table() {
        Map<Integer, Integer> m = new HashMap<>();
        if (swapAB) {
            m.put(BTN_SOUTH, BTN_EAST);
            m.put(BTN_EAST, BTN_SOUTH);
        }
        if (swapXY) {
            m.put(BTN_NORTH, BTN_WEST);
            m.put(BTN_WEST, BTN_NORTH);
        }
        m.putAll(custom);
        return m;
    }

    public String summary() {
        List<String> parts = new ArrayList<>();
        if (swapAB) parts.add("A/B swapped");
        if (swapXY) parts.add("X/Y swapped");
        if (!custom.isEmpty()) parts.add(custom.size() == 1 ? "1 button changed" : custom.size() + " buttons changed");
        return parts.isEmpty() ? "Standard layout" : String.join(", ", parts);
    }

    /** device \t flags \t from:to,from:to */
    public String encode() {
        StringBuilder c = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : custom.entrySet()) {
            if (c.length() > 0) c.append(',');
            c.append(e.getKey()).append(':').append(e.getValue());
        }
        return device + "\t" + (swapAB ? "A" : "") + (swapXY ? "X" : "") + "-\t" + (c.length() == 0 ? "-" : c);
    }

    public static ControllerLayout decode(String line) {
        String[] f = line.split("\t");
        if (f.length != 3) return null;
        ControllerLayout l = new ControllerLayout();
        l.device = f[0];
        l.swapAB = f[1].contains("A");
        l.swapXY = f[1].contains("X");
        if (!"-".equals(f[2])) {
            for (String pair : f[2].split(",")) {
                String[] kv = pair.split(":");
                try {
                    l.custom.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
                } catch (Exception ignored) {
                }
            }
        }
        return l;
    }

    public static Map<String, ControllerLayout> decodeAll(String text) {
        Map<String, ControllerLayout> m = new LinkedHashMap<>();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            ControllerLayout l = decode(line);
            if (l != null) m.put(l.device, l);
        }
        return m;
    }

    public static String encodeAll(Map<String, ControllerLayout> all) {
        StringBuilder b = new StringBuilder();
        for (ControllerLayout l : all.values()) if (!l.isEmpty()) b.append(l.encode()).append('\n');
        return b.toString();
    }

    /** Buttons a button can be changed into. */
    public static final String[] TARGET_LABELS = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start",
            "Guide", "L3", "R3", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Disabled"};
    public static final int[] TARGET_CODES = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2,
            BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR, BTN_DPAD_UP, BTN_DPAD_DOWN,
            BTN_DPAD_LEFT, BTN_DPAD_RIGHT, -1};

    public static String targetLabel(int code) {
        for (int i = 0; i < TARGET_CODES.length; i++) if (TARGET_CODES[i] == code) return TARGET_LABELS[i];
        return Codes.shortKeyName(code);
    }
}
