package com.adonzin.dockpad.service;

import android.os.Process;
import android.util.Log;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.EvdevDevice;
import com.adonzin.dockpad.core.Gestures;
import com.adonzin.dockpad.core.Shortcut;
import com.adonzin.dockpad.core.ShortcutEngine;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Handheld controls" (off unless turned on): shortcuts on the handheld itself, for when no
 * controller is in hand. Watches, without taking anything over (games still get every press):
 * <ul>
 *   <li>the built-in controls (presses DockPad itself writes there for player 1 are skipped),</li>
 *   <li>the volume buttons,</li>
 *   <li>the touchscreen, for a swipe in from a corner the user picked.</li>
 * </ul>
 * Only shortcuts recorded on the handheld ({@link Shortcut#HANDHELD}) run here.
 */
final class HandheldWatcher {
    private static final String TAG = "DockPad";
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TRACKING_ID = 0x39, ABS_MT_X = 0x35, ABS_MT_Y = 0x36;
    private static final int KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115;

    interface Host {
        boolean capture(String device, int code, boolean down);
        void output(int code, boolean down, Shortcut from);
        ScheduledExecutorService timer();
        List<Shortcut> shortcuts();
        boolean builtInTakenOver();
    }

    private final Host host;
    private volatile boolean enabled;
    private volatile int cornerX = -1, cornerY = -1;
    private final Map<String, Reader> readers = new HashMap<>();
    private ShortcutEngine engine;

    // presses DockPad wrote into the built-in controls (for player 1): not the handheld's own
    private final Map<Long, Long> injected = new HashMap<>(); // (code<<1|down) -> expiry

    // drawn gestures
    private volatile List<Gestures.Gesture> gestures = new ArrayList<>();
    private final Object drawLock = new Object();
    private volatile int drawFingers; // > 0 while the app records a new gesture
    private String drawn;

    void setGestures(String spec) {
        List<Gestures.Gesture> g = Gestures.decodeAll(spec);
        Gestures.publishNames(g);
        gestures = g;
    }

    /** Waits for one drawing with that many fingers; returns it normalised ("" on timeout). */
    String recordGesture(int timeoutMs, int fingers) throws InterruptedException {
        synchronized (drawLock) {
            drawn = null;
            drawFingers = Math.max(1, fingers);
            long end = System.currentTimeMillis() + timeoutMs;
            while (drawFingers > 0 && drawn == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                drawLock.wait(left);
            }
            drawFingers = 0;
            return drawn == null ? "" : drawn;
        }
    }

    /** A finished drawing: to the recorder, or matched against the taught shapes. */
    private void stroke(float[] raw, int fingers, double diagonalShare) {
        if (diagonalShare < 0.08 || raw.length < 16) return; // a tap or a tiny wiggle
        float[] norm = Gestures.normalize(raw);
        if (norm == null) return;
        if (drawFingers > 0) {
            if (fingers != drawFingers) return;
            synchronized (drawLock) {
                drawn = Gestures.encodePoints(norm);
                drawFingers = 0;
                drawLock.notifyAll();
            }
            return;
        }
        Gestures.Gesture best = null;
        float bestD = Float.MAX_VALUE;
        for (Gestures.Gesture g : gestures) {
            if (g.fingers != fingers) continue;
            float d = g.distance(norm);
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        if (best == null || bestD > Gestures.MATCH) return;
        Log.i(TAG, "handheld gesture: " + best.name + " (" + bestD + ")");
        key(best.code(), true);
        key(best.code(), false);
    }

    // corner learning
    private final Object learnLock = new Object();
    private volatile boolean learning;
    private String learned;

    HandheldWatcher(Host host) {
        this.host = host;
    }

    synchronized void configure(boolean on, String corner) {
        enabled = on;
        cornerX = cornerY = -1;
        if (corner != null && corner.contains(",")) {
            try {
                String[] f = corner.split(",");
                cornerX = Integer.parseInt(f[0].trim());
                cornerY = Integer.parseInt(f[1].trim());
            } catch (Exception ignored) {
            }
        }
        if (!on) stopAll();
        rebuild();
    }

    boolean isEnabled() {
        return enabled;
    }

    synchronized void rebuild() {
        if (engine != null) engine.releaseAll();
        List<Shortcut> mine = new ArrayList<>();
        for (Shortcut s : host.shortcuts()) if (Shortcut.HANDHELD.equals(s.device)) mine.add(s);
        engine = new ShortcutEngine(mine, Shortcut.HANDHELD, new ShortcutEngine.Out() {
            @Override
            public void output(int code, boolean down, Shortcut from) {
                host.output(code, down, from);
            }

            @Override
            public void forward(int code, boolean down) {
                // the press already reached the game: nothing held back to send
            }

            @Override
            public void armed(Shortcut s) {
                Actions.toast("Let go for: " + s.actionLabel());
            }
        }, (r, ms) -> {
            ScheduledFuture<?> f = host.timer().schedule(() -> {
                synchronized (HandheldWatcher.this) {
                    r.run();
                }
            }, ms, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        });
    }

    /** Called with every rescan: opens what's there, closes what's gone. */
    synchronized void update(List<EvdevDevice> devs, EvdevDevice builtIn) {
        if (!enabled) {
            stopAll();
            return;
        }
        Map<String, EvdevDevice> want = new HashMap<>();
        for (EvdevDevice d : devs) {
            if (builtIn != null && d.path.equals(builtIn.path)) want.put(d.path, d);
            else if (!d.isGamepad() && (d.hasKey(KEY_VOLUMEUP) || d.hasKey(KEY_VOLUMEDOWN))) want.put(d.path, d);
            else if (isTouchscreen(d)) want.put(d.path, d);
        }
        for (String p : new ArrayList<>(readers.keySet())) {
            if (!want.containsKey(p)) readers.remove(p).close();
        }
        for (EvdevDevice d : want.values()) {
            if (readers.containsKey(d.path)) continue;
            Reader r = new Reader(d, builtIn != null && d.path.equals(builtIn.path), isTouchscreen(d));
            readers.put(d.path, r);
            r.start();
        }
    }

    private static boolean isTouchscreen(EvdevDevice d) {
        String n = d.name.toLowerCase(java.util.Locale.ROOT);
        return d.hasAbs(ABS_MT_X) && d.hasAbs(ABS_MT_Y) && !d.isGamepad() && !d.isControllerTouchpad()
                && !n.contains("pen") && !n.contains("touchpad") && !n.contains("mouse");
    }

    synchronized void stopAll() {
        for (Reader r : readers.values()) r.close();
        readers.clear();
        if (engine != null) engine.releaseAll();
    }

    /** DockPad wrote these events into the built-in controls: don't take them for the handheld's own. */
    void wroteToBuiltIn(int[] events) {
        if (!enabled) return;
        long until = System.currentTimeMillis() + 600;
        synchronized (injected) {
            for (int i = 0; i + 2 < events.length; i += 3) {
                if (events[i] != Codes.EV_KEY || events[i + 2] == 2) continue;
                injected.put(((long) events[i + 1] << 1) | (events[i + 2] != 0 ? 1 : 0), until);
            }
        }
    }

    private boolean wasInjected(int code, boolean down) {
        long key = ((long) code << 1) | (down ? 1 : 0);
        synchronized (injected) {
            Long until = injected.remove(key);
            return until != null && until >= System.currentTimeMillis();
        }
    }

    /** Waits for a finger held still on the screen and returns where ("x,y", raw), or "" on timeout. */
    String learnCorner(int timeoutMs) throws InterruptedException {
        synchronized (learnLock) {
            learned = null;
            learning = true;
            long end = System.currentTimeMillis() + timeoutMs;
            while (learning && learned == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                learnLock.wait(left);
            }
            learning = false;
            return learned == null ? "" : learned;
        }
    }

    private void key(int code, boolean down) {
        synchronized (this) {
            if (host.capture(Shortcut.HANDHELD, code, down)) return;
            if (engine != null) engine.onKey(code, down);
        }
    }

    String describe() {
        if (!enabled) return "Handheld controls: off\n";
        StringBuilder b = new StringBuilder("Handheld controls: on, watching");
        synchronized (this) {
            if (readers.isEmpty()) b.append(" nothing yet");
            for (Reader r : readers.values()) b.append(" · ").append(r.dev.name).append(r.error == null ? "" : " (" + r.error + ")");
            b.append(cornerX >= 0 ? " · corner swipe set" : " · no corner set");
            b.append(" · ").append(gestures.size()).append(gestures.size() == 1 ? " drawn gesture" : " drawn gestures");
            int n = 0;
            for (Shortcut s : host.shortcuts()) if (Shortcut.HANDHELD.equals(s.device)) n++;
            b.append(" · ").append(n).append(n == 1 ? " shortcut" : " shortcuts");
        }
        return b.append('\n').toString();
    }

    // ------------------------------------------------------------------ reading a device

    private final class Reader extends Thread {
        final EvdevDevice dev;
        final boolean pad, touch;
        volatile boolean alive = true;
        volatile String error;
        private FileInputStream in;

        // touch tracking (the first finger only)
        private int slot, trackingId = -1, startX = -1, startY = -1, x = -1, y = -1;
        private long downAt;
        private boolean multi;
        // every finger (for drawn gestures)
        private final int[] fx = new int[10], fy = new int[10];
        private final boolean[] fOn = new boolean[10];
        private int strokeFingers;
        private final List<Float> path = new ArrayList<>();
        private double pathLen;

        Reader(EvdevDevice dev, boolean pad, boolean touch) {
            super("DockPad-handheld-" + dev.path.replace("/dev/input/", ""));
            this.dev = dev;
            this.pad = pad;
            this.touch = touch;
            setDaemon(true);
        }

        void close() {
            alive = false;
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            interrupt();
        }

        @Override
        public void run() {
            try {
                in = new FileInputStream(dev.path);
                int size = Process.is64Bit() ? 24 : 16;
                int off = size == 24 ? 16 : 8;
                byte[] buf = new byte[size * 64];
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                while (alive) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    for (int p = 0; p + size <= n; p += size) {
                        int type = bb.getShort(p + off) & 0xffff;
                        int code = bb.getShort(p + off + 2) & 0xffff;
                        int value = bb.getInt(p + off + 4);
                        if (touch) touchEvent(type, code, value);
                        else if (type == Codes.EV_KEY && value != 2) keyEvent(code, value != 0);
                    }
                }
            } catch (Throwable t) {
                if (alive) {
                    error = t.getMessage();
                    Log.w(TAG, "handheld " + dev.name + ": " + t);
                }
            }
        }

        private void keyEvent(int code, boolean down) {
            if (pad) {
                if (host.builtInTakenOver()) return; // DockPad's keyboard or PC mode has the controls
                if (wasInjected(code, down)) return; // an external controller's press, written here
            } else if (code != KEY_VOLUMEUP && code != KEY_VOLUMEDOWN) {
                return; // never power or anything else
            }
            key(code, down);
        }

        private void touchEvent(int type, int code, int value) {
            if (type == 3 && slot >= 0 && slot < fOn.length) { // every finger, for drawn gestures
                if (code == ABS_MT_TRACKING_ID) fOn[slot] = value >= 0;
                else if (code == ABS_MT_X) fx[slot] = value;
                else if (code == ABS_MT_Y) fy[slot] = value;
            }
            if (type == Codes.EV_SYN && code == 0) drawFrame();
            if (type == 3) { // EV_ABS
                if (code == ABS_MT_SLOT) {
                    slot = value;
                    if (value > 0 && trackingId >= 0) multi = true;
                } else if (slot == 0 && code == ABS_MT_TRACKING_ID) {
                    if (value >= 0) {
                        trackingId = value;
                        startX = startY = x = y = -1;
                        downAt = System.currentTimeMillis();
                        multi = false;
                    } else if (trackingId >= 0) {
                        trackingId = -1;
                        lifted();
                    }
                } else if (slot == 0 && code == ABS_MT_X) {
                    x = value;
                    if (startX < 0) startX = value;
                } else if (slot == 0 && code == ABS_MT_Y) {
                    y = value;
                    if (startY < 0) startY = value;
                }
            } else if (type == Codes.EV_SYN && learning && trackingId >= 0 && startX >= 0 && startY >= 0
                    && System.currentTimeMillis() - downAt > 800 && near(x, y, startX, startY, 0.03)) {
                synchronized (learnLock) {
                    learned = startX + "," + startY;
                    learning = false;
                    learnLock.notifyAll();
                }
            }
        }

        /** Follows the fingers' centre while the most fingers so far are down; ends when all are lifted. */
        private void drawFrame() {
            int count = 0;
            float cx = 0, cy = 0;
            for (int i = 0; i < fOn.length; i++) {
                if (!fOn[i]) continue;
                count++;
                cx += fx[i];
                cy += fy[i];
            }
            if (count == 0) {
                if (strokeFingers > 0 && !path.isEmpty()) {
                    float[] raw = new float[path.size()];
                    for (int i = 0; i < raw.length; i++) raw[i] = path.get(i);
                    EvdevDevice.AbsInfo ix = dev.abs.get(ABS_MT_X), iy = dev.abs.get(ABS_MT_Y);
                    double w = ix == null ? 1080 : Math.max(1, ix.max - ix.min), h = iy == null ? 1920 : Math.max(1, iy.max - iy.min);
                    stroke(raw, strokeFingers, pathLen / Math.hypot(w, h));
                }
                strokeFingers = 0;
                path.clear();
                pathLen = 0;
                return;
            }
            cx /= count;
            cy /= count;
            if (count > strokeFingers) { // another finger joined: the drawing starts over from here
                strokeFingers = count;
                path.clear();
                pathLen = 0;
            }
            if (count != strokeFingers || path.size() > 4000) return; // fingers lifting at the end
            int n = path.size();
            if (n >= 2) {
                double d = Math.hypot(cx - path.get(n - 2), cy - path.get(n - 1));
                if (d < 2) return; // not moved
                pathLen += d;
            }
            path.add(cx);
            path.add(cy);
        }

        /** A swipe that starts at the chosen corner and moves well away from it, quickly, with one finger. */
        private void lifted() {
            if (learning || multi || cornerX < 0 || startX < 0 || x < 0) return;
            long took = System.currentTimeMillis() - downAt;
            if (took > 900) return;
            if (!near(startX, startY, cornerX, cornerY, 0.10)) return;
            if (near(x, y, startX, startY, 0.15)) return; // didn't move far enough
            key(Codes.GESTURE_CORNER_SWIPE, true);
            key(Codes.GESTURE_CORNER_SWIPE, false);
        }

        /** Within a share of the screen's size (raw touch units) of each other. */
        private boolean near(int ax, int ay, int bx, int by, double share) {
            EvdevDevice.AbsInfo ix = dev.abs.get(ABS_MT_X), iy = dev.abs.get(ABS_MT_Y);
            double w = ix == null ? 1080 : Math.max(1, ix.max - ix.min), h = iy == null ? 1920 : Math.max(1, iy.max - iy.min);
            double dx = (ax - bx) / w, dy = (ay - by) / h;
            return Math.sqrt(dx * dx + dy * dy) <= share;
        }
    }
}
