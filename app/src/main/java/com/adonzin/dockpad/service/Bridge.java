package com.adonzin.dockpad.service;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.adonzin.dockpad.core.EvdevDevice;
import com.adonzin.dockpad.core.Mapper;
import com.adonzin.dockpad.core.TargetLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The actual bridge: finds the built-in controls ("target") and any external gamepads
 * ("sources"), takes exclusive control of the sources and re-sends their input as if it
 * came from the built-in controls.
 */
final class Bridge {
    private static final String TAG = "DockPad";

    /** Known built-in controllers (vendor, product). Retroid Pocket 5 first. */
    private static final int[][] KNOWN_BUILT_IN = {{0x2022, 0x3002}};

    private volatile boolean running;
    private Thread scanner;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private String lastDevList = "";
    private final AtomicLong injected = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile String lastError = "";

    // target state
    private volatile EvdevDevice target;
    private volatile int targetId = -1;
    private volatile TargetLayout layout;

    synchronized String start() {
        if (running) return "already running";
        running = true;
        lastDevList = "";
        scanner = new Thread(this::scanLoop, "DockPad-scan");
        scanner.setDaemon(true);
        scanner.start();
        return "started";
    }

    synchronized void stop() {
        running = false;
        if (scanner != null) scanner.interrupt();
        for (Source s : sources.values()) s.close();
        sources.clear();
    }

    boolean isRunning() {
        return running;
    }

    String status() {
        StringBuilder b = new StringBuilder();
        b.append("Bridge: ").append(running ? "RUNNING" : "stopped").append('\n');
        EvdevDevice t = target;
        b.append("Built-in controls: ");
        if (t == null) b.append("not found");
        else b.append(t.name).append(" [").append(t.id()).append("], Android id ").append(targetId);
        b.append('\n');
        synchronized (this) {
            if (sources.isEmpty()) b.append("External controllers: none connected\n");
            for (Source s : sources.values()) {
                b.append("• ").append(s.dev.name).append(" [").append(s.dev.id()).append("] ").append(s.dev.path).append('\n')
                        .append("    layout: ").append(s.mapper == null ? "?" : s.mapper.layoutDescription).append('\n')
                        .append("    hidden from apps: ").append(s.hideMethod).append('\n')
                        .append("    events read: ").append(s.eventsRead.get())
                        .append(s.alive ? "" : "  (disconnected)").append('\n');
            }
        }
        b.append("Injected: ").append(injected.get()).append(", rejected: ").append(rejected.get()).append('\n');
        if (!lastError.isEmpty()) b.append("Last error: ").append(lastError).append('\n');
        return b.toString();
    }

    // ---------------------------------------------------------------- scanning

    private void scanLoop() {
        while (running) {
            try {
                String[] names = new File("/dev/input").list();
                if (names != null) Arrays.sort(names);
                String list = names == null ? "" : String.join(",", names);
                boolean changed = !list.equals(lastDevList);
                synchronized (this) {
                    for (Source s : sources.values()) if (!s.alive) changed = true;
                }
                if (changed) {
                    lastDevList = list;
                    rescan();
                }
                resolveTargetId();
            } catch (Throwable t) {
                error("scan", t);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void rescan() throws IOException, InterruptedException {
        List<EvdevDevice> devs = EvdevDevice.parse(run("getevent", "-i"));
        EvdevDevice newTarget = null;
        for (int[] k : KNOWN_BUILT_IN) {
            for (EvdevDevice d : devs) {
                if (d.vendor == k[0] && d.product == k[1] && d.isGamepad()) newTarget = d;
            }
            if (newTarget != null) break;
        }
        target = newTarget;
        resolveTargetId();

        synchronized (this) {
            // drop sources that went away
            Set<String> present = new HashSet<>();
            for (EvdevDevice d : devs) present.add(d.path);
            for (Source s : new ArrayList<>(sources.values())) {
                if (!s.alive || !present.contains(s.dev.path)) {
                    s.close();
                    sources.remove(s.dev.path);
                }
            }
            if (newTarget == null) return; // nothing to send input to
            for (EvdevDevice d : devs) {
                if (!d.isGamepad() || d.path.equals(newTarget.path) || sources.containsKey(d.path)) continue;
                Source s = new Source(d);
                sources.put(d.path, s);
                s.start();
            }
        }
    }

    private void resolveTargetId() {
        EvdevDevice t = target;
        if (t == null) {
            targetId = -1;
            return;
        }
        InputDevice dev = findAndroidDevice(t);
        if (dev == null) {
            targetId = -1;
            return;
        }
        if (dev.getId() != targetId || layout == null) {
            Set<Integer> axes = new HashSet<>();
            for (InputDevice.MotionRange r : dev.getMotionRanges()) axes.add(r.getAxis());
            layout = new TargetLayout(axes);
            targetId = dev.getId();
        }
    }

    static InputDevice findAndroidDevice(EvdevDevice d) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) continue;
            if (dev.getVendorId() == d.vendor && dev.getProductId() == d.product && d.name.equals(dev.getName())) {
                return dev;
            }
        }
        return null;
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        java.lang.Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
        });
        reader.start();
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy();
        reader.join(1000);
        return out.toString("UTF-8");
    }

    private void error(String where, Throwable t) {
        lastError = where + ": " + t;
        Log.e(TAG, where, t);
    }

    // ---------------------------------------------------------------- output

    private final Object injectLock = new Object();
    private final Map<Integer, Long> keyDownTimes = new LinkedHashMap<>();

    private void sendKey(int keyCode, boolean down) {
        int id = targetId;
        if (id < 0) return;
        long now = SystemClock.uptimeMillis();
        long downTime;
        synchronized (injectLock) {
            if (down) {
                keyDownTimes.put(keyCode, now);
                downTime = now;
            } else {
                Long d = keyDownTimes.remove(keyCode);
                downTime = d == null ? now : d;
            }
        }
        KeyEvent ev = new KeyEvent(downTime, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode,
                0, 0, id, 0, 0, InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_KEYBOARD);
        inject(ev);
    }

    private void sendAxes(float[] state) {
        int id = targetId;
        TargetLayout l = layout;
        if (id < 0 || l == null) return;
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.clear();
        for (int i = 0; i < Mapper.AXES; i++) {
            for (int axis : l.outputs[i]) pc.setAxisValue(axis, state[i]);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, id, 0, InputDevice.SOURCE_JOYSTICK, 0);
        inject(ev);
        ev.recycle();
    }

    private void inject(android.view.InputEvent ev) {
        try {
            if (Hidden.inject(ev)) injected.incrementAndGet();
            else rejected.incrementAndGet();
        } catch (Throwable t) {
            rejected.incrementAndGet();
            error("inject", t);
        }
    }

    // ---------------------------------------------------------------- sources

    private final class Source implements Mapper.Sink {
        final EvdevDevice dev;
        volatile Mapper mapper;
        volatile boolean alive = true;
        volatile String hideMethod = "pending";
        final AtomicLong eventsRead = new AtomicLong();
        private FileInputStream in;
        private int disabledAndroidId = -1;
        private Thread thread;

        Source(EvdevDevice dev) {
            this.dev = dev;
        }

        void start() {
            thread = new Thread(this::readLoop, "DockPad-" + dev.path);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void key(int androidKeyCode, boolean down) {
            sendKey(androidKeyCode, down);
        }

        @Override
        public void axes(float[] state) {
            sendAxes(state);
        }

        private void readLoop() {
            try {
                in = new FileInputStream(dev.path);
                String grabErr = Hidden.grab(in.getFD());
                if (grabErr == null) {
                    hideMethod = "yes (exclusive grab)";
                } else {
                    InputDevice ad = findAndroidDevice(dev);
                    if (ad != null && Hidden.setDeviceEnabled(ad.getId(), false)) {
                        disabledAndroidId = ad.getId();
                        hideMethod = "yes (device disabled)";
                    } else {
                        hideMethod = "NO — apps may see it twice (" + grabErr + ")";
                    }
                }
                mapper = new Mapper(dev, this);
                Log.i(TAG, "Bridging " + dev + " — " + mapper.layoutDescription + ", hidden: " + hideMethod);

                int size = Process.is64Bit() ? 24 : 16;
                int off = size == 24 ? 16 : 8;
                byte[] buf = new byte[size * 64];
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                while (running && alive) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    for (int p = 0; p + size <= n; p += size) {
                        int type = bb.getShort(p + off) & 0xffff;
                        int code = bb.getShort(p + off + 2) & 0xffff;
                        int value = bb.getInt(p + off + 4);
                        eventsRead.incrementAndGet();
                        mapper.onEvent(type, code, value);
                    }
                }
            } catch (Throwable t) {
                if (running && alive) error(dev.name, t);
            } finally {
                close();
            }
        }

        synchronized void close() {
            boolean wasAlive = alive;
            alive = false;
            if (wasAlive && mapper != null) {
                try {
                    mapper.releaseAll();
                } catch (Throwable ignored) {
                }
            }
            if (disabledAndroidId >= 0) {
                Hidden.setDeviceEnabled(disabledAndroidId, true);
                disabledAndroidId = -1;
            }
            if (in != null) {
                try {
                    in.close(); // also releases the exclusive grab
                } catch (IOException ignored) {
                }
                in = null;
            }
        }
    }
}
