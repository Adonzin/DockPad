package com.adonzin.dockpad;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.adonzin.dockpad.adb.SocketBridge;
import com.adonzin.dockpad.adb.Starter;

import org.json.JSONObject;

/** Quick Settings tiles: DockPad on/off and Cursor mode on/off. */
final class Tiles {
    private Tiles() {}

    /** Current state, read from DockPad's own background service (built-in pairing). */
    static final class State {
        boolean serviceUp, running, cursor;
        String problem = ""; // why the state couldn't be read, shown on the tile
        IBridge bridge; // whichever is running: DockPad's own service or the Shizuku one
    }

    /** Call off the main thread. */
    static State read(TileService t) {
        State st = new State();
        IBridge b;
        SocketBridge own = Starter.bridge(t);
        if (own.version() >= 0) b = own;
        else b = ShizukuLink.bridge();
        if (b == null) {
            st.problem = "Service not running";
            return st;
        }
        st.bridge = b;
        st.serviceUp = true;
        try {
            JSONObject o = new JSONObject(b.statusJson());
            st.running = o.optBoolean("running");
            st.cursor = o.optBoolean("pcMode");
            if (!st.running) st.problem = "DockPad is off";
        } catch (Exception e) {
            st.problem = "Can't read state: " + e.getClass().getSimpleName();
        }
        return st;
    }

    @SuppressWarnings("deprecation")
    static void openApp(TileService t) {
        open(t, new Intent(t, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Opens DockPad's connection screen, which explains what went wrong and how to fix it. */
    static void openConnect(TileService t) {
        open(t, MainActivity.connectIntent(t));
    }

    private static void open(TileService t, Intent i) {
        try {
            t.startActivityAndCollapse(i);
        } catch (Exception e) {
            t.startActivity(i);
        }
    }

    static void bg(Runnable r) {
        new Thread(r, "DockPad-tile").start();
    }

    static void ui(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    static void set(TileService t, int state, String subtitle) {
        Tile tile = t.getQsTile();
        if (tile == null) return;
        tile.setState(state);
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.setSubtitle(subtitle);
        tile.updateTile();
    }

    // ------------------------------------------------------------------ DockPad on/off

    public static class DockPadTile extends TileService {
        @Override
        public void onStartListening() {
            ShizukuLink.connect(this::refresh);
        }

        private void refresh() {
            bg(() -> {
                State st = read(this);
                ui(() -> set(this, st.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE,
                        st.running ? "On" : st.problem.isEmpty() ? "Off" : st.problem));
            });
        }

        @Override
        public void onClick() {
            Prefs prefs = new Prefs(this);
            ShizukuLink.connect(() -> bg(() -> {
                State st = read(this);
                try {
                    if (st.running) {
                        prefs.bridgeWanted(false);
                        st.bridge.stop();
                    } else if (st.bridge != null && !(st.bridge instanceof SocketBridge)) {
                        prefs.bridgeWanted(true); // Shizuku
                        prefs.applyTo(st.bridge, true);
                    } else if (!prefs.paired()) {
                        ui(() -> openApp(this)); // not set up yet
                        return;
                    } else {
                        prefs.bridgeWanted(true);
                        if (!st.serviceUp) {
                            Starter.Result r = Starter.start(this, prefs.turnOffWirelessDebugging());
                            if (!r.ok) {
                                ui(() -> openConnect(this)); // the app explains what's wrong
                                return;
                            }
                        }
                        prefs.applyTo(Starter.bridge(this), true);
                    }
                } catch (Exception ignored) {
                }
                refresh();
            }));
        }
    }

    // ------------------------------------------------------------------ Cursor mode on/off

    public static class CursorTile extends TileService {
        @Override
        public void onStartListening() {
            ShizukuLink.connect(this::refresh);
        }

        private void refresh() {
            bg(() -> {
                State st = read(this);
                ui(() -> {
                    // stays tappable when off (a tap opens DockPad); unavailable tiles ignore taps
                    if (!st.running) set(this, Tile.STATE_INACTIVE, st.problem.isEmpty() ? "DockPad is off" : st.problem);
                    else set(this, st.cursor ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, st.cursor ? "On" : "Off");
                });
            });
        }

        @Override
        public void onClick() {
            ShizukuLink.connect(() -> bg(() -> {
                State st = read(this);
                if (!st.running) {
                    ui(() -> openApp(this));
                    return;
                }
                try {
                    st.bridge.setPcMode(!st.cursor);
                } catch (Exception ignored) {
                }
                Starter.sleepQuietly(300);
                refresh();
            }));
        }
    }
}
