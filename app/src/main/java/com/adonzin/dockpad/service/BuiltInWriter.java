package com.adonzin.dockpad.service;

import android.os.Process;
import android.util.Log;

import com.adonzin.dockpad.core.EvdevDevice;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes events straight into the built-in controls' own input device (like Android's
 * `sendevent` tool). Apps can't tell them apart from real presses on the built-in controls,
 * so every emulator already set up for the built-in controls works without remapping.
 */
final class BuiltInWriter implements PadOut {
    private final EvdevDevice device;
    private FileOutputStream out;
    private volatile String error = "";

    BuiltInWriter(EvdevDevice device) {
        this.device = device;
    }

    boolean open() {
        try {
            out = new FileOutputStream(device.path);
            return true;
        } catch (Throwable t) {
            error = "can't write to " + device.path + ": " + t;
            Log.e("DockPad", error, t);
            return false;
        }
    }

    @Override
    public String name() {
        return device.name;
    }

    @Override
    public boolean isAlive() {
        return out != null;
    }

    @Override
    public String describe() {
        String s = stickInfo();
        return isAlive() ? "active (sent as the built-in controls" + (s.isEmpty() ? "" : "; " + s) + ")"
                : "NOT working — " + error;
    }

    /** The last value written per axis (what the kernel holds, as far as DockPad knows). */
    private final java.util.Map<Integer, Integer> lastAbs = new java.util.HashMap<>();

    /**
     * The built-in sticks have "fuzz": the kernel holds back or averages any change smaller than about
     * twice it. That suits the built-in sticks' fine steps, but it would blunt an external stick's
     * small, single moves (and leave the stick short of where it stopped). So a small change goes in
     * with a "kick": first a value far enough away to pass untouched, then the real value, which then
     * passes untouched too. Both are in the same report, so Android only ever sees the real one.
     */
    private int[] unfuzz(int[] events) {
        int extra = 0;
        int[] kick = new int[events.length / 3];
        for (int i = 0, k = 0; i + 2 < events.length; i += 3, k++) {
            kick[k] = Integer.MIN_VALUE;
            if (events[i] != 3) continue; // EV_ABS
            int code = events[i + 1], v = events[i + 2];
            EvdevDevice.AbsInfo a = device.abs.get(code);
            Integer was = lastAbs.put(code, v);
            if (a == null || a.fuzz <= 0 || was == null || was == v) continue;
            int gap = 2 * a.fuzz;
            if (Math.abs(v - was) >= gap) continue;
            int dir = v > was ? 1 : -1;
            int away = was - dir * gap; // beyond the old value, on the far side from the new one
            if (away < a.min || away > a.max) away = v + dir * gap; // or past the new one
            kick[k] = away;
            extra++;
        }
        if (extra == 0) return events;
        int[] o = new int[events.length + extra * 3];
        for (int i = 0, k = 0, j = 0; i + 2 < events.length; i += 3, k++) {
            if (kick[k] != Integer.MIN_VALUE) {
                o[j++] = 3;
                o[j++] = events[i + 1];
                o[j++] = kick[k];
            }
            o[j++] = events[i];
            o[j++] = events[i + 1];
            o[j++] = events[i + 2];
        }
        return o;
    }

    /** For Diagnostics: the built-in sticks' range and smoothing. */
    String stickInfo() {
        EvdevDevice.AbsInfo a = device.abs.get(0);
        return a == null ? "" : "sticks " + a.min + "…" + a.max + ", fuzz " + a.fuzz + ", flat " + a.flat;
    }

    @Override
    public synchronized void write(int[] events) {
        if (out == null) return;
        events = unfuzz(events);
        int size = Process.is64Bit() ? 24 : 16;
        int off = size == 24 ? 16 : 8;
        ByteBuffer b = ByteBuffer.allocate(size * (events.length / 3)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0, p = 0; i + 2 < events.length; i += 3, p += size) {
            b.putShort(p + off, (short) events[i]);
            b.putShort(p + off + 2, (short) events[i + 1]);
            b.putInt(p + off + 4, events[i + 2]);
        }
        try {
            out.write(b.array());
        } catch (IOException e) {
            error = "write failed: " + e;
            close();
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }
}
