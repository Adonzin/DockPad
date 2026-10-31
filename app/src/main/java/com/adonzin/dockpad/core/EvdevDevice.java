package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Linux input device as described by `getevent -i` (numeric, not -l).
 * Pure Java so it can be unit-tested off-device.
 */
public final class EvdevDevice {

    public static final class AbsInfo {
        public int min, max, fuzz, flat;

        public AbsInfo(int min, int max, int fuzz, int flat) {
            this.min = min;
            this.max = max;
            this.fuzz = fuzz;
            this.flat = flat;
        }
    }

    public String path = "";
    public String name = "";
    public int bus, vendor, product;
    public final Set<Integer> keys = new HashSet<>();
    public final Map<Integer, AbsInfo> abs = new HashMap<>();

    public boolean hasKey(int code) {
        return keys.contains(code);
    }

    public boolean hasAbs(int code) {
        return abs.containsKey(code);
    }

    /** Looks like a gamepad: a stick plus at least one face/start button. */
    public boolean isGamepad() {
        boolean stick = hasAbs(Codes.ABS_X) && hasAbs(Codes.ABS_Y);
        boolean buttons = hasKey(Codes.BTN_SOUTH) || hasKey(Codes.BTN_EAST) || hasKey(Codes.BTN_START);
        return stick && buttons;
    }

    /** A controller's built-in touchpad (e.g. DualShock 4 / DualSense), which Android treats as a mouse. */
    public boolean isControllerTouchpad() {
        return !isGamepad() && name.toLowerCase().contains("touchpad") && hasKey(Codes.BTN_LEFT);
    }

    public String id() {
        return String.format("%04x:%04x", vendor, product);
    }

    @Override
    public String toString() {
        return name + " [" + id() + "] " + path;
    }

    private static final Pattern ADD = Pattern.compile("^add device \\d+:\\s*(\\S+)");
    private static final Pattern NAME = Pattern.compile("^\\s*name:\\s*\"(.*)\"\\s*$");
    private static final Pattern IDFIELD = Pattern.compile("^\\s*(bus|vendor|product)\\s*:?\\s+([0-9a-fA-F]+)\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*([A-Z]+)\\s*\\(([0-9a-fA-F]{4})\\):(.*)$");
    private static final Pattern ABSLINE = Pattern.compile(
            "([0-9a-fA-F]{4})\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+),\\s*fuzz\\s+(-?\\d+),\\s*flat\\s+(-?\\d+)");
    private static final Pattern HEX4 = Pattern.compile("\\b([0-9a-fA-F]{4})\\*?(?=\\s|$)");

    /** Parses the full output of `getevent -i`. */
    public static List<EvdevDevice> parse(String output) {
        List<EvdevDevice> out = new ArrayList<>();
        EvdevDevice cur = null;
        int section = -1; // event type of current section, -1 = none
        for (String line : output.split("\n")) {
            line = line.replace("\r", "");
            Matcher m = ADD.matcher(line);
            if (m.find()) {
                cur = new EvdevDevice();
                cur.path = m.group(1);
                out.add(cur);
                section = -1;
                continue;
            }
            if (cur == null) continue;
            if ((m = NAME.matcher(line)).find()) {
                cur.name = m.group(1);
                continue;
            }
            if ((m = IDFIELD.matcher(line)).find()) {
                int v = Integer.parseInt(m.group(2), 16);
                switch (m.group(1)) {
                    case "bus": cur.bus = v; break;
                    case "vendor": cur.vendor = v; break;
                    case "product": cur.product = v; break;
                }
                continue;
            }
            String body;
            if ((m = SECTION.matcher(line)).find()) {
                section = Integer.parseInt(m.group(2), 16);
                body = m.group(3);
            } else if (line.trim().endsWith(":") || line.trim().isEmpty() || !Character.isWhitespace(line.charAt(0))) {
                // "events:", "input props:", etc. end any event section
                if (!line.trim().isEmpty()) section = -1;
                continue;
            } else {
                body = line;
            }
            if (section == Codes.EV_KEY) {
                Matcher h = HEX4.matcher(body);
                while (h.find()) cur.keys.add(Integer.parseInt(h.group(1), 16));
            } else if (section == Codes.EV_ABS) {
                Matcher a = ABSLINE.matcher(body);
                if (a.find()) {
                    cur.abs.put(Integer.parseInt(a.group(1), 16), new AbsInfo(
                            Integer.parseInt(a.group(3)), Integer.parseInt(a.group(4)),
                            Integer.parseInt(a.group(5)), Integer.parseInt(a.group(6))));
                }
            }
        }
        return out;
    }
}
