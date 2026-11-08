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

    // ---------------------------------------------------------------- rumble pass-through

    private volatile boolean rumbleOn = true;
    private volatile String handheldWatch = "not started";
    private volatile boolean p1Rumbling;
    private ScheduledFuture<?> p1Pending;

    void setRumble(boolean on) {
        rumbleOn = on;
        if (!on) {
            for (int p = 1; p <= MAX_PLAYERS; p++) rumblePlayer(p, false);
        }
    }

    /** Sends rumble to every real controller playing as this player. */
    private void rumblePlayer(int player, boolean on) {
        if (on && (!rumbleOn || !running)) return;
        List<Source> list = new ArrayList<>();
        synchronized (this) {
            for (Source s : sources.values()) {
                boolean isBuiltIn = target != null && s.dev.path.equals(target.path);
                if (s.alive && s.joined && !s.touchpad && !isBuiltIn && s.player == player) list.add(s);
            }
        }
        for (Source s : list) Rumble.set(s.dev, on);
    }

    /** Player 1's rumble: games use the handheld's own motor, so copy it (ignoring tiny UI clicks). */
    private synchronized void handheldVibrating(boolean on) {
        if (p1Pending != null) {
            p1Pending.cancel(false);
            p1Pending = null;
        }
        if (on) {
            p1Pending = timer.schedule(() -> {
                p1Rumbling = true;
                rumblePlayer(1, true);
            }, 40, TimeUnit.MILLISECONDS);
        } else if (p1Rumbling) {
            p1Rumbling = false;
            timer.execute(() -> rumblePlayer(1, false));
        }
    }

    // press A to join: new controllers wait (and do nothing) until they press a button
    private volatile boolean joinByPress = false;

    void setJoinByPress(boolean on) {
        if (joinByPress != on) {
            joinByPress = on;
            synchronized (this) {
                for (Source s : sources.values()) s.close();
                sources.clear();
            }
            lastDevList = "";
        }
    }

    /** Disconnects a Bluetooth controller. Returns "" on success, else why not. */
    String disconnectController(String name) {
        String err = Bt.disconnect(name);
        return err == null ? "" : err;
    }

    /** Gives a waiting controller the next free player. */
    private void join(Source src) {
        synchronized (this) {
            if (!running || !src.alive || src.joined) return;
            Slot sl = slot(nextFreePlayerIndex());
            if (sl == null) return;
            src.joinAs(sl);
            lastDevList = ""; // its touchpad (if any) can follow now
        }
        Actions.toast("Player " + src.player + ": " + src.dev.name);
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
                final int pl = player;
                v.onRumble(on -> rumblePlayer(pl, on)); // games can rumble players 2+ like any controller
                if (!v.create(target, VirtualPad.nameFor(player))) {
                    lastError = v.describe();
                    return slots.isEmpty() ? null : slots.get(0);
                }
                matchButtons(v, target);
                pad = v;
            }
            TargetEncoder enc = new TargetEncoder(target, events -> {
                pad.write(events);
                written.addAndGet(events.length / 3);
            });
            enc.setDpadForm(dpadForm);
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

    // ---------------------------------------------------------------- battery

    private volatile Map<String, Battery.Level> batteries = new java.util.HashMap<>();
    private final java.util.Set<String> warnedLow = new java.util.HashSet<>();
    private Thread batteryThread;
    private volatile boolean batteryDirty = true;

    private void startBatteryWatch() {
        if (batteryThread != null) return;
        batteryThread = new Thread(() -> {
            while (true) {
                try {
                    // every 30 s, or soon after a controller connects
                    for (int i = 0; i < 30 && !batteryDirty; i++) Thread.sleep(1000);
                    if (batteryDirty) Thread.sleep(2000); // let it settle
                    batteryDirty = false;
                    if (running) refreshBatteries();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "battery", t);
                }
            }
        }, "DockPad-battery");
        batteryThread.setDaemon(true);
        batteryThread.start();
    }

    private void refreshBatteries() {
        List<String> names = new ArrayList<>();
        synchronized (this) {
            for (Source s : sources.values()) {
                boolean isBuiltIn = target != null && s.dev.path.equals(target.path);
                if (s.alive && !s.touchpad && !isBuiltIn && !names.contains(s.dev.name)) names.add(s.dev.name);
            }
        }
        if (names.isEmpty()) {
            batteries = new java.util.HashMap<>();
            return;
        }
        Map<String, Battery.Level> now = Battery.read(names);
        batteries = now;
        for (Map.Entry<String, Battery.Level> e : now.entrySet()) {
            Battery.Level l = e.getValue();
            if (l.percent >= 0 && l.percent <= 15 && !l.charging) {
                if (warnedLow.add(e.getKey())) Actions.toast(e.getKey() + ": battery low (" + l.percent + "%)");
            } else if (l.percent > 25 || l.charging) {
                warnedLow.remove(e.getKey());
            }
        }
    }

    void setTouchpadAsButton(boolean on) {
        touchpadAsButton = on;
        lastDevList = ""; // rescan
    }

    private volatile Map<String, Map<Integer, Integer>> layouts = new java.util.HashMap<>();

    void setLayouts(String spec) {
        Map<String, Map<Integer, Integer>> m = new java.util.HashMap<>();
        Map<String, ControllerLayout> t = new java.util.HashMap<>();
        for (ControllerLayout l : ControllerLayout.decodeAll(spec).values()) {
            m.put(l.device, l.table());
            if (!l.turbo.isEmpty()) t.put(l.device, l);
        }
        Map<String, com.adonzin.dockpad.core.Tuning> tu = new java.util.HashMap<>();
        for (ControllerLayout l : ControllerLayout.decodeAll(spec).values()) {
            if (!l.tuning.isDefault()) tu.put(l.device, l.tuning);
        }
        layouts = m;
        turbos = t;
        tunings = tu;
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.applyTuning();
    }

    private volatile Map<String, com.adonzin.dockpad.core.Tuning> tunings = new java.util.HashMap<>();

    // ---------------------------------------------------------------- rapid fire

    private volatile Map<String, ControllerLayout> turbos = new java.util.HashMap<>();
    private volatile boolean turboOn = true;

    boolean isTurboOn() {
        return turboOn;
    }

    void setTurboOn(boolean on) {
        turboOn = on;
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.stopTurbo();
        Log.i(TAG, "Rapid fire " + (on ? "on" : "off"));
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
        startBatteryWatch();
        lastDevList = "";
        // lets the app draw DockPad's keyboard over other apps
        Actions.exec("appops", "set", com.adonzin.dockpad.BuildConfig.APPLICATION_ID, "SYSTEM_ALERT_WINDOW", "allow");
        String watch = Rumble.watchHandheld(this::handheldVibrating);
        handheldWatch = watch == null ? "watching" : "unavailable (" + watch + ")";
        // lets the app search for and pair controllers without a permission prompt
        for (String perm : new String[]{"BLUETOOTH_SCAN", "BLUETOOTH_CONNECT"}) {
            Actions.exec("pm", "grant", com.adonzin.dockpad.BuildConfig.APPLICATION_ID, "android.permission." + perm);
        }
        scanner = new Thread(this::scanLoop, "DockPad-scan");
        scanner.setDaemon(true);
        scanner.start();
        return "started";
    }

    synchronized void stop() {
        running = false;
        if (scanner != null) scanner.interrupt();
        pcModeOn = false;
        keyboardOpen = false;
        pc.setActive(false);
        teardown();
        extras.close();
    }

    // ---------------------------------------------------------------- PC control mode

    // ---------------------------------------------------------------- d-pad form

    private static final java.io.File DPAD_FILE = new java.io.File("/data/local/tmp/dockpad_dpad_form");
    private volatile int dpadForm = readDpadForm();
    private volatile int dpadSeen;

    private static int readDpadForm() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(DPAD_FILE)) {
            int v = in.read() - '0';
            return v >= 1 && v <= 3 ? v : TargetEncoder.DPAD_HAT;
        } catch (Exception e) {
            return TargetEncoder.DPAD_HAT;
        }
    }

    /** Watches the built-in controls' d-pad to learn whether it reports buttons, a hat or both. */
    private void observeBuiltInDpad(int type, int code, int value) {
        int seen = dpadSeen;
        if (type == Codes.EV_KEY && code >= Codes.BTN_DPAD_UP && code <= Codes.BTN_DPAD_RIGHT && value == 1) {
            seen |= TargetEncoder.DPAD_BUTTONS;
        } else if (type == Codes.EV_ABS && (code == Codes.ABS_HAT0X || code == Codes.ABS_HAT0Y) && value != 0) {
            seen |= TargetEncoder.DPAD_HAT;
        } else {
            return;
        }
        if (seen == dpadSeen) return;
        dpadSeen = seen;
        if (seen != dpadForm) {
            dpadForm = seen;
            synchronized (this) {
                for (Slot sl : slots) sl.encoder.setDpadForm(seen);
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(DPAD_FILE)) {
                o.write('0' + seen);
            } catch (Exception ignored) {
            }
            Log.i(TAG, "Built-in d-pad form: " + seen);
        }
    }

    private final Extras extras = new Extras();

    // ---------------------------------------------------------------- on-screen keyboard

    private final KeyboardInput keyboardInput = new KeyboardInput();
    private volatile boolean keyboardOpen;
    private volatile long keyboardLastPoll;

    {
        timer.scheduleAtFixedRate(() -> {
            if (!keyboardOpen) return;
            keyboardInput.tick();
            // the keyboard app stopped asking (closed or killed): give the controllers back
            if (System.currentTimeMillis() - keyboardLastPoll > 4000) setKeyboardOpen(false);
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    boolean isKeyboardOpen() {
        return keyboardOpen;
    }

    /** While open, controllers drive DockPad's keyboard instead of the game. */
    void setKeyboardOpen(boolean on) {
        if (on == keyboardOpen || (on && !running)) return;
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.modeChanged();
        keyboardInput.reset();
        keyboardLastPoll = System.currentTimeMillis();
        keyboardOpen = on;
        if (on) extras.keyboard(); // make sure the virtual keyboard exists before typing
        lastDevList = ""; // the built-in controls are taken over while it's open: rescan
    }

    /** Navigation events since the last call ("up,a,…"), or "closed". */
    String pollKeyboard() {
        if (!keyboardOpen) return "closed";
        keyboardLastPoll = System.currentTimeMillis();
        return keyboardInput.drain();
    }

    /** Presses the keys in order and lets go in reverse, e.g. "29,46" = Ctrl+C. */
    void typeCombo(String csv) {
        List<Integer> codes = new ArrayList<>();
        for (String p : csv.split(",")) {
            try {
                codes.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        for (int c : codes) extras.key(c, true);
        Extras.sleep(25);
        for (int i = codes.size() - 1; i >= 0; i--) extras.key(codes.get(i), false);
    }

    void typeKey(int code, boolean shift) {
        if (shift) extras.key(42, true);
        extras.tap(code);
        if (shift) extras.key(42, false);
    }

    private boolean takeOverBuiltIn() {
        return pcModeOn || keyboardOpen;
    }
    private final PcMode pc = new PcMode(extras);
    private volatile boolean pcModeOn;

    {
        Actions.bridge = this;
    }

    Extras extras() {
        return extras;
    }

    boolean isPcMode() {
        return pcModeOn;
    }

    void setPcMode(boolean on) {
        if (on == pcModeOn || (on && !running)) return;
        pcModeOn = on;
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.modeChanged(); // let go of anything held in the other mode
        pc.setActive(on);
        lastDevList = ""; // the built-in controls are taken over only in PC mode: rescan
        Log.i(TAG, "Cursor mode " + (on ? "on" : "off"));
    }

    /**
     * Runs a command from the quick menu (one action, or a macro) after a short delay, so it
     * reaches the game rather than the menu, which is closing.
     */
    void runCommand(String spec, int delayMs) {
        Shortcut c = Shortcut.decode(spec);
        if (c == null) return;
        long t = Math.max(0, delayMs);
        for (int[] step : c.steps) {
            final int[] st = step;
            timer.schedule(() -> {
                for (int o : st) commandOutput(o, true, c.param);
            }, t, TimeUnit.MILLISECONDS);
            t += 60;
            timer.schedule(() -> {
                for (int o : st) commandOutput(o, false, c.param);
            }, t, TimeUnit.MILLISECONDS);
            t += 40;
        }
    }

    /**
     * Once Android has set the new virtual controller up: if it gave it a different button layout
     * file than the built-in controls (so a raw code would be Y on one and X on the other), re-code
     * its buttons to match. Without this, players 2+ could find X/Y (or more) swapped in games and menus.
     */
    private void matchButtons(VirtualPad v, EvdevDevice builtIn) {
        timer.schedule(new Runnable() {
            int tries;

            @Override
            public void run() {
                if (!running || !v.isAlive()) return;
                try {
                    String dump = Actions.exec("dumpsys", "input");
                    String bf = KeyLayouts.layoutFile(dump, builtIn.path, builtIn.name);
                    String pf = KeyLayouts.layoutFile(dump, null, v.name());
                    if (bf == null || pf == null) {
                        if (++tries < 8) timer.schedule(this, 700, TimeUnit.MILLISECONDS);
                        else v.setRemap(java.util.Collections.emptyMap(), "button layout not found");
                        return;
                    }
                    String names = new File(pf).getName() + " vs built-in " + new File(bf).getName();
                    if (bf.equals(pf)) {
                        v.setRemap(java.util.Collections.emptyMap(), "buttons: same layout as built-in");
                        return;
                    }
                    Map<Integer, Integer> m = KeyLayouts.remap(KeyLayouts.parse(bf), KeyLayouts.parse(pf),
                            builtIn.keys, VirtualPad.keyCodes(builtIn));
                    v.setRemap(m, m.isEmpty() ? "buttons already match (" + names + ")"
                            : m.size() + " button" + (m.size() == 1 ? "" : "s") + " re-coded to match (" + names + ")");
                    Log.i(TAG, v.name() + ": " + v.layoutNote + " " + m);
                } catch (Throwable t) {
                    v.setRemap(java.util.Collections.emptyMap(), "button check failed: " + t);
                }
            }
        }, 700, TimeUnit.MILLISECONDS);
    }

    /** Presses a key on the built-in controls (e.g. Home/Back). False if it can't right now. */
    boolean tapBuiltInKey(int evdevCode) {
        EvdevDevice t = target;
        if (pcModeOn || t == null || !t.hasKey(evdevCode)) return false;
        TargetEncoder enc;
        synchronized (this) {
            enc = slots.isEmpty() ? null : slots.get(0).encoder;
        }
        if (enc == null) return false;
        enc.rawKey(evdevCode, true);
        Extras.sleep(50);
        enc.rawKey(evdevCode, false);
        return true;
    }

    // face-button swap for all external controllers (quick menu / shortcuts)
    private volatile boolean swapAB, swapXY;

    void toggleSwap(boolean ab, boolean xy) {
        List<Source> list;
        synchronized (this) {
            list = new ArrayList<>(sources.values());
        }
        for (Source s : list) s.modeChanged(); // let go of held buttons first
        if (ab && xy) {
            boolean on = !(swapAB && swapXY);
            swapAB = on;
            swapXY = on;
        } else {
            if (ab) swapAB = !swapAB;
            if (xy) swapXY = !swapXY;
        }
        Log.i(TAG, "Face swap: A/B " + swapAB + ", X/Y " + swapXY);
    }

    private int applySwap(int code) {
        if (swapAB) {
            if (code == Codes.BTN_SOUTH) return Codes.BTN_EAST;
            if (code == Codes.BTN_EAST) return Codes.BTN_SOUTH;
        }
        if (swapXY) {
            if (code == Codes.BTN_NORTH) return Codes.BTN_WEST;
            if (code == Codes.BTN_WEST) return Codes.BTN_NORTH;
        }
        return code;
    }

    private void commandOutput(int code, boolean down, String param) {
        if (Codes.isAction(code)) {
            if (down) Actions.run(code, param, null);
        } else if (Codes.isKeyboardOutput(code)) {
            extras.key(code - Codes.KB, down);
        } else {
            TargetEncoder enc;
            synchronized (this) {
                enc = slots.isEmpty() ? null : slots.get(0).encoder;
            }
            if (enc != null) enc.rawKey(code, down);
        }
    }

    /** "speed,pointerOnLeft" e.g. "1.0,false" */
    void setPcSettings(String spec) {
        try {
            String[] f = spec.split(",");
            pc.settings(Float.parseFloat(f[0]), f.length > 1 && Boolean.parseBoolean(f[1]));
        } catch (Exception ignored) {
        }
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
            o.put("builtInHow", builtInHow);
            o.put("multiplayer", multiplayer);
            o.put("pcMode", pcModeOn);
            o.put("swapAB", swapAB);
            o.put("swapXY", swapXY);
            o.put("keyboard", keyboardOpen);
            o.put("turbo", turboOn);
            o.put("turboConfigured", !turbos.isEmpty());
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
                Battery.Level bl = batteries.get(s.dev.name);
                if (bl != null && !s.touchpad) {
                    c.put("battery", bl.percent);
                    c.put("charging", bl.charging);
                }
                ctrls.put(c);
            }
            o.put("controllers", ctrls);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** Which input devices the kernel says can rumble (for working out rumble pass-through). */
    static String rumbleReport() {
        StringBuilder b = new StringBuilder("Rumble (kernel): ");
        try {
            int n = 0;
            for (EvdevDevice d : EvdevDevice.parse(run("getevent", "-i"))) {
                if (d.ff.isEmpty()) continue;
                n++;
                b.append(d.name).append(" [").append(d.id()).append(", ").append(d.path).append("] ")
                        .append(d.ff.contains(0x50) ? "rumble" : "effects " + d.ff).append("; ");
            }
            if (n == 0) b.append("no device accepts rumble");
            b.append("\nRumble devices (all): ");
            for (EvdevDevice d : EvdevDevice.parse(run("getevent", "-i"))) {
                b.append(d.path.replace("/dev/input/", "")).append(' ').append(d.name).append(" [").append(d.id())
                        .append(d.ff.isEmpty() ? "" : ", FF").append(d.isGamepad() ? ", pad" : "").append("]; ");
            }
            b.append("\nRumble (Android): ");
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) continue;
                int motors = 0;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 31) motors = d.getVibratorManager().getVibratorIds().length;
                } catch (Throwable ignored) {
                }
                b.append(d.getName()).append(String.format(" [%04x:%04x", d.getVendorId(), d.getProductId()))
                        .append(d.isEnabled() ? "" : ", disabled").append(motors > 0 ? ", " + motors + " motors" : "")
                        .append("]; ");
            }
        } catch (Throwable t) {
            b.append(t);
        }
        b.append('\n');
        try {
            String v = Actions.exec("dumpsys", "vibrator_manager");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)vibratorIds:?\\s*\\[?([0-9, ]*)").matcher(v);
            b.append("Handheld vibrator: ").append(v.trim().isEmpty() ? "no report"
                    : m.find() ? "ids " + m.group(1).trim() : "present").append('\n');
        } catch (Throwable t) {
            b.append("Handheld vibrator: ").append(t).append('\n');
        }
        return b.toString();
    }

    synchronized String status() {
        StringBuilder b = new StringBuilder();
        b.append("Bridge: ").append(running ? "RUNNING" : "stopped").append('\n');
        EvdevDevice t = target;
        b.append("Built-in controls: ").append(t == null ? "not found" : t.name + " [" + t.id() + "]").append('\n');
        b.append("Cursor mode: ").append(pcModeOn ? "ON" : "off").append('\n');
        b.append("D-pad sent as: ").append(dpadForm == 1 ? "buttons" : dpadForm == 2 ? "hat" : "buttons + hat")
                .append(dpadSeen == 0 ? " (not learned yet: press the built-in d-pad once)" : " (learned)").append('\n');
        b.append("Battery: ");
        if (batteries.isEmpty()) b.append("none reported");
        for (Map.Entry<String, Battery.Level> e : batteries.entrySet()) {
            b.append(e.getKey()).append(' ').append(e.getValue().percent).append('%')
                    .append(e.getValue().charging ? " charging" : "").append("; ");
        }
        b.append('\n');
        try {
            StringBuilder bt = new StringBuilder();
            for (String[] d : Bt.connectedDevices()) {
                bt.append(d[0]).append(" (").append(d[2].equals("-1") ? "no level" : d[2] + "%").append(d[3].equals("1") ? ", controller" : "")
                        .append("); ");
            }
            b.append("Bluetooth connected: ").append(bt.length() == 0 ? "none found" : bt).append('\n');
            b.append(Battery.describe()).append('\n');
            b.append(rumbleReport());
            b.append("Rumble pass-through: ").append(rumbleOn ? "on" : "off").append("; handheld motor: ")
                    .append(handheldWatch).append("; controllers: ");
            List<Source> list = new ArrayList<>(sources.values());
            for (Source s : list) {
                if (s.touchpad || (target != null && s.dev.path.equals(target.path))) continue;
                b.append(s.dev.name).append(Rumble.canRumble(s.dev) ? " (can rumble)" : " (no rumble)").append("; ");
            }
            b.append('\n');
        } catch (Throwable btErr) {
            b.append("Bluetooth: ").append(btErr).append('\n');
        }
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
            b.append("• ").append(s.dev.name).append(" [").append(s.dev.id()).append("] → ").append(s.joined ? "Player " + s.player : "waiting (press A to join)")
                    .append(s.alive ? "" : " (disconnected)").append('\n')
                    .append("    ").append(s.touchpad ? "touchpad (click = mappable button)"
                            : s.mapper == null ? "" : s.mapper.layoutDescription).append('\n')
                    .append("    exclusive: ").append(s.hideMethod)
                    .append(", events: ").append(s.eventsRead.get()).append('\n');
            if (!s.builtIn && !s.touchpad && s.joined) b.append("    ").append(s.latency.summary())
                    .append(s.fastThread ? "" : " · priority not raised").append('\n');
            if (s.lastKey >= 0) b.append("    last button: ").append(Codes.keyName(s.lastKey))
                    .append(String.format(" (0x%x)", s.lastKey)).append('\n');
        }
        b.append("Events sent to virtual controller: ").append(written.get()).append('\n');
        if (!lastError.isEmpty()) b.append("Last error: ").append(lastError).append('\n');
        return b.toString();
    }

    // ---------------------------------------------------------------- scanning

    private final Object scanWake = new Object();
    @SuppressWarnings("deprecation")
    private android.os.FileObserver devWatch;

    /**
     * Watches for controllers appearing, so DockPad takes a new one over at once (games only get
     * the moment between its arrival and then to see it themselves).
     */
    @SuppressWarnings("deprecation")
    private void watchDevices() {
        try {
            devWatch = new android.os.FileObserver("/dev/input",
                    android.os.FileObserver.CREATE | android.os.FileObserver.DELETE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || !path.startsWith("event")) return;
                    timer.schedule(() -> {
                        synchronized (scanWake) {
                            scanWake.notifyAll();
                        }
                    }, 60, TimeUnit.MILLISECONDS); // let the system set up the device's permissions
                }
            };
            devWatch.startWatching();
        } catch (Throwable t) {
            devWatch = null; // the regular check every 1.5 s still finds it
        }
    }

    private void scanLoop() {
        watchDevices();
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
                synchronized (scanWake) {
                    scanWake.wait(1500);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
        if (devWatch != null) devWatch.stopWatching();
    }

    // ---------------------------------------------------------------- built-in controls

    private volatile String[] builtInChoice; // name, vendor, product chosen by the user (null = automatic)
    private volatile String builtInHow = "";

    void setBuiltIn(String spec) {
        String[] f = spec == null ? new String[0] : spec.split("\t");
        builtInChoice = f.length == 3 ? f : null;
        lastDevList = ""; // rescan
    }

    /**
     * Finds the built-in controls: the user's choice if set, else a known device, else the
     * gamepad that looks most like it's part of the device (not Bluetooth, not removable).
     */
    private EvdevDevice pickBuiltIn(List<EvdevDevice> devs) {
        String[] choice = builtInChoice;
        if (choice != null) {
            for (EvdevDevice d : devs) {
                if (d.isGamepad() && !VirtualPad.isOurs(d.name) && d.name.equals(choice[0])
                        && String.valueOf(d.vendor).equals(choice[1]) && String.valueOf(d.product).equals(choice[2])) {
                    builtInHow = "chosen";
                    return d;
                }
            }
        }
        for (int[] k : KNOWN_BUILT_IN) {
            for (EvdevDevice d : devs) {
                if (d.vendor == k[0] && d.product == k[1] && d.isGamepad() && !VirtualPad.isOurs(d.name)) {
                    builtInHow = "known";
                    return d;
                }
            }
        }
        EvdevDevice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (EvdevDevice d : devs) {
            if (!d.isGamepad() || VirtualPad.isOurs(d.name)) continue;
            int score = 0;
            if (d.bus == 0x05) score -= 100; // Bluetooth: never built in
            if (d.bus == 0x18 || d.bus == 0x19 || d.bus == 0x06 || d.bus == 0x1c) score += 30; // I2C, host, virtual, SPI
            InputDevice ad = findAndroidDevice(d);
            if (ad != null && !ad.isExternal()) score += 50;
            score -= eventNumber(d.path) / 10; // created early (at boot) is more likely built in
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        if (best != null && bestScore >= 25) {
            builtInHow = "detected";
            return best;
        }
        builtInHow = "";
        return null;
    }

    // ---------------------------------------------------------------- "Check buttons"

    private volatile StringBuffer recording;

    /**
     * Lists everything every input device reports for a few seconds: devices DockPad uses (it
     * holds them, so only it can see their events) and every other device (via getevent).
     */
    String recordAllInput(int ms) throws Exception {
        StringBuilder out = new StringBuilder();
        List<EvdevDevice> devs = EvdevDevice.parse(run("getevent", "-i"));
        Map<String, String> names = new java.util.HashMap<>();
        out.append("Input devices:\n");
        for (EvdevDevice d : devs) {
            names.put(d.path, d.name);
            out.append("  ").append(d.path).append("  ").append(d.name).append(" [").append(d.id()).append("]")
                    .append(d.isGamepad() ? " gamepad" : d.isKeyboard() ? " keyboard" : "").append('\n');
        }
        StringBuffer rec = new StringBuffer();
        recording = rec;
        java.lang.Process p = new ProcessBuilder("getevent", "-l").redirectErrorStream(true).start();
        StringBuilder raw = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("EV_SYN") || line.startsWith("add device") || line.startsWith("  name:")
                            || line.startsWith("could not")) continue;
                    synchronized (raw) {
                        raw.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(ms);
        recording = null;
        p.destroy();
        reader.join(500);

        out.append("\nEvents from controllers DockPad is using:\n");
        out.append(rec.length() == 0 ? "  (none)\n" : rec.toString());
        out.append("\nEvents from every other device:\n");
        String other;
        synchronized (raw) {
            other = raw.toString();
        }
        if (other.trim().isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (String line : other.split("\n")) {
                int colon = line.indexOf(':');
                String path = colon > 0 ? line.substring(0, colon).trim() : "";
                String name = names.get(path);
                out.append("  ").append(name == null ? path : name).append(" —").append(colon > 0 ? line.substring(colon + 1) : line).append('\n');
            }
        }
        return out.toString();
    }

    private void recordEvent(String device, int type, int code, int value) {
        StringBuffer r = recording;
        if (r == null || type == Codes.EV_SYN) return;
        if (type == Codes.EV_ABS && Math.abs(value) < 2 && code < 0x10) return; // stick noise
        r.append("  ").append(device).append(" — ")
                .append(type == Codes.EV_KEY ? "button " + Codes.keyName(code) + String.format(" (0x%x)", code)
                        + (value == 1 ? " pressed" : value == 0 ? " released" : " held")
                        : type == Codes.EV_ABS ? String.format("axis 0x%x = %d", code, value)
                        : String.format("type %d code 0x%x value %d", type, code, value))
                .append('\n');
    }

    // "press a button on the built-in controls"
    private final Object identifyLock = new Object();
    private volatile boolean identifying;
    private String identified;

    private void offerIdentify(EvdevDevice d) {
        if (!identifying) return;
        synchronized (identifyLock) {
            if (!identifying || identified != null) return;
            identified = d.name + "\t" + d.vendor + "\t" + d.product;
            identifyLock.notifyAll();
        }
    }

    /** Waits for a button press on any controller; returns "name\tvendor\tproduct" or "". */
    String identifyController(int timeoutMs) throws Exception {
        List<EvdevDevice> devs = EvdevDevice.parse(run("getevent", "-i"));
        List<FileInputStream> streams = new ArrayList<>();
        synchronized (identifyLock) {
            identified = null;
            identifying = true;
        }
        try {
            Set<String> taken;
            synchronized (this) {
                taken = new HashSet<>(sources.keySet()); // already read by the bridge (they report too)
            }
            for (EvdevDevice d : devs) {
                if (!d.isGamepad() || VirtualPad.isOurs(d.name) || taken.contains(d.path)) continue;
                FileInputStream in = new FileInputStream(d.path);
                streams.add(in);
                Thread t = new Thread(() -> {
                    int size = Process.is64Bit() ? 24 : 16;
                    int off = size == 24 ? 16 : 8;
                    byte[] buf = new byte[size * 16];
                    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    try {
                        while (identifying) {
                            int n = in.read(buf);
                            if (n <= 0) return;
                            for (int p = 0; p + size <= n; p += size) {
                                int type = bb.getShort(p + off) & 0xffff;
                                int value = bb.getInt(p + off + 4);
                                if (type == Codes.EV_KEY && value == 1) offerIdentify(d);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }, "DockPad-identify");
                t.setDaemon(true);
                t.start();
            }
            synchronized (identifyLock) {
                long end = System.currentTimeMillis() + timeoutMs;
                while (identified == null) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    identifyLock.wait(left);
                }
                identifying = false;
                return identified == null ? "" : identified;
            }
        } finally {
            identifying = false;
            for (FileInputStream in : streams) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void rescan() throws IOException, InterruptedException {
        List<EvdevDevice> devs = EvdevDevice.parse(run("getevent", "-i"));
        EvdevDevice newTarget = pickBuiltIn(devs);

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
                boolean unwanted = (s.touchpad && !touchpadAsButton) || (s.builtIn && s.wantGrab != takeOverBuiltIn());
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
                fresh.add(d);
            }
            fresh.sort((a, b) -> Integer.compare(eventNumber(a.path), eventNumber(b.path)));

            for (int pass = 0; pass < 2; pass++) { // gamepads first, then their touchpads
                for (EvdevDevice d : fresh) {
                    boolean touchpad = d.isControllerTouchpad();
                    if ((pass == 0) == touchpad) continue;
                    boolean isBuiltIn = d.path.equals(target.path);
                    int index;
                    if (isBuiltIn) index = 0;
                    else if (touchpad) index = parentPlayerIndex(d);
                    else if (multiplayer && joinByPress) {
                        Source s = new Source(d, null, 0, false); // waits for a button press
                        sources.put(d.path, s);
                        s.start();
                        continue;
                    } else index = nextFreePlayerIndex();
                    if (index < 0) continue; // its controller hasn't joined yet
                    Slot sl = slot(index);
                    if (sl == null) continue;
                    Source s = new Source(d, sl.encoder, sl.player, isBuiltIn);
                    sources.put(d.path, s);
                    s.start();
                    batteryDirty = true;
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
            if (s.touchpad || s.builtIn || !s.joined) continue;
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
        if (best != null && !best.joined) return -1;
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
        volatile TargetEncoder sink;
        volatile int player;
        /** False while waiting for "press A to join". */
        volatile boolean joined;
        volatile Mapper mapper, pcMapper, kbMapper;
        final boolean touchpad;
        /** The built-in controls: only read (for shortcuts) normally, taken over in PC mode. */
        final boolean builtIn, wantGrab;
        volatile boolean alive = true;
        volatile String hideMethod = "pending";
        final AtomicLong eventsRead = new AtomicLong();
        final LatencyStats latency = new LatencyStats();
        volatile boolean fastThread;
        private FileInputStream in;
        private int disabledAndroidId = -1;
        volatile int lastKey = -1;
        private final Set<Integer> swallowed = new HashSet<>();
        private ShortcutEngine engine;

        Source(EvdevDevice dev, TargetEncoder sink, int player, boolean builtIn) {
            this.dev = dev;
            this.sink = sink;
            this.player = player;
            this.joined = sink != null;
            this.builtIn = builtIn;
            this.wantGrab = !builtIn || takeOverBuiltIn();
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
                // run ahead of the game's own threads, and stamp events so DockPad's delay can be measured
                fastThread = Hidden.raisePriority(0);
                latency.monotonic = Hidden.useMonotonicClock(in.getFD());
                String grabErr = wantGrab ? Hidden.grab(in.getFD()) : "not taken over";
                if (!wantGrab) {
                    hideMethod = "no (works as normal)";
                } else if (grabErr == null) {
                    hideMethod = "yes";
                } else {
                    InputDevice ad = findAndroidDevice(dev);
                    if (ad != null && Hidden.setDeviceEnabled(ad.getId(), false)) {
                        disabledAndroidId = ad.getId();
                        hideMethod = "yes (disabled)";
                    } else {
                        hideMethod = "NO (" + grabErr + ")";
                        // games could get this controller's input twice (from it and from DockPad): say so
                        Actions.toast(dev.name + ": DockPad couldn't take it over fully, so games may see its "
                                + "buttons twice. Reconnecting it usually fixes this.");
                    }
                }
                synchronized (this) {
                    if (joined) {
                        mapper = new Mapper(dev, sink);
                        if (!builtIn && !touchpad) mapper.setTuning(tunings.get(dev.name));
                    }
                    pcMapper = new Mapper(dev, pc.sink());
                    kbMapper = new Mapper(dev, keyboardInput.sink());
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
                        long stampUs = size == 24 ? bb.getLong(p) * 1_000_000L + bb.getLong(p + 8)
                                : (bb.getInt(p) & 0xffffffffL) * 1_000_000L + (bb.getInt(p + 4) & 0xffffffffL);
                        if (!joined) {
                            if (type == Codes.EV_KEY && value == 1 && isJoinButton(code)) join(this);
                            continue; // the press that joins doesn't reach the game
                        }
                        if (builtIn) observeBuiltInDpad(type, code, value);
                        if (recording != null && wantGrab) recordEvent(dev.name, type, code, value);
                        synchronized (this) {
                            if (!alive) break;
                            if (type == Codes.EV_KEY) {
                                if (identifying && value == 1) {
                                    offerIdentify(dev);
                                    continue;
                                }
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
                                if (!touchpad && !builtIn) code = applySwap(code);
                                if (handleKey(code, value)) continue;
                                if (turboKey(code, value)) continue;
                            }
                            deliver(type, code, value);
                            if (type == Codes.EV_SYN && code == 0 && !builtIn && !touchpad) latency.report(stampUs);
                        }
                    }
                }
            } catch (Throwable t) {
                // ENODEV just means the controller was disconnected
                // ENOENT: it went before DockPad could open it (Retroid's firmware swaps the first
                // Bluetooth controller's device for its own copy right after it connects)
                String msg = String.valueOf(t.getMessage());
                if (running && alive && !msg.contains("ENODEV") && !msg.contains("ENOENT")) error(dev.name, t);
            } finally {
                close();
            }
        }

        /** Sends an event to the game (as player N) or, in PC mode, to the mouse/keyboard. */
        private void deliver(int type, int code, int value) {
            if (keyboardOpen) {
                if (wantGrab) kbMapper.onEvent(type, code, value);
            } else if (pcModeOn) {
                if (wantGrab) pcMapper.onEvent(type, code, value);
            } else if (!builtIn) {
                mapper.onEvent(type, code, value); // the built-in controls already reach apps by themselves
            }
        }

        // rapid fire: per held button, a timer that keeps pressing and releasing it
        private final Map<Integer, ScheduledFuture<?>> turboTasks = new java.util.HashMap<>();

        private boolean turboKey(int code, int value) {
            if (builtIn || touchpad || keyboardOpen || pcModeOn) return false;
            ScheduledFuture<?> running = turboTasks.get(code);
            if (value == 0 && running != null) {
                running.cancel(false);
                turboTasks.remove(code);
                deliver(Codes.EV_KEY, code, 0);
                return true;
            }
            ControllerLayout cfg = turbos.get(dev.name);
            if (!turboOn || cfg == null || !cfg.turbo.contains(code)) return false;
            if (value == 2 || running != null) return true;
            if (value != 1) return false;
            deliver(Codes.EV_KEY, code, 1);
            long half = Math.max(20, 500 / Math.max(2, cfg.turboRate)); // press + release = one cycle
            final boolean[] down = {true};
            turboTasks.put(code, timer.scheduleAtFixedRate(() -> {
                synchronized (Source.this) {
                    if (!alive) return;
                    down[0] = !down[0];
                    deliver(Codes.EV_KEY, code, down[0] ? 1 : 0);
                }
            }, half, half, TimeUnit.MILLISECONDS));
            return true;
        }

        synchronized void stopTurbo() {
            for (Map.Entry<Integer, ScheduledFuture<?>> e : turboTasks.entrySet()) {
                e.getValue().cancel(false);
                try {
                    deliver(Codes.EV_KEY, e.getKey(), 0);
                } catch (Throwable ignored) {
                }
            }
            turboTasks.clear();
        }

        private boolean isJoinButton(int code) {
            return code == Codes.BTN_SOUTH || code == Codes.BTN_EAST || code == Codes.BTN_NORTH
                    || code == Codes.BTN_WEST || code == Codes.BTN_START;
        }

        synchronized void joinAs(Slot sl) {
            sink = sl.encoder;
            player = sl.player;
            Mapper m = new Mapper(dev, sink);
            m.setTuning(tunings.get(dev.name));
            mapper = m;
            joined = true;
        }

        synchronized void applyTuning() {
            if (mapper == null || builtIn || touchpad) return;
            try {
                mapper.setTuning(tunings.get(dev.name));
            } catch (Throwable ignored) {
            }
        }

        synchronized void modeChanged() {
            stopTurbo();
            try {
                if (mapper != null) mapper.releaseAll();
                if (pcMapper != null) pcMapper.releaseAll();
                if (kbMapper != null) kbMapper.releaseAll();
                if (engine != null) engine.releaseAll();
            } catch (Throwable ignored) {
            }
        }

        private void buildEngine() {
            engine = new ShortcutEngine(shortcuts, dev.name, new ShortcutEngine.Out() {
                @Override
                public void output(int code, boolean down, Shortcut from) {
                    if (Codes.isAction(code)) {
                        if (down) Actions.run(code, from == null ? "" : from.param, null);
                        return;
                    }
                    if (Codes.isKeyboardOutput(code)) {
                        extras.key(code - Codes.KB, down);
                        return;
                    }
                    sink.rawKey(code, down);
                }

                @Override
                public void forward(int code, boolean down) {
                    if (mapper != null) deliver(Codes.EV_KEY, code, down ? 1 : 0);
                }

                @Override
                public void armed(Shortcut s) {
                    Actions.toast("Let go for: " + s.actionLabel());
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
            for (ScheduledFuture<?> f : turboTasks.values()) f.cancel(false);
            turboTasks.clear();
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
                    if (!builtIn) mapper.releaseAll();
                    if (pcMapper != null) pcMapper.releaseAll();
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
