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
        return isAlive() ? "active (sent as the built-in controls)" : "NOT working — " + error;
    }

    @Override
    public synchronized void write(int[] events) {
        if (out == null) return;
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
