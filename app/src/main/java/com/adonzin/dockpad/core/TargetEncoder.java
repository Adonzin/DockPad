package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.adonzin.dockpad.core.Codes.*;

/**
 * Turns normalized gamepad state (from one or more Mappers) back into raw evdev events
 * in the exact layout of the device being imitated (the built-in controls), ready to be
 * written to the virtual controller.
 */
public final class TargetEncoder implements Mapper.Sink {

    public interface Out {
        /** Flat list of (type, code, value) triples, ending with a SYN_REPORT. */
        void write(int[] events);
    }

    private final EvdevDevice target;
    private final Out out;
    private final Map<Integer, Integer> keyOut = new HashMap<>();
    private final int[] axisCode = new int[Mapper.AXES];
    private final Map<Integer, Integer> pressCount = new HashMap<>();
    private final Map<Integer, Integer> absValue = new HashMap<>();
    private final Map<Integer, Boolean> keyState = new HashMap<>();
    private final int[] dpadKeyCount = new int[4]; // up, down, left, right
    private float hatX, hatY;
    private final List<Integer> pending = new ArrayList<>();

    public TargetEncoder(EvdevDevice target, Out out) {
        this.target = target;
        this.out = out;
        mapKey(KEYCODE_BUTTON_A, BTN_SOUTH);
        mapKey(KEYCODE_BUTTON_B, BTN_EAST);
        mapKey(KEYCODE_BUTTON_C, BTN_C);
        mapKey(KEYCODE_BUTTON_X, BTN_NORTH);
        mapKey(KEYCODE_BUTTON_Y, BTN_WEST);
        mapKey(KEYCODE_BUTTON_Z, BTN_Z);
        mapKey(KEYCODE_BUTTON_L1, BTN_TL);
        mapKey(KEYCODE_BUTTON_R1, BTN_TR);
        mapKey(KEYCODE_BUTTON_L2, BTN_TL2);
        mapKey(KEYCODE_BUTTON_R2, BTN_TR2);
        mapKey(KEYCODE_BUTTON_SELECT, BTN_SELECT);
        mapKey(KEYCODE_BUTTON_START, BTN_START);
        mapKey(KEYCODE_BUTTON_MODE, BTN_MODE);
        mapKey(KEYCODE_BUTTON_THUMBL, BTN_THUMBL);
        mapKey(KEYCODE_BUTTON_THUMBR, BTN_THUMBR);
        mapKey(KEYCODE_BACK, KEY_BACK);
        if (target.hasKey(KEY_HOME)) mapKey(KEYCODE_HOME, KEY_HOME);
        else mapKey(KEYCODE_HOME, KEY_HOMEPAGE);
        // If the target has no Mode button, use Home for it (and vice versa)
        if (!keyOut.containsKey(KEYCODE_BUTTON_MODE) && keyOut.containsKey(KEYCODE_HOME))
            keyOut.put(KEYCODE_BUTTON_MODE, keyOut.get(KEYCODE_HOME));

        axisCode[Mapper.LX] = pick(ABS_X);
        axisCode[Mapper.LY] = pick(ABS_Y);
        boolean rxry = target.hasAbs(ABS_RX) && target.hasAbs(ABS_RY);
        axisCode[Mapper.RX] = rxry ? ABS_RX : pick(ABS_Z);
        axisCode[Mapper.RY] = rxry ? ABS_RY : pick(ABS_RZ);
        axisCode[Mapper.LT] = target.hasAbs(ABS_BRAKE) ? ABS_BRAKE : (rxry ? pick(ABS_Z) : -1);
        axisCode[Mapper.RT] = target.hasAbs(ABS_GAS) ? ABS_GAS : (rxry ? pick(ABS_RZ) : -1);
        axisCode[Mapper.HX] = pick(ABS_HAT0X);
        axisCode[Mapper.HY] = pick(ABS_HAT0Y);
    }

    private void mapKey(int androidKey, int evdev) {
        if (target.hasKey(evdev)) keyOut.put(androidKey, evdev);
    }

    private int pick(int code) {
        return target.hasAbs(code) ? code : -1;
    }

    // ------------------------------------------------------------ Mapper.Sink

    @Override
    public synchronized void key(int androidKeyCode, boolean down) {
        int d = dpadIndex(androidKeyCode);
        if (d >= 0) {
            dpadKeyCount[d] = Math.max(0, dpadKeyCount[d] + (down ? 1 : -1));
            updateDpad();
        } else {
            Integer code = keyOut.get(androidKeyCode);
            if (code != null) press(code, down);
        }
        flush();
    }

    @Override
    public synchronized void rawKey(int evdevCode, boolean down) {
        if (target.hasKey(evdevCode)) press(evdevCode, down);
        flush();
    }

    @Override
    public synchronized void axes(float[] s) {
        for (int l = Mapper.LX; l <= Mapper.RT; l++) {
            int code = axisCode[l];
            if (code < 0) continue;
            EvdevDevice.AbsInfo a = target.abs.get(code);
            float v = s[l];
            int raw;
            if (l <= Mapper.RY) raw = Math.round(a.min + (v + 1f) / 2f * (a.max - a.min));
            else raw = Math.round(a.min + v * (a.max - a.min));
            setAbs(code, raw);
        }
        hatX = s[Mapper.HX];
        hatY = s[Mapper.HY];
        updateDpad();
        flush();
    }

    // ------------------------------------------------------------ helpers

    private static int dpadIndex(int kc) {
        switch (kc) {
            case KEYCODE_DPAD_UP: return 0;
            case KEYCODE_DPAD_DOWN: return 1;
            case KEYCODE_DPAD_LEFT: return 2;
            case KEYCODE_DPAD_RIGHT: return 3;
            default: return -1;
        }
    }

    /** The d-pad can arrive as buttons, as a hat, or both: output whatever the target has. */
    private void updateDpad() {
        boolean up = dpadKeyCount[0] > 0 || hatY < -0.5f;
        boolean down = dpadKeyCount[1] > 0 || hatY > 0.5f;
        boolean left = dpadKeyCount[2] > 0 || hatX < -0.5f;
        boolean right = dpadKeyCount[3] > 0 || hatX > 0.5f;
        setKeyState(BTN_DPAD_UP, up);
        setKeyState(BTN_DPAD_DOWN, down);
        setKeyState(BTN_DPAD_LEFT, left);
        setKeyState(BTN_DPAD_RIGHT, right);
        if (axisCode[Mapper.HX] >= 0) setAbs(ABS_HAT0X, right ? 1 : left ? -1 : 0);
        if (axisCode[Mapper.HY] >= 0) setAbs(ABS_HAT0Y, down ? 1 : up ? -1 : 0);
    }

    private void setKeyState(int code, boolean pressed) {
        if (!target.hasKey(code)) return;
        Boolean was = keyState.get(code);
        if (was != null && was == pressed) return;
        if (was == null && !pressed) {
            keyState.put(code, false);
            return;
        }
        keyState.put(code, pressed);
        emit(EV_KEY, code, pressed ? 1 : 0);
    }

    private void press(int code, boolean down) {
        int c = pressCount.getOrDefault(code, 0);
        if (down) {
            pressCount.put(code, c + 1);
            if (c == 0) emit(EV_KEY, code, 1);
        } else if (c > 0) {
            pressCount.put(code, c - 1);
            if (c == 1) emit(EV_KEY, code, 0);
        }
    }

    private void setAbs(int code, int raw) {
        Integer prev = absValue.get(code);
        if (prev != null && prev == raw) return;
        absValue.put(code, raw);
        emit(EV_ABS, code, raw);
    }

    private void emit(int type, int code, int value) {
        pending.add(type);
        pending.add(code);
        pending.add(value);
    }

    private void flush() {
        if (pending.isEmpty()) return;
        emit(EV_SYN, SYN_REPORT, 0);
        int[] ev = new int[pending.size()];
        for (int i = 0; i < ev.length; i++) ev[i] = pending.get(i);
        pending.clear();
        out.write(ev);
    }
}
