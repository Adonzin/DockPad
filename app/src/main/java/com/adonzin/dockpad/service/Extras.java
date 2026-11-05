package com.adonzin.dockpad.service;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * DockPad's virtual mouse, keyboard and touchscreen. Each is created the first time it's
 * needed and removed when the bridge stops.
 */
final class Extras {
    static final int VID = 0x1209, PID_MOUSE = 0xd0c1, PID_KEYBOARD = 0xd0c2, PID_TOUCH = 0xd0c3;

    // evdev codes
    static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2, EV_ABS = 3;
    static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112, BTN_TOUCH = 0x14a;
    static final int REL_X = 0, REL_Y = 1, REL_HWHEEL = 6, REL_WHEEL = 8;
    static final int ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;

    /** Keys the virtual keyboard offers (letters, digits, editing, F-keys, modifiers, media, system). */
    static int[] keyboardKeys() {
        List<Integer> k = new ArrayList<>();
        for (int c = 1; c <= 88; c++) k.add(c); // Esc .. F12 block (letters, digits, punctuation, F1-F10, etc.)
        int[] more = {99, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 113, 114, 115, 125, 142, 158, 163, 164,
                165, 172, 224, 225, 580};
        for (int c : more) if (!k.contains(c)) k.add(c);
        int[] a = new int[k.size()];
        for (int i = 0; i < a.length; i++) a[i] = k.get(i);
        return a;
    }

    private VirtualPad mouse, keyboard, touch;

    private synchronized VirtualPad mouse() {
        if (mouse == null || !mouse.isAlive()) {
            mouse = new VirtualPad();
            mouse.createWithJson("DockPad Mouse", VirtualPad.json("DockPad Mouse", VID, PID_MOUSE,
                    new int[]{EV_KEY, EV_REL}, new int[]{BTN_LEFT, BTN_RIGHT, BTN_MIDDLE},
                    new int[]{REL_X, REL_Y, REL_HWHEEL, REL_WHEEL}, new int[0], "", new int[0]));
            sleep(150);
        }
        return mouse;
    }

    private String savedImeSetting;

    synchronized VirtualPad keyboard() {
        if (keyboard == null || !keyboard.isAlive()) {
            // A keyboard with letter keys makes Android hide the on-screen keyboard: keep it showing.
            if (savedImeSetting == null) {
                savedImeSetting = Actions.exec("settings", "get", "secure", "show_ime_with_hard_keyboard").trim();
                Actions.exec("settings", "put", "secure", "show_ime_with_hard_keyboard", "1");
            }
            keyboard = new VirtualPad();
            keyboard.createWithJson("DockPad Keyboard", VirtualPad.json("DockPad Keyboard", VID, PID_KEYBOARD,
                    new int[]{EV_KEY}, keyboardKeys(), new int[0], new int[0], "", new int[0]));
            sleep(150);
        }
        return keyboard;
    }

    private synchronized VirtualPad touch() {
        if (touch == null || !touch.isAlive()) {
            String info = abs(ABS_MT_SLOT, 0, 9) + "," + abs(ABS_MT_POSITION_X, 0, 4095) + ","
                    + abs(ABS_MT_POSITION_Y, 0, 4095) + "," + abs(ABS_MT_TRACKING_ID, 0, 65535);
            touch = new VirtualPad();
            touch.createWithJson("DockPad Touch", VirtualPad.json("DockPad Touch", VID, PID_TOUCH,
                    new int[]{EV_KEY, EV_ABS}, new int[]{BTN_TOUCH}, new int[0],
                    new int[]{ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID}, info,
                    new int[]{1 /* INPUT_PROP_DIRECT */}));
            sleep(300);
        }
        return touch;
    }

    private static String abs(int code, int min, int max) {
        return "{\"code\":" + code + ",\"info\":{\"value\":0,\"minimum\":" + min + ",\"maximum\":" + max
                + ",\"fuzz\":0,\"flat\":0,\"resolution\":0}}";
    }

    // ------------------------------------------------------------ mouse

    void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        mouse().write(new int[]{EV_REL, REL_X, dx, EV_REL, REL_Y, dy, EV_SYN, 0, 0});
    }

    void wheel(int vertical, int horizontal) {
        if (vertical == 0 && horizontal == 0) return;
        mouse().write(new int[]{EV_REL, REL_WHEEL, vertical, EV_REL, REL_HWHEEL, horizontal, EV_SYN, 0, 0});
    }

    void mouseButton(int button, boolean down) {
        mouse().write(new int[]{EV_KEY, button, down ? 1 : 0, EV_SYN, 0, 0});
    }

    /** Makes sure the mouse exists, so Android shows the pointer. */
    void showPointer() {
        mouse();
    }

    // ------------------------------------------------------------ keyboard

    void key(int code, boolean down) {
        keyboard().write(new int[]{EV_KEY, code, down ? 1 : 0, EV_SYN, 0, 0});
    }

    void tap(int code) {
        key(code, true);
        sleep(30);
        key(code, false);
    }

    // ------------------------------------------------------------ touch

    /** Three fingers tapping the middle of the screen (Moonlight's "show keyboard" gesture). */
    void threeFingerTap() {
        VirtualPad t = touch();
        int[] xs = {1500, 2048, 2596};
        List<Integer> down = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int[] ev = {EV_ABS, ABS_MT_SLOT, i, EV_ABS, ABS_MT_TRACKING_ID, 100 + i,
                    EV_ABS, ABS_MT_POSITION_X, xs[i], EV_ABS, ABS_MT_POSITION_Y, 2048};
            for (int v : ev) down.add(v);
        }
        int[] d = new int[down.size() + 6];
        for (int i = 0; i < down.size(); i++) d[i] = down.get(i);
        d[down.size()] = EV_KEY;
        d[down.size() + 1] = BTN_TOUCH;
        d[down.size() + 2] = 1;
        d[down.size() + 3] = EV_SYN;
        d[down.size() + 4] = 0;
        d[down.size() + 5] = 0;
        t.write(d);
        sleep(70);
        t.write(new int[]{EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, -1,
                EV_ABS, ABS_MT_SLOT, 1, EV_ABS, ABS_MT_TRACKING_ID, -1,
                EV_ABS, ABS_MT_SLOT, 2, EV_ABS, ABS_MT_TRACKING_ID, -1,
                EV_KEY, BTN_TOUCH, 0, EV_SYN, 0, 0});
    }

    synchronized void close() {
        for (VirtualPad v : new VirtualPad[]{mouse, keyboard, touch}) {
            if (v != null) {
                try {
                    v.close();
                } catch (Throwable e) {
                    Log.w("DockPad", "closing extra device", e);
                }
            }
        }
        mouse = keyboard = touch = null;
        if (savedImeSetting != null) {
            String v = "1".equals(savedImeSetting) ? "1" : "0";
            Actions.exec("settings", "put", "secure", "show_ime_with_hard_keyboard", v);
            savedImeSetting = null;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
