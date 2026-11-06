package com.adonzin.dockpad.core;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.adonzin.dockpad.core.Codes.*;

/**
 * Translates raw evdev events from ANY gamepad into a normalized gamepad state:
 * Android button key codes plus 8 logical axes (two sticks, two triggers, d-pad hat).
 *
 * Works out the source's layout from its capabilities, so controllers that follow
 * the Linux standard (Xbox, PlayStation, 8BitDo, Switch Pro...) and Retroid-normalized
 * controllers all work without per-model tables. Fills in whatever the source lacks
 * (analog triggers from digital ones and vice-versa, hat from d-pad buttons and vice-versa).
 */
public final class Mapper {

    public interface Sink {
        void key(int androidKeyCode, boolean down);
        /** A key with no standard gamepad meaning (volume, home, vendor keys): passed through as-is. */
        void rawKey(int evdevCode, boolean down);
        /** Full logical axis state, indexed by LX..HY. */
        void axes(float[] state);
    }

    public static final int LX = 0, LY = 1, RX = 2, RY = 3, LT = 4, RT = 5, HX = 6, HY = 7, AXES = 8;
    public static final String[] AXIS_NAMES = {"LX", "LY", "RX", "RY", "LT", "RT", "HatX", "HatY"};

    private static final Map<Integer, Integer> STANDARD_KEYS = new HashMap<>();
    static {
        STANDARD_KEYS.put(BTN_SOUTH, KEYCODE_BUTTON_A);
        STANDARD_KEYS.put(BTN_EAST, KEYCODE_BUTTON_B);
        STANDARD_KEYS.put(BTN_C, KEYCODE_BUTTON_C);
        STANDARD_KEYS.put(BTN_NORTH, KEYCODE_BUTTON_X);
        STANDARD_KEYS.put(BTN_WEST, KEYCODE_BUTTON_Y);
        STANDARD_KEYS.put(BTN_Z, KEYCODE_BUTTON_Z);
        STANDARD_KEYS.put(BTN_TL, KEYCODE_BUTTON_L1);
        STANDARD_KEYS.put(BTN_TR, KEYCODE_BUTTON_R1);
        STANDARD_KEYS.put(BTN_TL2, KEYCODE_BUTTON_L2);
        STANDARD_KEYS.put(BTN_TR2, KEYCODE_BUTTON_R2);
        STANDARD_KEYS.put(BTN_SELECT, KEYCODE_BUTTON_SELECT);
        STANDARD_KEYS.put(BTN_START, KEYCODE_BUTTON_START);
        STANDARD_KEYS.put(BTN_MODE, KEYCODE_BUTTON_MODE);
        STANDARD_KEYS.put(BTN_THUMBL, KEYCODE_BUTTON_THUMBL);
        STANDARD_KEYS.put(BTN_THUMBR, KEYCODE_BUTTON_THUMBR);
        STANDARD_KEYS.put(BTN_DPAD_UP, KEYCODE_DPAD_UP);
        STANDARD_KEYS.put(BTN_DPAD_DOWN, KEYCODE_DPAD_DOWN);
        STANDARD_KEYS.put(BTN_DPAD_LEFT, KEYCODE_DPAD_LEFT);
        STANDARD_KEYS.put(BTN_DPAD_RIGHT, KEYCODE_DPAD_RIGHT);
        STANDARD_KEYS.put(BTN_TRIGGER_HAPPY1, KEYCODE_DPAD_LEFT);
        STANDARD_KEYS.put(BTN_TRIGGER_HAPPY2, KEYCODE_DPAD_RIGHT);
        STANDARD_KEYS.put(BTN_TRIGGER_HAPPY3, KEYCODE_DPAD_UP);
        STANDARD_KEYS.put(BTN_TRIGGER_HAPPY4, KEYCODE_DPAD_DOWN);
        STANDARD_KEYS.put(KEY_BACK, KEYCODE_BACK);
        STANDARD_KEYS.put(KEY_HOME, KEYCODE_HOME);
        STANDARD_KEYS.put(KEY_HOMEPAGE, KEYCODE_HOME);
    }

    private enum Kind { STICK, TRIGGER, HAT }

    private final Sink sink;
    private final Map<Integer, Integer> keyMap = new HashMap<>();
    private final Map<Integer, Integer> absToLogical = new HashMap<>();
    private final EvdevDevice.AbsInfo[] info = new EvdevDevice.AbsInfo[AXES];
    private final float[] axes = new float[AXES];
    private final Set<Integer> held = new LinkedHashSet<>();
    private final Set<Integer> heldRaw = new LinkedHashSet<>();
    private final Set<Integer> rawKeys = new LinkedHashSet<>();
    private boolean dirty;

    private final boolean synthLtAxis, synthRtAxis, synthL2Key, synthR2Key, synthHat, synthDpadKeys;
    private boolean up, down, left, right; // d-pad button state when synthesizing hat

    public final String layoutDescription;

    /** Fine-tuning for this controller (null = none). */
    private volatile Tuning tuning;
    private float[] lastOut;

    public void setTuning(Tuning t) {
        tuning = t == null || t.isDefault() ? null : t.copy();
        dirty = true;
        // let go of trigger presses decided under the old press point
        if (info[LT] != null) setKey(KEYCODE_BUTTON_L2, false);
        if (info[RT] != null) setKey(KEYCODE_BUTTON_R2, false);
        flush();
    }

    /** DockPad decides L2/R2 presses from the analog trigger (instead of the controller's own button). */
    private boolean ownTrigger(int logical) {
        Tuning t = tuning;
        return info[logical] != null && t != null && t.customTriggerPoint();
    }

    public Mapper(EvdevDevice dev, Sink sink) {
        this.sink = sink;
        for (int code : dev.keys) {
            Integer kc = STANDARD_KEYS.get(code);
            if (kc != null) keyMap.put(code, kc);
            else rawKeys.add(code);
        }

        assign(dev, ABS_X, LX);
        assign(dev, ABS_Y, LY);
        String right;
        if (dev.hasAbs(ABS_RX) && dev.hasAbs(ABS_RY)) {
            // Linux standard: right stick on RX/RY, analog triggers on Z/RZ
            assign(dev, ABS_RX, RX);
            assign(dev, ABS_RY, RY);
            assign(dev, ABS_Z, LT);
            assign(dev, ABS_RZ, RT);
            right = "RX/RY";
        } else if (dev.hasAbs(ABS_Z) && dev.hasAbs(ABS_RZ)) {
            // Android/HID style: right stick on Z/RZ
            assign(dev, ABS_Z, RX);
            assign(dev, ABS_RZ, RY);
            right = "Z/RZ";
        } else {
            right = "none";
        }
        assign(dev, ABS_BRAKE, LT);
        assign(dev, ABS_GAS, RT);
        assign(dev, ABS_HAT0X, HX);
        assign(dev, ABS_HAT0Y, HY);

        boolean hasDpadKeys = keyMap.containsValue(KEYCODE_DPAD_UP);
        synthLtAxis = info[LT] == null;
        synthRtAxis = info[RT] == null;
        synthL2Key = info[LT] != null && !keyMap.containsValue(KEYCODE_BUTTON_L2);
        synthR2Key = info[RT] != null && !keyMap.containsValue(KEYCODE_BUTTON_R2);
        synthHat = info[HX] == null && hasDpadKeys;
        synthDpadKeys = info[HX] != null && !hasDpadKeys;

        layoutDescription = keyMap.size() + " buttons, right stick " + right
                + ", triggers " + (synthLtAxis ? "digital" : "analog")
                + (synthHat ? ", d-pad buttons→hat" : "")
                + (synthDpadKeys ? ", hat→d-pad buttons" : "");
    }

    private void assign(EvdevDevice dev, int code, int logical) {
        EvdevDevice.AbsInfo a = dev.abs.get(code);
        if (a == null || a.max == a.min) return;
        // remove any earlier source for the same logical axis
        absToLogical.values().remove(logical);
        absToLogical.put(code, logical);
        info[logical] = a;
    }

    private static Kind kind(int logical) {
        if (logical <= RY) return Kind.STICK;
        if (logical <= RT) return Kind.TRIGGER;
        return Kind.HAT;
    }

    static float normalize(EvdevDevice.AbsInfo a, Kind k, int raw) {
        float range = a.max - a.min;
        switch (k) {
            case STICK: {
                float v = ((raw - a.min) / range) * 2f - 1f;
                float dead = Math.max(a.flat * 2f / range, 0f);
                if (Math.abs(v) <= dead) return 0f;
                return clamp(v, -1f, 1f);
            }
            case TRIGGER:
                return clamp((raw - a.min) / range, 0f, 1f);
            default:
                return raw < 0 ? -1f : raw > 0 ? 1f : 0f;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    public void onEvent(int type, int code, int value) {
        if (type == EV_KEY) {
            if (value == 2) return; // autorepeat
            Integer kc = keyMap.get(code);
            if (kc == null) kc = STANDARD_KEYS.get(code); // e.g. a remapped button the device doesn't have
            boolean pressed = value != 0;
            if (kc == null) {
                if (!rawKeys.contains(code)) return;
                if (pressed ? heldRaw.add(code) : heldRaw.remove(code)) sink.rawKey(code, pressed);
                return;
            }
            if ((kc == KEYCODE_BUTTON_L2 && ownTrigger(LT)) || (kc == KEYCODE_BUTTON_R2 && ownTrigger(RT))) return;
            setKey(kc, pressed);
            if (kc == KEYCODE_BUTTON_L2 && synthLtAxis) setAxis(LT, pressed ? 1f : 0f);
            if (kc == KEYCODE_BUTTON_R2 && synthRtAxis) setAxis(RT, pressed ? 1f : 0f);
            if (synthHat) {
                if (kc == KEYCODE_DPAD_UP) up = pressed;
                else if (kc == KEYCODE_DPAD_DOWN) down = pressed;
                else if (kc == KEYCODE_DPAD_LEFT) left = pressed;
                else if (kc == KEYCODE_DPAD_RIGHT) right = pressed;
                setAxis(HX, (right ? 1f : 0f) - (left ? 1f : 0f));
                setAxis(HY, (down ? 1f : 0f) - (up ? 1f : 0f));
            }
        } else if (type == EV_ABS) {
            Integer l = absToLogical.get(code);
            if (l == null) return;
            float v = normalize(info[l], kind(l), value);
            setAxis(l, v);
            if (l == LT && (synthL2Key || ownTrigger(LT))) triggerKey(KEYCODE_BUTTON_L2, v);
            if (l == RT && (synthR2Key || ownTrigger(RT))) triggerKey(KEYCODE_BUTTON_R2, v);
            if (synthDpadKeys && (l == HX || l == HY)) {
                if (l == HX) {
                    setKey(KEYCODE_DPAD_LEFT, v < 0);
                    setKey(KEYCODE_DPAD_RIGHT, v > 0);
                } else {
                    setKey(KEYCODE_DPAD_UP, v < 0);
                    setKey(KEYCODE_DPAD_DOWN, v > 0);
                }
            }
        } else if (type == EV_SYN && code == SYN_REPORT) {
            flush();
        }
    }

    private void triggerKey(int kc, float v) {
        Tuning t = tuning;
        float on = t == null ? 0.5f : t.pressAt(), off = t == null ? 0.4f : t.releaseAt();
        if (v >= on) setKey(kc, true);
        else if (v < off) setKey(kc, false);
    }

    private void setAxis(int l, float v) {
        if (axes[l] != v) {
            axes[l] = v;
            dirty = true;
        }
    }

    private void setKey(int kc, boolean pressed) {
        if (pressed) {
            if (held.add(kc)) sink.key(kc, true);
        } else if (held.remove(kc)) {
            sink.key(kc, false);
        }
    }

    public void flush() {
        if (dirty) {
            dirty = false;
            Tuning t = tuning;
            float[] out = t == null ? axes.clone() : t.apply(axes, lastOut);
            lastOut = out;
            sink.axes(out.clone());
        }
    }

    /** Releases every held button and centers all axes (on disconnect/stop). */
    public void releaseAll() {
        for (Integer kc : held.toArray(new Integer[0])) setKey(kc, false);
        for (Integer c : heldRaw.toArray(new Integer[0])) {
            heldRaw.remove(c);
            sink.rawKey(c, false);
        }
        for (int i = 0; i < AXES; i++) setAxis(i, 0f);
        up = down = left = right = false;
        flush();
    }
}
