package com.adonzin.dockpad.service;

import android.os.Process;
import android.util.Log;
import android.view.InputDevice;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.ControllerLayout;
import com.adonzin.dockpad.core.EvdevDevice;
import com.adonzin.dockpad.core.Shortcut;
import com.adonzin.dockpad.core.ShortcutEngine;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
    private volatile boolean touchpadAsButton = true;
    private Thread scanner;
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private volatile String lastDevList = "";
    private volatile String lastError = "";

    private volatile EvdevDevice target;
    private final AtomicLong written = new AtomicLong();

    // multiplayer: one virtual controller per player
    private static final int MAX_PLAYERS = 4;
    private volatile boolean multiplayer = true;
    private final List<Slot> slots = new ArrayList<>();

    private static final class Slot {
        final int player; // 1-based
        final PadOut pad;
        final TargetEncoder encoder;

        Slot(int player, PadOut pad, TargetEncoder encoder) {
            this.player = player;
            this.pad = pad;
            this.encoder = encoder;
        }
    }

    void setMultiplayer(boolean on) {
        if (multiplayer != on) {
            multiplayer = on;
            synchronized (this) {
                // re-assign everyone
                for (Source s : sources.values()) s.close();
                sources.clear();
            }
            lastDevList = "";
        }
    }

    /** Returns the virtual controller for a player, creating it (and any lower players) if needed. */
    private Slot slot(int index) {
        while (slots.size() <= index) {
            int player = slots.size() + 1;
            PadOut pad;
            if (player == 1) {
                // Player 1 goes straight into the built-in controls: no remapping anywhere.
                BuiltInWriter w = new BuiltInWriter(target);
                if (!w.open()) {
                    lastError = w.describe();
                    return null;
                }
                pad = w;
            } else {
                VirtualPad v = new VirtualPad();
                if (!v.create(target, VirtualPad.nameFor(player))) {
                    lastError = v.describe();
                    return slots.isEmpty() ? null : slots.get(0);
                }
                pad = v;
            }
            TargetEncoder enc = new TargetEncoder(target, events -> {
                pad.write(events);
                written.addAndGet(events.length / 3);
            });
            slots.add(new Slot(player, pad, enc));
            Log.i(TAG, "Created " + pad.name() + " cloning " + target);
        }
        return slots.get(index);
    }

    // ---------------------------------------------------------------- shortcuts

    private volatile List<Shortcut> shortcuts = new ArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DockPad-timer");
        t.setDaemon(true);
        return t;
    });

    void setTouchpadAsButton(boolean on) {
        touchpadAsButton = on;
        lastDevList = ""; // rescan
    }

    private volatile Map<String, Map<Integer, Integer>> layouts = new java.util.HashMap<>();

    void setLayouts(String spec) {
        Map<String, Map<Integer, Integer>> m = new java.util.HashMap<>();
        for (ControllerLayout l : ControllerLayout.decodeAll(spec).values()) m.put(l.device, l.table());
        layouts = m;
    }

    void setShortcuts(String spec) {
        shortcuts = Shortcut.decodeAll(spec);
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.rebuildEngine();
    }

    // combo recording
    private final Object captureLock = new Object();
    private volatile boolean capturing;
    private volatile boolean captureRaw;
    private String captureDevice, captured;
    private final List<Integer> captureOrder = new ArrayList<>();
    private final Set<Integer> captureHeld = new HashSet<>();

    /**
     * Records the next button or combination: everything pressed on one controller until all
     * buttons are released again. Returns "deviceName\tcode,code" or "" on timeout.
     */
    String captureCombo(int timeoutMs, boolean raw) throws InterruptedException {
        synchronized (captureLock) {
            captureRaw = raw;
            captured = null;
            captureDevice = null;
            captureOrder.clear();
            captureHeld.clear();
            capturing = true;
            long end = System.currentTimeMillis() + timeoutMs;
            while (capturing && captured == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                captureLock.wait(left);
            }
            capturing = false;
            return captured == null ? "" : captured;
        }
    }

    private boolean captureEvent(String device, int code, boolean down) {
        if (!capturing) return false;
        synchronized (captureLock) {
            if (!capturing) return false;
            if (captureDevice != null && !captureDevice.equals(device)) return false;
            if (down) {
                captureDevice = device;
                if (!captureOrder.contains(code) && captureOrder.size() < 4) captureOrder.add(code);
                captureHeld.add(code);
                return true;
            }
            if (!captureHeld.remove(code)) return false;
            if (captureHeld.isEmpty() && !captureOrder.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (int c : captureOrder) b.append(b.length() == 0 ? "" : ",").append(c);
                captured = device + "\t" + b;
                capturing = false;
                captureLock.notifyAll();
            }
            return true;
        }
    }

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
        for (Slot sl : slots) sl.pad.close();
        slots.clear();
    }

    private boolean padsAlive() {
        for (Slot sl : slots) if (!sl.pad.isAlive()) return false;
        return !slots.isEmpty();
    }

    boolean isRunning() {
        return running;
    }

    synchronized String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", running);
            o.put("builtIn", target == null ? JSONObject.NULL : target.name);
            o.put("multiplayer", multiplayer);
            o.put("error", lastError);
            JSONArray players = new JSONArray();
            for (Slot sl : slots) {
                JSONObject p = new JSONObject();
                p.put("player", sl.player);
                p.put("name", sl.pad.name());
                p.put("active", sl.pad.isAlive());
                p.put("detail", sl.pad.describe());
                players.put(p);
            }
            o.put("players", players);
            JSONArray ctrls = new JSONArray();
            for (Source s : sources.values()) {
                if (!s.alive) continue;
                JSONObject c = new JSONObject();
                c.put("name", s.dev.name);
                c.put("player", s.player);
                c.put("builtIn", target != null && s.dev.path.equals(target.path));
                c.put("touchpad", s.touchpad);
                c.put("exclusive", s.hideMethod.startsWith("yes"));
                c.put("lastButton", s.lastKey < 0 ? "" : Codes.keyName(s.lastKey));
                ctrls.put(c);
            }
            o.put("controllers", ctrls);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    synchronized String status() {
        StringBuilder b = new StringBuilder();
        b.append("Bridge: ").append(running ? "RUNNING" : "stopped").append('\n');
        EvdevDevice t = target;
        b.append("Built-in controls: ").append(t == null ? "not found" : t.name + " [" + t.id() + "]").append('\n');
        b.append("Multiplayer: ").append(multiplayer ? "on" : "off (everything is player 1)").append('\n');
        if (slots.isEmpty()) b.append("Virtual controller: not created\n");
        for (Slot sl : slots) {
            b.append("Player ").append(sl.player).append(": \"").append(sl.pad.name()).append("\" ")
                    .append(sl.pad.describe());
            InputDevice vd = findAndroidDeviceByName(sl.pad.name());
            if (vd != null) b.append(" (Android id ").append(vd.getId()).append(")");
            b.append('\n');
        }
        b.append("Physical controllers:\n");
        if (sources.isEmpty()) b.append("   none\n");
        for (Source s : sources.values()) {
            b.append("• ").append(s.dev.name).append(" [").append(s.dev.id()).append("] → Player ").append(s.player)
                    .append(s.alive ? "" : " (disconnected)").append('\n')
                    .append("    ").append(s.touchpad ? "touchpad (click = mappable button)"
                            : s.mapper == null ? "" : s.mapper.layoutDescription).append('\n')
                    .append("    exclusive: ").append(s.hideMethod)
                    .append(", events: ").append(s.eventsRead.get()).append('\n');
            if (s.lastKey >= 0) b.append("    last button: ").append(Codes.keyName(s.lastKey))
                    .append(String.format(" (0x%x)", s.lastKey)).append('\n');
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
                    if (!slots.isEmpty() && !padsAlive()) changed = true;
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
                if (d.vendor == k[0] && d.product == k[1] && d.isGamepad() && !VirtualPad.isOurs(d.name)) {
                    newTarget = d;
                    break outer;
                }
            }
        }

        synchronized (this) {
            if (!running) return;
            boolean targetChanged = newTarget == null || target == null || !newTarget.path.equals(target.path);
            if (targetChanged || !padsAlive()) {
                teardown();
                target = newTarget;
                if (newTarget == null) return;
                if (slot(0) == null) return; // player 1 must exist
            }

            Set<String> present = new HashSet<>();
            for (EvdevDevice d : devs) present.add(d.path);
            for (Source s : new ArrayList<>(sources.values())) {
                boolean unwanted = s.dev.path.equals(target.path) || (s.touchpad && !touchpadAsButton);
                if (!s.alive || !present.contains(s.dev.path) || unwanted) {
                    s.close();
                    sources.remove(s.dev.path);
                }
            }

            // new controllers in connection order (event number)
            List<EvdevDevice> fresh = new ArrayList<>();
            for (EvdevDevice d : devs) {
                boolean touchpad = touchpadAsButton && d.isControllerTouchpad();
                if (!(d.isGamepad() || touchpad) || VirtualPad.isOurs(d.name) || sources.containsKey(d.path)) continue;
                if (d.path.equals(target.path)) continue; // the built-in controls work natively
                fresh.add(d);
            }
            fresh.sort((a, b) -> Integer.compare(eventNumber(a.path), eventNumber(b.path)));

            for (int pass = 0; pass < 2; pass++) { // gamepads first, then their touchpads
                for (EvdevDevice d : fresh) {
                    boolean touchpad = d.isControllerTouchpad();
                    if ((pass == 0) == touchpad) continue;
                    int index;
                    if (d.path.equals(target.path)) index = 0;
                    else if (touchpad) index = parentPlayerIndex(d);
                    else index = nextFreePlayerIndex();
                    Slot sl = slot(index);
                    if (sl == null) continue;
                    Source s = new Source(d, sl.encoder, sl.player);
                    sources.put(d.path, s);
                    s.start();
                }
            }
        }
    }

    private static int eventNumber(String path) {
        try {
            return Integer.parseInt(path.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lowest player slot not used by another external controller (player 1 is shared with the built-in controls). */
    private int nextFreePlayerIndex() {
        if (!multiplayer) return 0;
        Set<Integer> used = new HashSet<>();
        for (Source s : sources.values()) {
            if (s.touchpad) continue;
            used.add(s.player - 1);
        }
        for (int i = 0; i < MAX_PLAYERS; i++) if (!used.contains(i)) return i;
        return MAX_PLAYERS - 1;
    }

    /** A controller's touchpad belongs to the same player as the controller itself. */
    private int parentPlayerIndex(EvdevDevice touchpad) {
        Source best = null;
        int n = eventNumber(touchpad.path);
        for (Source s : sources.values()) {
            if (s.touchpad || !touchpad.name.startsWith(s.dev.name)) continue;
            int sn = eventNumber(s.dev.path);
            if (sn < n && (best == null || sn > eventNumber(best.dev.path))) best = s;
        }
        return best == null ? 0 : best.player - 1;
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
        final int player;
        volatile Mapper mapper;
        final boolean touchpad;
        volatile boolean alive = true;
        volatile String hideMethod = "pending";
        final AtomicLong eventsRead = new AtomicLong();
        private FileInputStream in;
        private int disabledAndroidId = -1;
        volatile int lastKey = -1;
        private final Set<Integer> swallowed = new HashSet<>();
        private ShortcutEngine engine;

        Source(EvdevDevice dev, TargetEncoder sink, int player) {
            this.dev = dev;
            this.sink = sink;
            this.player = player;
            this.touchpad = dev.isControllerTouchpad();
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
                synchronized (this) {
                    mapper = new Mapper(dev, sink);
                    buildEngine();
                }
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
                        synchronized (this) {
                            if (!alive) break;
                            if (type == Codes.EV_KEY) {
                                // raw recording (for changing this controller's buttons)
                                if (value != 2 && captureRaw && captureEvent(dev.name, code, value != 0)) {
                                    if (value != 0) swallowed.add(code);
                                    else swallowed.remove(code);
                                    continue;
                                }
                                Map<Integer, Integer> lay = layouts.get(dev.name);
                                if (lay != null && !touchpad) {
                                    Integer to = lay.get(code);
                                    if (to != null) {
                                        if (to < 0) continue; // disabled
                                        code = to;
                                    }
                                }
                                if (handleKey(code, value)) continue;
                            }
                            mapper.onEvent(type, code, value);
                        }
                    }
                }
            } catch (Throwable t) {
                if (running && alive) error(dev.name, t);
            } finally {
                close();
            }
        }

        private void buildEngine() {
            engine = new ShortcutEngine(shortcuts, dev.name, new ShortcutEngine.Out() {
                @Override
                public void output(int code, boolean down) {
                    if (Actions.isAction(code)) {
                        if (down) Actions.run(code);
                        return;
                    }
                    sink.rawKey(code, down);
                }

                @Override
                public void forward(int code, boolean down) {
                    if (mapper != null) mapper.onEvent(Codes.EV_KEY, code, down ? 1 : 0);
                }
            }, (r, ms) -> {
                ScheduledFuture<?> f = timer.schedule(() -> {
                    synchronized (Source.this) {
                        if (alive) r.run();
                    }
                }, ms, TimeUnit.MILLISECONDS);
                return () -> f.cancel(false);
            });
        }

        synchronized void rebuildEngine() {
            if (!alive || mapper == null) return;
            if (engine != null) engine.releaseAll();
            buildEngine();
        }

        /** Combo recording and shortcuts. Returns true if the key must not reach the game. */
        private boolean handleKey(int code, int value) {
            // A touchpad only offers its click as a button; touches and finger counts are ignored.
            if (touchpad && code != Codes.BTN_LEFT) return true;
            if (value == 1) lastKey = code;
            if (value == 2) return true; // key repeat: never needed
            boolean down = value != 0;
            if (!captureRaw && captureEvent(dev.name, code, down)) {
                if (down) swallowed.add(code);
                else swallowed.remove(code);
                return true;
            }
            if (swallowed.contains(code)) {
                if (!down) swallowed.remove(code);
                return true;
            }
            if (engine != null && engine.onKey(code, down)) return true;
            return touchpad; // an unmapped touchpad click does nothing
        }

        synchronized void close() {
            boolean wasAlive = alive;
            alive = false;
            if (engine != null) {
                try {
                    engine.releaseAll();
                } catch (Throwable ignored) {
                }
            }
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
