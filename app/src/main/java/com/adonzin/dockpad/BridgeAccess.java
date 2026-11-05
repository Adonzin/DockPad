package com.adonzin.dockpad;

import android.content.Context;

import com.adonzin.dockpad.adb.SocketBridge;
import com.adonzin.dockpad.adb.Starter;

/** Finds DockPad's running service: its own (built-in pairing) or the Shizuku one. */
final class BridgeAccess {
    private BridgeAccess() {}

    /** Call off the main thread (after ShizukuLink.connect on the main thread, for Shizuku). */
    static IBridge find(Context c) {
        SocketBridge own = Starter.bridge(c);
        if (own.version() >= 0) return own;
        return ShizukuLink.bridge();
    }
}
