package com.adonzin.dockpad.service;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.Mapper;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * While DockPad's on-screen keyboard is open, controller input is turned into simple
 * navigation events ("up", "a", "start"…) that the keyboard (in the app) collects.
 * The left stick repeats while held, like holding a d-pad direction.
 */
final class KeyboardInput {
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private String stickDir;
    private long nextRepeat;

    Mapper.Sink sink() {
        return new Mapper.Sink() {
            @Override
            public void key(int kc, boolean down) {
                if (!down) return;
                String e = name(kc);
                if (e != null) queue.add(e);
            }

            @Override
            public void rawKey(int evdevCode, boolean down) {
            }

            @Override
            public void axes(float[] s) {
                // the d-pad as a hat (how many controllers report it), else the left stick
                float hx = s[Mapper.HX], hy = s[Mapper.HY];
                float x = s[Mapper.LX], y = s[Mapper.LY];
                String dir = null;
                if (hx != 0 || hy != 0) {
                    dir = hx != 0 ? (hx > 0 ? "right" : "left") : (hy > 0 ? "down" : "up");
                } else if (Math.abs(x) > 0.6f || Math.abs(y) > 0.6f) {
                    dir = Math.abs(x) > Math.abs(y) ? (x > 0 ? "right" : "left") : (y > 0 ? "down" : "up");
                }
                synchronized (KeyboardInput.this) {
                    if (dir != null && !dir.equals(stickDir)) {
                        queue.add(dir);
                        nextRepeat = System.currentTimeMillis() + 350;
                    }
                    stickDir = dir;
                }
            }
        };
    }

    private static String name(int kc) {
        switch (kc) {
            case Codes.KEYCODE_DPAD_UP: return "up";
            case Codes.KEYCODE_DPAD_DOWN: return "down";
            case Codes.KEYCODE_DPAD_LEFT: return "left";
            case Codes.KEYCODE_DPAD_RIGHT: return "right";
            case Codes.KEYCODE_BUTTON_A: return "a";
            case Codes.KEYCODE_BUTTON_B: return "b";
            case Codes.KEYCODE_BUTTON_X: return "x";
            case Codes.KEYCODE_BUTTON_Y: return "y";
            case Codes.KEYCODE_BUTTON_L1: return "l1";
            case Codes.KEYCODE_BUTTON_R1: return "r1";
            case Codes.KEYCODE_BUTTON_L2: return "l2";
            case Codes.KEYCODE_BUTTON_R2: return "r2";
            case Codes.KEYCODE_BUTTON_START: return "start";
            case Codes.KEYCODE_BUTTON_SELECT: return "select";
            default: return null;
        }
    }

    /** Called regularly: repeats the stick direction while it's held. */
    synchronized void tick() {
        if (stickDir != null && System.currentTimeMillis() >= nextRepeat) {
            queue.add(stickDir);
            nextRepeat = System.currentTimeMillis() + 120;
        }
    }

    String drain() {
        StringBuilder b = new StringBuilder();
        String e;
        while ((e = queue.poll()) != null) b.append(b.length() == 0 ? "" : ",").append(e);
        return b.toString();
    }

    synchronized void reset() {
        queue.clear();
        stickDir = null;
    }
}
