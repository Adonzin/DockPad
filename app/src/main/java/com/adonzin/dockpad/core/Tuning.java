package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-controller fine-tuning of sticks and triggers, applied to the normalized axes
 * (see {@link Mapper}) before they reach the game. The defaults change nothing.
 */
public final class Tuning {
    /** Stick dead zone in percent of full tilt (0 = the controller's own). */
    public int deadZone = 0;
    /** Stick response: 100 = linear, below = more sensitive near the center, above = finer near the center. */
    public int response = 100;
    /** How far (percent) an analog trigger must be pulled to count as an L2/R2 press. */
    public int triggerPoint = 50;
    public boolean swapSticks, invertLeftY, invertRightY, leftStickDpad;

    public boolean isDefault() {
        return deadZone == 0 && response == 100 && triggerPoint == 50
                && !swapSticks && !invertLeftY && !invertRightY && !leftStickDpad;
    }

    public void reset() {
        deadZone = 0;
        response = 100;
        triggerPoint = 50;
        swapSticks = invertLeftY = invertRightY = leftStickDpad = false;
    }

    public Tuning copy() {
        return decode(encode());
    }

    /** The trigger press point differs from the controller's own (so DockPad decides presses). */
    public boolean customTriggerPoint() {
        return triggerPoint != 50;
    }

    public float pressAt() {
        return triggerPoint / 100f;
    }

    public float releaseAt() {
        return Math.max(0.02f, pressAt() - 0.08f);
    }

    /** No tuning: only the controller's own dead zone is applied. */
    public static final Tuning NONE = new Tuning();

    /**
     * Turns raw normalized axes into what the game should see. {@code prev} is the last output (or null).
     * {@code deadL}/{@code deadR}: the controller's own stick dead zones (used when this tuning sets none);
     * {@code lift}: where the game's own dead zone ends, so a stick leaving ours moves in the game at once.
     */
    public float[] apply(float[] in, float[] prev, float deadL, float deadR, float lift) {
        float[] o = in.clone();
        if (swapSticks) {
            o[Mapper.LX] = in[Mapper.RX];
            o[Mapper.LY] = in[Mapper.RY];
            o[Mapper.RX] = in[Mapper.LX];
            o[Mapper.RY] = in[Mapper.LY];
            float d = deadL;
            deadL = deadR;
            deadR = d;
        }
        shape(o, Mapper.LX, Mapper.LY, deadZone > 0 ? deadZone / 100f : deadL, lift);
        shape(o, Mapper.RX, Mapper.RY, deadZone > 0 ? deadZone / 100f : deadR, lift);
        if (invertLeftY) o[Mapper.LY] = -o[Mapper.LY];
        if (invertRightY) o[Mapper.RY] = -o[Mapper.RY];
        if (leftStickDpad) {
            float px = prev == null ? 0 : prev[Mapper.HX], py = prev == null ? 0 : prev[Mapper.HY];
            float sx = dir(o[Mapper.LX], px), sy = dir(o[Mapper.LY], py);
            if (in[Mapper.HX] == 0 && in[Mapper.HY] == 0) {
                o[Mapper.HX] = sx;
                o[Mapper.HY] = sy;
            }
            o[Mapper.LX] = 0;
            o[Mapper.LY] = 0;
        }
        return o;
    }

    /** Stick direction with a little hysteresis, so it doesn't flicker at the edge. */
    private static float dir(float v, float was) {
        float on = 0.5f, off = 0.4f;
        if (was > 0 && v > off) return 1f;
        if (was < 0 && v < -off) return -1f;
        return v > on ? 1f : v < -on ? -1f : 0f;
    }

    /**
     * A round (radial) dead zone that rescales what's outside it, so there's no jump when the stick
     * leaves it and full tilt still reaches the edge. Small moves stay small: that's what aiming needs.
     */
    private void shape(float[] o, int xi, int yi, float dz, float lift) {
        if (dz <= 0f && response == 100) return;
        float x = o[xi], y = o[yi];
        float mag = (float) Math.hypot(x, y);
        if (mag <= dz || mag == 0f) {
            o[xi] = 0;
            o[yi] = 0;
            return;
        }
        float m = Math.min(1f, (Math.min(mag, 1f) - dz) / (1f - dz));
        if (response != 100) m = (float) Math.pow(m, response / 100.0);
        // our dead zone already hides the stick's resting wobble: start just past the game's own one
        if (dz >= 0.02f && lift > 0f) m = lift + (1f - lift) * m;
        float scale = m / mag;
        o[xi] = clamp(x * scale);
        o[yi] = clamp(y * scale);
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : v > 1f ? 1f : v;
    }

    public String summary() {
        List<String> p = new ArrayList<>();
        if (deadZone != 0) p.add("dead zone " + deadZone + "%");
        if (response != 100) p.add(responseLabel(response).toLowerCase() + " sticks");
        if (triggerPoint != 50) p.add("triggers at " + triggerPoint + "%");
        if (swapSticks) p.add("sticks swapped");
        if (invertLeftY) p.add("left Y inverted");
        if (invertRightY) p.add("right Y inverted");
        if (leftStickDpad) p.add("left stick as D-pad");
        return String.join(", ", p);
    }

    public static final String[] RESPONSE_LABELS = {"Very quick", "Quick", "Normal", "Precise", "Very precise"};
    public static final int[] RESPONSES = {50, 75, 100, 150, 200};

    public static String responseLabel(int r) {
        int best = 0;
        for (int i = 0; i < RESPONSES.length; i++) {
            if (Math.abs(RESPONSES[i] - r) < Math.abs(RESPONSES[best] - r)) best = i;
        }
        return RESPONSE_LABELS[best];
    }

    /** deadZone:response:triggerPoint:flags */
    public String encode() {
        return deadZone + ":" + response + ":" + triggerPoint + ":"
                + (swapSticks ? "S" : "") + (invertLeftY ? "L" : "") + (invertRightY ? "R" : "")
                + (leftStickDpad ? "D" : "") + "-";
    }

    public static Tuning decode(String s) {
        Tuning t = new Tuning();
        if (s == null) return t;
        String[] f = s.split(":");
        try {
            if (f.length > 0) t.deadZone = Math.max(0, Math.min(60, Integer.parseInt(f[0])));
            if (f.length > 1) t.response = Math.max(25, Math.min(300, Integer.parseInt(f[1])));
            if (f.length > 2) t.triggerPoint = Math.max(5, Math.min(95, Integer.parseInt(f[2])));
        } catch (NumberFormatException ignored) {
        }
        if (f.length > 3) {
            t.swapSticks = f[3].contains("S");
            t.invertLeftY = f[3].contains("L");
            t.invertRightY = f[3].contains("R");
            t.leftStickDpad = f[3].contains("D");
        }
        return t;
    }
}
