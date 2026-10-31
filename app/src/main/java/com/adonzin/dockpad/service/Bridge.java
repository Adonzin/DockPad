package com.adonzin.dockpad.service;

import android.os.Process;
import android.util.Log;
import android.view.InputDevice;

import com.adonzin.dockpad.core.EvdevDevice;
import com.adonzin.dockpad.core.Mapper;
import com.adonzin.dockpad.core.TargetEncoder;

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
 * The bridge: creates one virtual "DockPad Controller" shaped exactly like the built-in
 * controls, takes exclusive control of the built-in controls and every external gamepad,
 * and feeds all of them into the virtual controller. Apps then only need to know about
 * one controller, whichever physical controller you are holding.
 */
final class Bridge {
    private static final String TAG = "DockPad";

    /** Known built-in controllers (vendor, product). Retroid Pocket 5 first. */
    private static final int[][] KNOWN_BUILT_IN = {{0x2022, 0x3002}};

    private volatile boolean running;
    private volatile boolean includeBuiltIn = true;
    private Thread scanner;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private String lastDevList = "";
    private volatile String lastError = "";

    private volatile EvdevDevice target;
    private VirtualPad pad;
    private TargetEncoder encoder;
    private final AtomicLong written = new AtomicLong();

    synchronized String start(boolean includeBuiltIn) {
        this.includeBuiltIn = includeBuiltIn;
        if (running) {
            lastDevList = ""; // force a rescan with the new setting
            return "already running";
        }
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
        teardown();
    }

    private synchronized void teardown() {
        for (Source s : sources.values()) s.close();
        sources.clear();
        if (pad != null) pad.close();
        pad = null;
        encoder = null;
    }

    boolean isRunning() {
        return running;
    }

    synchronized String status() {
        StringBuilder b = new StringBuilder();
        b.append("Bridge: ").append(running ? "RUNNING" : "stopped").append('\n');
        EvdevDevice t = target;
        b.append("Built-in controls: ").append(t == null ? "not found" : t.name + " [" + t.id() + "]").append('\n');
        b.append("Virtual controller \"").append(VirtualPad.NAME).append("\": ")
                .append(pad == null ? "not created" : pad.describe()).append('\n');
        InputDevice vd = findAndroidDeviceByName(VirtualPad.NAME);
        if (vd != null) b.append("   seen by Android as id ").append(vd.getId()).append('\n');
        b.append("Controllers feeding it:\n");
        if (sources.isEmpty()) b.append("   none\n");
        for (Source s : sources.values()) {
            b.append("• ").append(s.dev.name).append(" [").append(s.dev.id()).append("]")
                    .append(s.alive ? "" : " (disconnected)").append('\n')
                    .append("    ").append(s.mapper == null ? "" : s.mapper.layoutDescription).append('\n')
                    .append("    exclusive: ").append(s.hideMethod)
                    .append(", events: ").append(s.eventsRead.get()).append('\n');
        }
        b.append("Events sent to virtual controller: ").append(written.get()).append('\n');
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
                    if (pad != null && !pad.isAlive()) changed = true;
                }
                if (changed) {
                    lastDevList = list;
                    rescan();
                }
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
        outer:
        for (int[] k : KNOWN_BUILT_IN) {
            for (EvdevDevice d : devs) {
                if (d.vendor == k[0] && d.product == k[1] && d.isGamepad() && !VirtualPad.NAME.equals(d.name)) {
                    newTarget = d;
                    break outer;
                }
            }
        }

        synchronized (this) {
            if (!running) return;
            boolean targetChanged = newTarget == null || target == null || !newTarget.path.equals(target.path);
            if (targetChanged || pad == null || !pad.isAlive()) {
                teardown();
                target = newTarget;
                if (newTarget == null) return;
                pad = new VirtualPad();
                if (!pad.create(newTarget)) {
                    lastError = pad.describe();
                    return;
                }
                VirtualPad p = pad;
                encoder = new TargetEncoder(newTarget, events -> {
                    p.write(events);
                    written.addAndGet(events.length / 3);
                });
                Log.i(TAG, "Virtual controller created, cloning " + newTarget);
            }

            Set<String> present = new HashSet<>();
            for (EvdevDevice d : devs) present.add(d.path);
            for (Source s : new ArrayList<>(sources.values())) {
                boolean unwanted = s.dev.path.equals(target.path) && !includeBuiltIn;
                if (!s.alive || !present.contains(s.dev.path) || unwanted) {
                    s.close();
                    sources.remove(s.dev.path);
                }
            }
            for (EvdevDevice d : devs) {
                if (!d.isGamepad() || VirtualPad.NAME.equals(d.name) || sources.containsKey(d.path)) continue;
                if (d.path.equals(target.path) && !includeBuiltIn) continue;
                Source s = new Source(d, encoder);
                sources.put(d.path, s);
                s.start();
            }
        }
    }

    static InputDevice findAndroidDevice(EvdevDevice d) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && dev.getVendorId() == d.vendor && dev.getProductId() == d.product
                    && d.name.equals(dev.getName())) return dev;
        }
        return null;
    }

    static InputDevice findAndroidDeviceByName(String name) {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice dev = InputDevice.getDevice(id);
                if (dev != null && name.equals(dev.getName())) return dev;
            }
        } catch (Throwable ignored) {
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

    // ---------------------------------------------------------------- sources

    private final class Source {
        final EvdevDevice dev;
        final TargetEncoder sink;
        volatile Mapper mapper;
        volatile boolean alive = true;
        volatile String hideMethod = "pending";
        final AtomicLong eventsRead = new AtomicLong();
        private FileInputStream in;
        private int disabledAndroidId = -1;

        Source(EvdevDevice dev, TargetEncoder sink) {
            this.dev = dev;
            this.sink = sink;
        }

        void start() {
            Thread t = new Thread(this::readLoop, "DockPad-" + dev.path);
            t.setDaemon(true);
            t.start();
        }

        private void readLoop() {
            try {
                in = new FileInputStream(dev.path);
                String grabErr = Hidden.grab(in.getFD());
                if (grabErr == null) {
                    hideMethod = "yes";
                } else {
                    InputDevice ad = findAndroidDevice(dev);
                    if (ad != null && Hidden.setDeviceEnabled(ad.getId(), false)) {
                        disabledAndroidId = ad.getId();
                        hideMethod = "yes (disabled)";
                    } else {
                        hideMethod = "NO (" + grabErr + ")";
                    }
                }
                mapper = new Mapper(dev, sink);
                Log.i(TAG, "Feeding " + dev + " — " + mapper.layoutDescription + ", exclusive: " + hideMethod);

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
