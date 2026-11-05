package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies shortcuts to one controller's raw button presses.
 *
 * Rules:
 * - A shortcut fires when its last button is pressed while the others are held.
 *   That last button is kept from the game; the earlier ones reach it as normal.
 * - "When pressed" with a single-step action behaves like a remap: the outputs stay
 *   pressed as long as the button is held.
 * - "When held" fires after half a second. If the same buttons also have a "when pressed"
 *   shortcut, a short press runs that one instead; otherwise a short press goes to the
 *   game as normal.
 * - Multi-step actions are played as a macro: each step is tapped in turn.
 *
 * Not thread-safe: the caller serializes calls (including scheduled callbacks).
 */
public final class ShortcutEngine {
    public static final long HOLD_MS = 500, STEP_DOWN_MS = 60, STEP_GAP_MS = 40;

    public interface Out {
        /** Press/release an output (see Codes: controller button, keyboard key or action). */
        void output(int code, boolean down, Shortcut from);
        /** Let a button press through to the game as if no shortcut existed. */
        void forward(int code, boolean down);
    }

    public interface Scheduler {
        /** Runs r after delayMs; returns a handle that cancels it when run. */
        Runnable schedule(Runnable r, long delayMs);
    }

    private final List<Shortcut> shortcuts = new ArrayList<>();
    private final Set<Integer> triggerKeys = new HashSet<>();
    private final Out out;
    private final Scheduler scheduler;

    private final Set<Integer> down = new HashSet<>();
    private final Set<Integer> consumed = new HashSet<>();
    private final Map<Integer, int[]> heldOutputs = new HashMap<>(); // trigger key -> outputs held
    private final Map<Integer, Shortcut> outputFrom = new HashMap<>();
    private final Map<Integer, Integer> outputCount = new HashMap<>();
    private final List<Runnable> macroTimers = new ArrayList<>();

    private Integer pendingKey;
    private Shortcut pendingPress, pendingHold;
    private Runnable pendingTimer;
    private boolean pendingFired;

    public ShortcutEngine(List<Shortcut> all, String deviceName, Out out, Scheduler scheduler) {
        this.out = out;
        this.scheduler = scheduler;
        for (Shortcut s : all) {
            if (!s.appliesTo(deviceName)) continue;
            shortcuts.add(s);
            for (int c : s.trigger) triggerKeys.add(c);
        }
    }

    public boolean isEmpty() {
        return shortcuts.isEmpty();
    }

    /** Returns true if the key press/release was used by a shortcut (must not reach the game). */
    public boolean onKey(int code, boolean isDown) {
        if (!triggerKeys.contains(code)) return false;
        if (isDown) {
            if (!down.add(code)) return consumed.contains(code);
            Shortcut press = null, hold = null;
            int best = 0;
            for (Shortcut s : shortcuts) {
                if (!contains(s.trigger, code) || !down.containsAll(asList(s.trigger))) continue;
                int n = s.trigger.length;
                if (n < best) continue;
                if (n > best) {
                    best = n;
                    press = hold = null;
                }
                if (s.hold) {
                    if (hold == null) hold = s;
                } else if (press == null) {
                    press = s;
                }
            }
            if (press == null && hold == null) return false;
            consumed.add(code);
            if (hold == null) {
                start(code, press);
            } else {
                cancelPending();
                pendingKey = code;
                pendingPress = press;
                pendingHold = hold;
                pendingFired = false;
                final Shortcut h = hold;
                pendingTimer = scheduler.schedule(() -> {
                    if (pendingKey != null && pendingKey == code && !pendingFired) {
                        pendingFired = true;
                        start(code, h);
                    }
                }, Math.max(100, h.holdMs));
            }
            return true;
        } else {
            down.remove(code);
            if (!consumed.remove(code)) return false;
            if (pendingKey != null && pendingKey == code) {
                boolean fired = pendingFired;
                Shortcut press = pendingPress;
                cancelPending();
                if (!fired) {
                    if (press != null) playMacro(press); // short press of a press+hold pair
                    else {
                        out.forward(code, true);
                        out.forward(code, false);
                    }
                    return true;
                }
            }
            int[] held = heldOutputs.remove(code);
            if (held != null) for (int o : held) release(o, null);
            return true;
        }
    }

    private void start(int key, Shortcut s) {
        if (s.steps.size() == 1) {
            int[] outs = s.steps.get(0);
            heldOutputs.put(key, outs);
            for (int o : outs) press(o, s);
        } else {
            playMacro(s);
        }
    }

    private void playMacro(Shortcut s) {
        List<int[]> steps = s.steps;
        if (macroTimers.size() > 200) macroTimers.subList(0, 100).clear(); // old, finished timers
        long t = 0;
        for (int[] step : steps) {
            final int[] st = step;
            macroTimers.add(scheduler.schedule(() -> {
                for (int o : st) press(o, s);
            }, t));
            t += STEP_DOWN_MS;
            macroTimers.add(scheduler.schedule(() -> {
                for (int o : st) release(o, s);
            }, t));
            t += STEP_GAP_MS;
        }
    }

    private void press(int o, Shortcut s) {
        int c = outputCount.getOrDefault(o, 0);
        outputCount.put(o, c + 1);
        if (c == 0) {
            outputFrom.put(o, s);
            out.output(o, true, s);
        }
    }

    private void release(int o, Shortcut s) {
        int c = outputCount.getOrDefault(o, 0);
        if (c <= 0) return;
        outputCount.put(o, c - 1);
        if (c == 1) out.output(o, false, outputFrom.remove(o));
    }

    private void cancelPending() {
        if (pendingTimer != null) pendingTimer.run();
        pendingTimer = null;
        pendingKey = null;
        pendingPress = pendingHold = null;
        pendingFired = false;
    }

    /** Releases everything (controller disconnected or shortcuts changed). */
    public void releaseAll() {
        cancelPending();
        for (Runnable r : macroTimers) r.run();
        macroTimers.clear();
        for (Map.Entry<Integer, Integer> e : outputCount.entrySet()) {
            if (e.getValue() > 0) out.output(e.getKey(), false, outputFrom.get(e.getKey()));
        }
        outputCount.clear();
        outputFrom.clear();
        heldOutputs.clear();
        down.clear();
        consumed.clear();
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static List<Integer> asList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int v : a) l.add(v);
        return l;
    }

    @Override
    public String toString() {
        return shortcuts.size() + " shortcuts on keys " + Arrays.toString(triggerKeys.toArray());
    }
}
