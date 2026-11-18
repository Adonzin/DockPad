package com.adonzin.dockpad;

import android.content.Context;

import com.adonzin.dockpad.adb.SocketBridge;
import com.adonzin.dockpad.adb.Starter;

/** Finds DockPad's running background service. */
final class BridgeAccess {
    private BridgeAccess() {}

    /** Runs it now (kept so callers read the same as before). */
    static void now(Runnable r) {
        r.run();
    }

    /** Call off the main thread. Null when the service isn't running. */
    static IBridge find(Context c) {
        SocketBridge own = Starter.bridge(c);
        return own.version() >= 0 ? own : null;
    }
}
