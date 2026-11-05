package com.adonzin.dockpad.service;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.Mapper;

import java.util.HashMap;
import java.util.Map;

/**
 * PC control mode: controllers act as a mouse and keyboard (for Moonlight and other remote
 * desktop apps, or any app without controller support).
 *
 *   Right stick  pointer          Left stick  scroll
 *   A            left click       B           right click      R3   middle click
 *   X            Backspace        Y           show keyboard (Moonlight)
 *   Start        Enter            Select      Esc              Guide  Windows key
 *   L1 / R1      Alt+Shift+Tab / Alt+Tab (Alt stays held briefly to step through windows)
 *   L2 / R2      Ctrl / Shift (held)          L3   Tab         D-pad  arrow keys
 *   Home / Back / volume keep working as usual.
 */
final class PcMode {
    // keyboard key codes
    private static final int KEY_ESC = 1, KEY_BACKSPACE = 14, KEY_TAB = 15, KEY_ENTER = 28, KEY_LEFTCTRL = 29,
            KEY_LEFTSHIFT = 42, KEY_LEFTALT = 56, KEY_UP = 103, KEY_LEFT = 105, KEY_RIGHT = 106, KEY_DOWN = 108,
            KEY_LEFTMETA = 125, KEY_HOMEPAGE = 172, KEY_BACK = 158, KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115;

    private final Extras extras;
    private final Map<Integer, Integer> keys = new HashMap<>(); // Android key code -> keyboard key
    private volatile boolean active;
    private volatile float speed = 1f; // 0.25 .. 3
    private volatile boolean pointerOnLeft;

    private final float[] axes = new float[Mapper.AXES];
    private Thread loop;
    private long altReleaseAt;
    private boolean altHeld;

    PcMode(Extras extras) {
        this.extras = extras;
        keys.put(Codes.KEYCODE_BUTTON_X, KEY_BACKSPACE);
        keys.put(Codes.KEYCODE_BUTTON_START, KEY_ENTER);
        keys.put(Codes.KEYCODE_BUTTON_SELECT, KEY_ESC);
        keys.put(Codes.KEYCODE_BUTTON_MODE, KEY_LEFTMETA);
        keys.put(Codes.KEYCODE_BUTTON_L2, KEY_LEFTCTRL);
        keys.put(Codes.KEYCODE_BUTTON_R2, KEY_LEFTSHIFT);
        keys.put(Codes.KEYCODE_BUTTON_THUMBL, KEY_TAB);
        keys.put(Codes.KEYCODE_DPAD_UP, KEY_UP);
        keys.put(Codes.KEYCODE_DPAD_DOWN, KEY_DOWN);
        keys.put(Codes.KEYCODE_DPAD_LEFT, KEY_LEFT);
        keys.put(Codes.KEYCODE_DPAD_RIGHT, KEY_RIGHT);
        keys.put(Codes.KEYCODE_HOME, KEY_HOMEPAGE);
        keys.put(Codes.KEYCODE_BACK, KEY_BACK);
    }

    boolean isActive() {
        return active;
    }

    void settings(float speed, boolean pointerOnLeft) {
        this.speed = Math.max(0.25f, Math.min(3f, speed));
        this.pointerOnLeft = pointerOnLeft;
    }

    synchronized void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) {
            extras.showPointer();
            extras.keyboard();
            loop = new Thread(this::run, "DockPad-pointer");
            loop.setDaemon(true);
            loop.start();
        } else {
            if (loop != null) loop.interrupt();
            loop = null;
            for (int i = 0; i < axes.length; i++) axes[i] = 0;
            if (altHeld) {
                extras.key(KEY_LEFTALT, false);
                altHeld = false;
            }
        }
    }

    /** A sink for one controller's normalized input while PC mode is on. */
    Mapper.Sink sink() {
        return new Mapper.Sink() {
            @Override
            public void key(int androidKeyCode, boolean down) {
                onKey(androidKeyCode, down);
            }

            @Override
            public void rawKey(int evdevCode, boolean down) {
                if (evdevCode == Codes.KEY_VOLUMEUP) extras.key(KEY_VOLUMEUP, down);
                else if (evdevCode == Codes.KEY_VOLUMEDOWN) extras.key(KEY_VOLUMEDOWN, down);
            }

            @Override
            public void axes(float[] state) {
                synchronized (axes) {
                    System.arraycopy(state, 0, axes, 0, Math.min(state.length, axes.length));
                }
            }
        };
    }

    private void onKey(int kc, boolean down) {
        if (!active) return;
        switch (kc) {
            case Codes.KEYCODE_BUTTON_A: extras.mouseButton(Extras.BTN_LEFT, down); return;
            case Codes.KEYCODE_BUTTON_B: extras.mouseButton(Extras.BTN_RIGHT, down); return;
            case Codes.KEYCODE_BUTTON_THUMBR: extras.mouseButton(Extras.BTN_MIDDLE, down); return;
            case Codes.KEYCODE_BUTTON_Y:
                if (down) Actions.run(Codes.ACTION_MOONLIGHT_KEYBOARD, "", null);
                return;
            case Codes.KEYCODE_BUTTON_L1:
            case Codes.KEYCODE_BUTTON_R1:
                if (down) altTab(kc == Codes.KEYCODE_BUTTON_L1);
                return;
            default:
                Integer k = keys.get(kc);
                if (k != null) extras.key(k, down);
        }
    }

    private synchronized void altTab(boolean backwards) {
        if (!altHeld) {
            extras.key(KEY_LEFTALT, true);
            altHeld = true;
        }
        if (backwards) extras.key(KEY_LEFTSHIFT, true);
        extras.tap(KEY_TAB);
        if (backwards) extras.key(KEY_LEFTSHIFT, false);
        altReleaseAt = System.currentTimeMillis() + 900;
    }

    private void run() {
        float fx = 0, fy = 0, fw = 0, fh = 0;
        long last = System.nanoTime();
        try {
            while (active) {
                Thread.sleep(8);
                long now = System.nanoTime();
                float dt = (now - last) / 1e9f;
                last = now;
                float px, py, sx, sy;
                synchronized (axes) {
                    boolean left = pointerOnLeft;
                    px = axes[left ? Mapper.LX : Mapper.RX];
                    py = axes[left ? Mapper.LY : Mapper.RY];
                    sx = axes[left ? Mapper.RX : Mapper.LX];
                    sy = axes[left ? Mapper.RY : Mapper.LY];
                }
                // pointer: speed grows with how far the stick is pushed
                float mag = (float) Math.sqrt(px * px + py * py);
                if (mag > 0.12f) {
                    float m = Math.min(1f, (mag - 0.12f) / 0.88f);
                    float pixelsPerSecond = 1400f * speed * m * m;
                    fx += px / mag * pixelsPerSecond * dt;
                    fy += py / mag * pixelsPerSecond * dt;
                    int dx = (int) fx, dy = (int) fy;
                    fx -= dx;
                    fy -= dy;
                    extras.move(dx, dy);
                } else {
                    fx = fy = 0;
                }
                // scrolling
                float wv = Math.abs(sy) > 0.25f ? -sy : 0, wh = Math.abs(sx) > 0.25f ? sx : 0;
                fw += wv * 14f * dt;
                fh += wh * 14f * dt;
                int w = (int) fw, h = (int) fh;
                fw -= w;
                fh -= h;
                if (wv == 0) fw = 0;
                if (wh == 0) fh = 0;
                extras.wheel(w, h);
                // finish Alt+Tab
                synchronized (this) {
                    if (altHeld && System.currentTimeMillis() > altReleaseAt) {
                        extras.key(KEY_LEFTALT, false);
                        altHeld = false;
                    }
                }
            }
        } catch (InterruptedException ignored) {
        }
    }
}
