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
    public String captureCombo(int timeoutMs) {
        try {
            return bridge.captureCombo(timeoutMs);
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
}
