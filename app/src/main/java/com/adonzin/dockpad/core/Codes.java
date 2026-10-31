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
}
