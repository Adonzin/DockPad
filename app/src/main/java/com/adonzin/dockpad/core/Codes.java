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

    // ------------------------------------------------------------ shortcut outputs
    // >= 0 and < KB : a button on the (virtual) controller (evdev code)
    // KB + code      : a key on DockPad's virtual keyboard (evdev key code)
    // <= -100        : a system action

    public static final int KB = 0x10000;

    public static boolean isKeyboardOutput(int c) {
        return c >= KB && c < KB + 0x300;
    }

    public static boolean isAction(int c) {
        return c <= -100;
    }

    public static final int ACTION_CLEAR_RECENTS = -100, ACTION_FORCE_CLOSE = -101, ACTION_PREVIOUS_APP = -102,
            ACTION_SCREENSHOT = -103, ACTION_NOTIFICATIONS = -104, ACTION_QUICK_SETTINGS = -105,
            ACTION_CLOSE_PANELS = -106, ACTION_TOGGLE_IME_SETTING = -107, ACTION_OPEN_APP = -108,
            ACTION_TOGGLE_PC_MODE = -109, ACTION_SLEEP = -110, ACTION_BRIGHTNESS_UP = -111,
            ACTION_BRIGHTNESS_DOWN = -112, ACTION_PLAY_PAUSE = -113, ACTION_NEXT_TRACK = -114,
            ACTION_PREVIOUS_TRACK = -115, ACTION_MUTE = -116, ACTION_MOONLIGHT_KEYBOARD = -117,
            ACTION_QUICK_MENU = -118, ACTION_HOME = -119, ACTION_BACK = -120;

    public static final String[] CATEGORIES = {"Controller button", "Keyboard key", "System action"};

    public static final String[][] CAT_LABELS = {
            {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Start", "Select", "L3 (stick press)", "R3 (stick press)",
                    "Guide / Mode", "D-pad up", "D-pad down", "D-pad left", "D-pad right",
                    "Home", "Back", "Recent apps", "Volume up", "Volume down"},
            {"Esc", "Enter", "Tab", "Space", "Backspace", "Delete", "Arrow up", "Arrow down", "Arrow left",
                    "Arrow right", "Home (key)", "End", "Page up", "Page down", "Insert", "Ctrl", "Shift", "Alt",
                    "Windows", "Print Screen", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11",
                    "F12", "A (key)", "B (key)", "C (key)", "D (key)", "E (key)", "F (key)", "G (key)", "H (key)",
                    "I (key)", "J (key)", "K (key)", "L (key)", "M (key)", "N (key)", "O (key)", "P (key)",
                    "Q (key)", "R (key)", "S (key)", "T (key)", "U (key)", "V (key)", "W (key)", "X (key)",
                    "Y (key)", "Z (key)", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"},
            {"Open the quick menu", "Home", "Back", "Clear all recent apps", "Force close the current app", "Switch to the previous app", "Screenshot",
                    "Open notifications", "Open quick settings", "Close notifications / quick settings",
                    "Toggle on-screen keyboard (with controllers connected)", "Open an app…",
                    "Toggle Cursor mode", "Sleep (screen off)", "Brightness up", "Brightness down",
                    "Play / pause", "Next track", "Previous track", "Mute",
                    "Moonlight: show keyboard (three-finger tap)"}};

    public static final int[][] CAT_CODES = {
            {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_START, BTN_SELECT,
                    BTN_THUMBL, BTN_THUMBR, BTN_MODE, BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT,
                    KEY_HOME, KEY_BACK, KEY_APPSELECT, KEY_VOLUMEUP, KEY_VOLUMEDOWN},
            {KB + 1, KB + 28, KB + 15, KB + 57, KB + 14, KB + 111, KB + 103, KB + 108, KB + 105, KB + 106,
                    KB + 102, KB + 107, KB + 104, KB + 109, KB + 110, KB + 29, KB + 42, KB + 56, KB + 125, KB + 99,
                    KB + 59, KB + 60, KB + 61, KB + 62, KB + 63, KB + 64, KB + 65, KB + 66, KB + 67, KB + 68,
                    KB + 87, KB + 88,
                    KB + 30, KB + 48, KB + 46, KB + 32, KB + 18, KB + 33, KB + 34, KB + 35, KB + 23, KB + 36,
                    KB + 37, KB + 38, KB + 50, KB + 49, KB + 24, KB + 25, KB + 16, KB + 19, KB + 31, KB + 20,
                    KB + 22, KB + 47, KB + 17, KB + 45, KB + 21, KB + 44,
                    KB + 11, KB + 2, KB + 3, KB + 4, KB + 5, KB + 6, KB + 7, KB + 8, KB + 9, KB + 10},
            {ACTION_QUICK_MENU, ACTION_HOME, ACTION_BACK, ACTION_CLEAR_RECENTS, ACTION_FORCE_CLOSE, ACTION_PREVIOUS_APP, ACTION_SCREENSHOT,
                    ACTION_NOTIFICATIONS, ACTION_QUICK_SETTINGS, ACTION_CLOSE_PANELS, ACTION_TOGGLE_IME_SETTING,
                    ACTION_OPEN_APP, ACTION_TOGGLE_PC_MODE, ACTION_SLEEP, ACTION_BRIGHTNESS_UP,
                    ACTION_BRIGHTNESS_DOWN, ACTION_PLAY_PAUSE, ACTION_NEXT_TRACK, ACTION_PREVIOUS_TRACK,
                    ACTION_MUTE, ACTION_MOONLIGHT_KEYBOARD}};

    public static String outputLabel(int code) {
        for (int c = 0; c < CAT_CODES.length; c++) {
            for (int i = 0; i < CAT_CODES[c].length; i++) {
                if (CAT_CODES[c][i] == code) return CAT_LABELS[c][i];
            }
        }
        if (isKeyboardOutput(code)) return String.format("key 0x%x", code - KB);
        return String.format("button 0x%x", code);
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
