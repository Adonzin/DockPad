package com.adonzin.dockpad.core;

/** Linux evdev and Android constants, kept here so the core has no Android dependency. */
public final class Codes {
    private Codes() {}

    // evdev event types
    public static final int EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    public static final int SYN_REPORT = 0;

    // evdev gamepad buttons (Linux "standard gamepad" layout)
    public static final int BTN_SOUTH = 0x130, BTN_EAST = 0x131, BTN_C = 0x132, BTN_NORTH = 0x133,
            BTN_WEST = 0x134, BTN_Z = 0x135, BTN_TL = 0x136, BTN_TR = 0x137, BTN_TL2 = 0x138,
            BTN_TR2 = 0x139, BTN_SELECT = 0x13a, BTN_START = 0x13b, BTN_MODE = 0x13c,
            BTN_THUMBL = 0x13d, BTN_THUMBR = 0x13e;
    public static final int BTN_DPAD_UP = 0x220, BTN_DPAD_DOWN = 0x221, BTN_DPAD_LEFT = 0x222, BTN_DPAD_RIGHT = 0x223;
    // xpad reports d-pad as these on some controllers
    public static final int BTN_TRIGGER_HAPPY1 = 0x2c0, BTN_TRIGGER_HAPPY2 = 0x2c1,
            BTN_TRIGGER_HAPPY3 = 0x2c2, BTN_TRIGGER_HAPPY4 = 0x2c3;
    public static final int KEY_HOME = 0x66, KEY_BACK = 0x9e, KEY_HOMEPAGE = 0xac;

    // evdev axes
    public static final int ABS_X = 0x00, ABS_Y = 0x01, ABS_Z = 0x02, ABS_RX = 0x03, ABS_RY = 0x04,
            ABS_RZ = 0x05, ABS_GAS = 0x09, ABS_BRAKE = 0x0a, ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;

    // Android key codes
    public static final int KEYCODE_HOME = 3, KEYCODE_BACK = 4, KEYCODE_DPAD_UP = 19, KEYCODE_DPAD_DOWN = 20,
            KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22, KEYCODE_BUTTON_A = 96, KEYCODE_BUTTON_B = 97,
            KEYCODE_BUTTON_C = 98, KEYCODE_BUTTON_X = 99, KEYCODE_BUTTON_Y = 100, KEYCODE_BUTTON_Z = 101,
            KEYCODE_BUTTON_L1 = 102, KEYCODE_BUTTON_R1 = 103, KEYCODE_BUTTON_L2 = 104, KEYCODE_BUTTON_R2 = 105,
            KEYCODE_BUTTON_THUMBL = 106, KEYCODE_BUTTON_THUMBR = 107, KEYCODE_BUTTON_START = 108,
            KEYCODE_BUTTON_SELECT = 109, KEYCODE_BUTTON_MODE = 110;

    // Android motion axes
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 11, AXIS_RX = 12, AXIS_RY = 13, AXIS_RZ = 14,
            AXIS_HAT_X = 15, AXIS_HAT_Y = 16, AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18, AXIS_GAS = 22, AXIS_BRAKE = 23;

    public static final int BTN_LEFT = 0x110;
    public static final int ABS_MT_POSITION_X = 0x35;
    public static final int KEY_VOLUMEDOWN = 0x72, KEY_VOLUMEUP = 0x73, KEY_APPSELECT = 0x244;

    /** Things a button can be remapped to: label + evdev key code on the virtual controller (-1 = nothing). */
    public static final String[] OUTPUT_LABELS = {"Home", "Back", "Recent apps", "Volume up", "Volume down",
            "A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Start", "Select", "L3 (stick press)", "R3 (stick press)",
            "Guide / Mode", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Do nothing"};
    public static final int[] OUTPUT_CODES = {KEY_HOME, KEY_BACK, KEY_APPSELECT, KEY_VOLUMEUP, KEY_VOLUMEDOWN,
            BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_START, BTN_SELECT,
            BTN_THUMBL, BTN_THUMBR, BTN_MODE, BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT, -1};

    public static String outputLabel(int code) {
        for (int i = 0; i < OUTPUT_CODES.length; i++) if (OUTPUT_CODES[i] == code) return OUTPUT_LABELS[i];
        return String.format("key 0x%x", code);
    }

    /** Human name of a raw evdev key code, as a controller reports it. */
    public static String keyName(int code) {
        switch (code) {
            case BTN_LEFT: return "Touchpad click";
            case BTN_SOUTH: return "A / Cross (bottom)";
            case BTN_EAST: return "B / Circle (right)";
            case BTN_NORTH: return "X / Triangle (top)";
            case BTN_WEST: return "Y / Square (left)";
            case BTN_C: return "C";
            case BTN_Z: return "Z";
            case BTN_TL: return "L1";
            case BTN_TR: return "R1";
            case BTN_TL2: return "L2";
            case BTN_TR2: return "R2";
            case BTN_SELECT: return "Select / Share";
            case BTN_START: return "Start / Options";
            case BTN_MODE: return "Guide / Home / PS";
            case BTN_THUMBL: return "L3";
            case BTN_THUMBR: return "R3";
            case BTN_DPAD_UP: return "D-pad up";
            case BTN_DPAD_DOWN: return "D-pad down";
            case BTN_DPAD_LEFT: return "D-pad left";
            case BTN_DPAD_RIGHT: return "D-pad right";
            case KEY_HOME: case KEY_HOMEPAGE: return "Home key";
            case KEY_BACK: return "Back key";
            case KEY_APPSELECT: return "Recent apps key";
            case KEY_VOLUMEUP: return "Volume up";
            case KEY_VOLUMEDOWN: return "Volume down";
            default: return String.format("button 0x%x", code);
        }
    }

    /** Short label for a physical button, used in combos like "Select + B". */
    public static String shortKeyName(int code) {
        switch (code) {
            case BTN_LEFT: return "Touchpad";
            case BTN_SOUTH: return "A";
            case BTN_EAST: return "B";
            case BTN_NORTH: return "X";
            case BTN_WEST: return "Y";
            case BTN_C: return "C";
            case BTN_Z: return "Z";
            case BTN_TL: return "L1";
            case BTN_TR: return "R1";
            case BTN_TL2: return "L2";
            case BTN_TR2: return "R2";
            case BTN_SELECT: return "Select";
            case BTN_START: return "Start";
            case BTN_MODE: return "Guide";
            case BTN_THUMBL: return "L3";
            case BTN_THUMBR: return "R3";
            case BTN_DPAD_UP: return "Up";
            case BTN_DPAD_DOWN: return "Down";
            case BTN_DPAD_LEFT: return "Left";
            case BTN_DPAD_RIGHT: return "Right";
            case KEY_HOME: case KEY_HOMEPAGE: return "Home";
            case KEY_BACK: return "Back";
            case KEY_APPSELECT: return "Recents";
            case KEY_VOLUMEUP: return "Vol+";
            case KEY_VOLUMEDOWN: return "Vol-";
            default: return String.format("0x%x", code);
        }
    }
}
