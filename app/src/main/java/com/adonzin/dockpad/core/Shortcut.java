package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A user-defined shortcut: when a button (or a combination of buttons) is pressed or held
 * on a controller, perform an action. The action is one or more steps; each step presses
 * one or more outputs together (a single step = a remap, several steps = a macro).
 */
public final class Shortcut {
    public static final String ANY = "*";

    public String device = ANY;       // controller name, or ANY
    public int[] trigger = new int[0]; // raw evdev key codes, in the order they are pressed
    public boolean hold;              // false: when pressed, true: when held for holdMs
    public int holdMs = 500;          // how long to hold, for hold shortcuts
    public List<int[]> steps = new ArrayList<>(); // empty = do nothing (block the buttons)
    /** Extra data for some actions: for "Open an app", "package|App name". */
    public String param = "";

    public Shortcut copy() {
        Shortcut s = new Shortcut();
        s.device = device;
        s.trigger = trigger.clone();
        s.hold = hold;
        s.holdMs = holdMs;
        for (int[] st : steps) s.steps.add(st.clone());
        s.param = param;
        return s;
    }

    public boolean sameTrigger(Shortcut o) {
        int[] a = trigger.clone(), b = o.trigger.clone();
        Arrays.sort(a);
        Arrays.sort(b);
        return device.equals(o.device) && hold == o.hold && Arrays.equals(a, b);
    }

    public boolean appliesTo(String deviceName) {
        return ANY.equals(device) || device.equals(deviceName);
    }

    // ------------------------------------------------------------ labels

    public String triggerLabel() {
        StringBuilder b = new StringBuilder();
        for (int c : trigger) b.append(b.length() == 0 ? "" : " + ").append(Codes.shortKeyName(c));
        return b.toString();
    }

    public String deviceLabel() {
        return ANY.equals(device) ? "Any controller" : device;
    }

    public String actionLabel() {
        if (steps.isEmpty()) return "Do nothing (block)";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) b.append(", then ");
            b.append(stepLabel(steps.get(i)));
        }
        return b.toString();
    }

    public String stepLabel(int[] step) {
        StringBuilder b = new StringBuilder();
        for (int o : step) {
            b.append(b.length() == 0 ? "" : " + ");
            if (o == Codes.ACTION_OPEN_APP && param.contains("|")) b.append("Open ").append(param.substring(param.indexOf('|') + 1));
            else b.append(Codes.outputLabel(o));
        }
        return b.toString();
    }

    // ------------------------------------------------------------ storage

    /** device \t trigger-csv \t P|H \t steps ("-" = none; steps split by ';', outputs by ',') */
    public String encode() {
        StringBuilder st = new StringBuilder();
        for (int[] step : steps) {
            if (st.length() > 0) st.append(';');
            st.append(csv(step));
        }
        String p = param.replace("\t", " ").replace("\n", " ");
        String when = hold ? (holdMs == 500 ? "H" : "H" + holdMs) : "P";
        return device + "\t" + csv(trigger) + "\t" + when + "\t" + (st.length() == 0 ? "-" : st)
                + (p.isEmpty() ? "" : "\t" + p);
    }

    public static Shortcut decode(String line) {
        String[] f = line.split("\t");
        if (f.length != 4 && f.length != 5) return null;
        try {
            Shortcut s = new Shortcut();
            s.device = f[0];
            s.trigger = parseCsv(f[1]);
            if (s.trigger.length == 0) return null;
            s.hold = f[2].startsWith("H");
            if (s.hold && f[2].length() > 1) s.holdMs = Math.max(100, Integer.parseInt(f[2].substring(1)));
            if (f.length == 5) s.param = f[4];
            if (!"-".equals(f[3])) {
                for (String step : f[3].split(";")) {
                    int[] st = parseCsv(step);
                    if (st.length > 0) s.steps.add(st);
                }
            }
            return s;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<Shortcut> decodeAll(String text) {
        List<Shortcut> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Shortcut s = decode(line);
            if (s != null) out.add(s);
        }
        return out;
    }

    public static String encodeAll(List<Shortcut> list) {
        StringBuilder b = new StringBuilder();
        for (Shortcut s : list) b.append(s.encode()).append('\n');
        return b.toString();
    }

    /** Converts the old one-button "name \t code \t output" format. */
    public static List<Shortcut> fromLegacy(String text) {
        List<Shortcut> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 3) continue;
            try {
                Shortcut s = new Shortcut();
                s.device = f[0];
                s.trigger = new int[]{Integer.parseInt(f[1])};
                int o = Integer.parseInt(f[2]);
                if (o >= 0) s.steps.add(new int[]{o});
                out.add(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String csv(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int v : a) b.append(b.length() == 0 ? "" : ",").append(v);
        return b.toString();
    }

    private static int[] parseCsv(String s) {
        if (s.trim().isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
}
