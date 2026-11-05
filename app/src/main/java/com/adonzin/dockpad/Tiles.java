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
    }

    static State read(TileService t) {
        State st = new State();
        SocketBridge b = Starter.bridge(t);
        if (b.version() < 0) return st;
        st.serviceUp = true;
        try {
            JSONObject o = new JSONObject(b.statusJson());
            st.running = o.optBoolean("running");
            st.cursor = o.optBoolean("pcMode");
        } catch (Exception ignored) {
        }
        return st;
    }

    @SuppressWarnings("deprecation")
    static void openApp(TileService t) {
        Intent i = new Intent(t, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
            refresh();
        }

        private void refresh() {
            bg(() -> {
                State st = read(this);
                ui(() -> set(this, st.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE,
                        st.running ? "On" : "Off"));
            });
        }

        @Override
        public void onClick() {
            Prefs prefs = new Prefs(this);
            if (!prefs.paired()) { // Shizuku or not set up yet: use the app
                openApp(this);
                return;
            }
            set(this, Tile.STATE_UNAVAILABLE, "…");
            bg(() -> {
                State st = read(this);
                try {
                    if (st.running) {
                        prefs.bridgeWanted(false);
                        Starter.bridge(this).stop();
                    } else {
                        prefs.bridgeWanted(true);
                        if (!st.serviceUp) {
                            Starter.Result r = Starter.start(this, prefs.turnOffWirelessDebugging());
                            if (!r.ok) {
                                ui(() -> openApp(this)); // the app explains what's wrong
                                return;
                            }
                        }
                        prefs.applyTo(Starter.bridge(this), true);
                    }
                } catch (Exception ignored) {
                }
                refresh();
            });
        }
    }

    // ------------------------------------------------------------------ Cursor mode on/off

    public static class CursorTile extends TileService {
        @Override
        public void onStartListening() {
            refresh();
        }

        private void refresh() {
            bg(() -> {
                State st = read(this);
                ui(() -> {
                    if (!st.running) set(this, Tile.STATE_UNAVAILABLE, "DockPad is off");
                    else set(this, st.cursor ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, st.cursor ? "On" : "Off");
                });
            });
        }

        @Override
        public void onClick() {
            bg(() -> {
                State st = read(this);
                if (!st.running) {
                    ui(() -> openApp(this));
                    return;
                }
                try {
                    Starter.bridge(this).setPcMode(!st.cursor);
                } catch (Exception ignored) {
                }
                refresh();
            });
        }
    }
}
