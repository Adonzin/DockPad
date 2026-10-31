package com.adonzin.dockpad;

import android.content.Context;
import android.content.SharedPreferences;

import com.adonzin.dockpad.core.Shortcut;

import java.util.List;

/** All saved settings in one place. */
final class Prefs {
    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getSharedPreferences("dockpad", Context.MODE_PRIVATE);
        // migrate old one-button shortcuts
        if (p.contains("remaps")) {
            List<Shortcut> old = Shortcut.fromLegacy(p.getString("remaps", ""));
            List<Shortcut> now = shortcuts();
            now.addAll(old);
            p.edit().remove("remaps").putString("shortcuts", Shortcut.encodeAll(now)).apply();
        }
    }

    boolean includeBuiltIn() { return p.getBoolean("includeBuiltIn", true); }
    void includeBuiltIn(boolean v) { p.edit().putBoolean("includeBuiltIn", v).apply(); }

    boolean touchpadAsButton() { return p.getBoolean("touchpadAsButton", true); }
    void touchpadAsButton(boolean v) { p.edit().putBoolean("touchpadAsButton", v).apply(); }

    boolean multiplayer() { return p.getBoolean("multiplayer", true); }
    void multiplayer(boolean v) { p.edit().putBoolean("multiplayer", v).apply(); }

    /** The user wants the bridge on: start it again automatically when the app can. */
    boolean bridgeWanted() { return p.getBoolean("bridgeWanted", false); }
    void bridgeWanted(boolean v) { p.edit().putBoolean("bridgeWanted", v).apply(); }

    List<Shortcut> shortcuts() { return Shortcut.decodeAll(p.getString("shortcuts", "")); }
    String shortcutsSpec() { return p.getString("shortcuts", ""); }
    void shortcuts(List<Shortcut> list) { p.edit().putString("shortcuts", Shortcut.encodeAll(list)).apply(); }
}
