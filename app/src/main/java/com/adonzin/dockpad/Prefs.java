package com.adonzin.dockpad;

import android.content.Context;
import android.content.SharedPreferences;

import com.adonzin.dockpad.core.ControllerLayout;
import com.adonzin.dockpad.core.Shortcut;

import java.util.List;
import java.util.Map;

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

    /** Built-in quick menu items the user has hidden (ids from QuickMenuActivity.ITEMS). */
    java.util.Set<String> menuHidden() {
        return new java.util.HashSet<>(java.util.Arrays.asList(p.getString("menuHidden", "").split(",")));
    }
    void menuHidden(java.util.Set<String> ids) { p.edit().putString("menuHidden", String.join(",", ids)).apply(); }

    /** Quick menu commands: name in Shortcut.device, the action/macro in steps. */
    List<Shortcut> menuCommands() { return Shortcut.decodeAll(p.getString("menuCommands", "")); }
    void menuCommands(List<Shortcut> list) { p.edit().putString("menuCommands", Shortcut.encodeAll(list)).apply(); }

    List<Shortcut> shortcuts() { return Shortcut.decodeAll(p.getString("shortcuts", "")); }
    String shortcutsSpec() { return p.getString("shortcuts", ""); }
    void shortcuts(List<Shortcut> list) { p.edit().putString("shortcuts", Shortcut.encodeAll(list)).apply(); }

    Map<String, ControllerLayout> layouts() { return ControllerLayout.decodeAll(p.getString("layouts", "")); }
    String layoutsSpec() { return p.getString("layouts", ""); }
    void layouts(Map<String, ControllerLayout> all) { p.edit().putString("layouts", ControllerLayout.encodeAll(all)).apply(); }

    ControllerLayout layout(String device) {
        ControllerLayout l = layouts().get(device);
        if (l == null) {
            l = new ControllerLayout();
            l.device = device;
        }
        return l;
    }

    void saveLayout(ControllerLayout l) {
        Map<String, ControllerLayout> all = layouts();
        all.put(l.device, l);
        layouts(all);
    }

    /** DockPad has been paired with Wireless debugging (built-in mode, no Shizuku needed). */
    boolean paired() { return p.getBoolean("paired", false); }
    void paired(boolean v) { p.edit().putBoolean("paired", v).apply(); }

    boolean autoStart() { return p.getBoolean("autoStart", true); }
    void autoStart(boolean v) { p.edit().putBoolean("autoStart", v).apply(); }

    boolean turnOffWirelessDebugging() { return p.getBoolean("turnOffWD", true); }
    void turnOffWirelessDebugging(boolean v) { p.edit().putBoolean("turnOffWD", v).apply(); }

    /** Sends every setting to a running bridge and turns it on if the user wants it on. */
    /** User-chosen built-in controls: "name \t vendor \t product", or "" for automatic. */
    String builtIn() { return p.getString("builtIn", ""); }
    void builtIn(String v) { p.edit().putString("builtIn", v).apply(); }

    float pcSpeed() { return p.getFloat("pcSpeed", 1f); }
    void pcSpeed(float v) { p.edit().putFloat("pcSpeed", v).apply(); }

    boolean pcPointerLeft() { return p.getBoolean("pcPointerLeft", false); }
    void pcPointerLeft(boolean v) { p.edit().putBoolean("pcPointerLeft", v).apply(); }

    void applyTo(IBridge b, boolean startIfWanted) throws Exception {
        b.setBuiltIn(builtIn());
        b.setPcSettings(pcSpeed() + "," + pcPointerLeft());
        b.setLayouts(layoutsSpec());
        b.setMultiplayer(multiplayer());
        b.setTouchpadAsButton(touchpadAsButton());
        b.setShortcuts(shortcutsSpec());
        if (b.isRunning() || (startIfWanted && bridgeWanted())) b.start(true);
    }
}
