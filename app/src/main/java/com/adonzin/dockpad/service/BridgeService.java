package com.adonzin.dockpad.service;

import android.content.Context;
import android.util.Log;

import com.adonzin.dockpad.IBridge;

/**
 * Runs inside a separate process started by Shizuku with ADB ("shell") privileges.
 * It keeps running after the DockPad app is closed, until stopped or the device reboots.
 */
public class BridgeService extends IBridge.Stub {
    private final Bridge bridge = new Bridge();

    public BridgeService() {
        Hidden.exemptHiddenApis();
        Log.i("DockPad", "BridgeService created");
    }

    public BridgeService(Context context) {
        this();
    }

    @Override
    public void destroy() {
        bridge.stop();
        System.exit(0);
    }

    @Override
    public String start(boolean includeBuiltIn) {
        return bridge.start(includeBuiltIn);
    }

    @Override
    public void stop() {
        bridge.stop();
    }

    @Override
    public String status() {
        return bridge.status();
    }

    @Override
    public boolean isRunning() {
        return bridge.isRunning();
    }

    @Override
    public String statusJson() {
        return bridge.statusJson();
    }

    @Override
    public void setShortcuts(String spec) {
        bridge.setShortcuts(spec);
    }

    @Override
    public String captureCombo(int timeoutMs, boolean raw) {
        try {
            return bridge.captureCombo(timeoutMs, raw);
        } catch (InterruptedException e) {
            return "";
        }
    }

    @Override
    public void setTouchpadAsButton(boolean on) {
        bridge.setTouchpadAsButton(on);
    }

    @Override
    public void setMultiplayer(boolean on) {
        bridge.setMultiplayer(on);
    }

    @Override
    public void setLayouts(String spec) {
        bridge.setLayouts(spec);
    }

    @Override
    public void setBuiltIn(String spec) {
        bridge.setBuiltIn(spec);
    }

    @Override
    public String identifyController(int timeoutMs) {
        try {
            return bridge.identifyController(timeoutMs);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void setPcMode(boolean on) {
        bridge.setPcMode(on);
    }

    @Override
    public void setPcSettings(String spec) {
        bridge.setPcSettings(spec);
    }

    @Override
    public void runCommand(String spec, int delayMs) {
        bridge.runCommand(spec, delayMs);
    }

    @Override
    public void setKeyboardOpen(boolean on) {
        bridge.setKeyboardOpen(on);
    }

    @Override
    public String pollKeyboard() {
        return bridge.pollKeyboard();
    }

    @Override
    public void typeKey(int code, boolean shift) {
        bridge.typeKey(code, shift);
    }

    @Override
    public void typeCombo(String csv) {
        bridge.typeCombo(csv);
    }

    @Override
    public String recordAllInput(int ms) {
        try {
            return bridge.recordAllInput(ms);
        } catch (Exception e) {
            return "Couldn't record: " + e;
        }
    }

    /** Every system/global/secure setting plus the screens' power states (read only), to compare. */
    @Override
    public String settingsSnapshot() {
        return Actions.exec("sh", "-c", "for n in system global secure; do settings list $n | sed \"s/^/$n: /\"; done; "
                + "dumpsys display | grep -E 'mState=|mScreenState=' | sed 's/^ */display: /'; "
                + "dumpsys power | grep -E 'mWakefulness=|Display Power' | sed 's/^ */power: /'");
    }

    @Override
    public void setScreenOffAgain(boolean on) {
        bridge.dock.setScreenOffAgain(on);
    }

    @Override
    public void setDockReady(boolean on) {
        bridge.dock.setEnabled(on);
    }

    @Override
    public void setJoinByPress(boolean on) {
        bridge.setJoinByPress(on);
    }

    @Override
    public String disconnectController(String name) {
        try {
            return bridge.disconnectController(name);
        } catch (Throwable t) {
            return "Couldn't disconnect: " + t;
        }
    }

    /** Shizuku: install, then reopen the app (which restarts this service at the new version). */
    @Override
    public void installUpdate(String url, int version) {
        new Thread(() -> {
            String err = Updater.downloadAndInstall(url);
            if (err != null) {
                Actions.toast("DockPad update: " + err);
                return;
            }
            String pkg = com.adonzin.dockpad.BuildConfig.APPLICATION_ID;
            Actions.exec("sh", "-c", "(sleep 3; am start -n " + pkg + "/" + pkg + ".MainActivity) >/dev/null 2>&1 &");
        }, "DockPad-update").start();
    }

    @Override
    public void setRumble(boolean on) {
        bridge.setRumble(on);
    }
}
