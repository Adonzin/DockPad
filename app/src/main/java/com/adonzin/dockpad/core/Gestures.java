package com.adonzin.dockpad.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shapes drawn on the handheld's screen (handheld controls): each is taught by drawing it a few
 * times, and recognised by comparing a new drawing with those samples. Size and position don't
 * matter; the shape and the direction it's drawn in do.
 */
public final class Gestures {
    /** Trigger codes for drawn gestures: BASE + id. */
    public static final int BASE = 0x310, MAX = 32;
    /** Points per normalised drawing. */
    public static final int POINTS = 32;
    /** Average distance (in a unit-sized box) under which a drawing counts as the shape. */
    public static final float MATCH = 0.17f;

    private Gestures() {
    }

    public static boolean isGesture(int code) {
        return code >= BASE && code < BASE + MAX;
    }

    public static final class Gesture {
        public int id;
        public int fingers = 2;
        public String name = "";
        public final List<float[]> samples = new ArrayList<>();

        public int code() {
            return BASE + id;
        }

        String encode() {
            StringBuilder b = new StringBuilder();
            b.append(id).append('\t').append(fingers).append('\t').append(name.replaceAll("[\\t\\n|]", " ").trim()).append('\t');
            for (int s = 0; s < samples.size(); s++) {
                if (s > 0) b.append('|');
                float[] p = samples.get(s);
                for (int i = 0; i < p.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(String.format(Locale.US, "%.3f", p[i]));
                }
            }
            return b.toString();
        }

        static Gesture decode(String line) {
            String[] f = line.split("\t");
            if (f.length != 4) return null;
            try {
                Gesture g = new Gesture();
                g.id = Integer.parseInt(f[0]);
                g.fingers = Integer.parseInt(f[1]);
                g.name = f[2];
                for (String s : f[3].split("\\|")) {
                    String[] v = s.split(",");
                    if (v.length != POINTS * 2) continue;
                    float[] p = new float[v.length];
                    for (int i = 0; i < v.length; i++) p[i] = Float.parseFloat(v[i]);
                    g.samples.add(p);
                }
                return g.samples.isEmpty() || g.id < 0 || g.id >= MAX ? null : g;
            } catch (Exception e) {
                return null;
            }
        }

        /** How far a drawing is from this shape: its closest sample. */
        public float distance(float[] drawing) {
            float best = Float.MAX_VALUE;
            for (float[] s : samples) best = Math.min(best, Gestures.distance(s, drawing));
            return best;
        }
    }

    public static String encodeAll(List<Gesture> all) {
        StringBuilder b = new StringBuilder();
        for (Gesture g : all) b.append(g.encode()).append('\n');
        return b.toString();
    }

    public static List<Gesture> decodeAll(String spec) {
        List<Gesture> out = new ArrayList<>();
        if (spec == null) return out;
        for (String line : spec.split("\n")) {
            Gesture g = Gesture.decode(line);
            if (g != null) out.add(g);
        }
        return out;
    }

    public static int freeId(List<Gesture> all) {
        for (int id = 0; id < MAX; id++) {
            boolean used = false;
            for (Gesture g : all) used |= g.id == id;
            if (!used) return id;
        }
        return -1;
    }

    // ------------------------------------------------------------ names for labels

    private static volatile Map<Integer, String> names = new HashMap<>();

    public static void publishNames(List<Gesture> all) {
        Map<Integer, String> m = new HashMap<>();
        for (Gesture g : all) m.put(g.code(), g.name);
        names = m;
    }

    public static String name(int code) {
        String n = names.get(code);
        return n != null ? n : "Gesture " + (code - BASE + 1);
    }

    // ------------------------------------------------------------ the maths

    /** Raw points (x0,y0,x1,y1…) → POINTS points, evenly spaced along the path, centred, in a unit box. */
    public static float[] normalize(float[] raw) {
        int n = raw.length / 2;
        if (n < 2) return null;
        double length = 0;
        for (int i = 1; i < n; i++) length += Math.hypot(raw[2 * i] - raw[2 * i - 2], raw[2 * i + 1] - raw[2 * i - 1]);
        if (length <= 0) return null;
        float[] out = new float[POINTS * 2];
        double step = length / (POINTS - 1), carried = 0;
        int k = 0;
        out[k++] = raw[0];
        out[k++] = raw[1];
        double px = raw[0], py = raw[1];
        for (int i = 1; i < n && k < POINTS * 2; i++) {
            double qx = raw[2 * i], qy = raw[2 * i + 1];
            double d = Math.hypot(qx - px, qy - py);
            while (carried + d >= step && k < POINTS * 2) {
                double t = (step - carried) / d;
                px = px + t * (qx - px);
                py = py + t * (qy - py);
                out[k++] = (float) px;
                out[k++] = (float) py;
                d = Math.hypot(qx - px, qy - py);
                carried = 0;
            }
            carried += d;
            px = qx;
            py = qy;
        }
        while (k < POINTS * 2) { // rounding: repeat the last point
            out[k] = raw[2 * n - 2];
            out[k + 1] = raw[2 * n - 1];
            k += 2;
        }
        float cx = 0, cy = 0, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < POINTS; i++) {
            cx += out[2 * i];
            cy += out[2 * i + 1];
            minX = Math.min(minX, out[2 * i]);
            maxX = Math.max(maxX, out[2 * i]);
            minY = Math.min(minY, out[2 * i + 1]);
            maxY = Math.max(maxY, out[2 * i + 1]);
        }
        cx /= POINTS;
        cy /= POINTS;
        float size = Math.max(maxX - minX, maxY - minY);
        if (size <= 0) return null;
        for (int i = 0; i < POINTS; i++) {
            out[2 * i] = (out[2 * i] - cx) / size;
            out[2 * i + 1] = (out[2 * i + 1] - cy) / size;
        }
        return out;
    }

    public static float distance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return Float.MAX_VALUE;
        double sum = 0;
        for (int i = 0; i < a.length / 2; i++) sum += Math.hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]);
        return (float) (sum / (a.length / 2));
    }

    /** Encodes one normalised drawing (for sending between the service and the app). */
    public static String encodePoints(float[] p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(',');
            b.append(String.format(Locale.US, "%.3f", p[i]));
        }
        return b.toString();
    }

    public static float[] decodePoints(String s) {
        try {
            String[] v = s.split(",");
            if (v.length != POINTS * 2) return null;
            float[] p = new float[v.length];
            for (int i = 0; i < v.length; i++) p[i] = Float.parseFloat(v[i]);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
