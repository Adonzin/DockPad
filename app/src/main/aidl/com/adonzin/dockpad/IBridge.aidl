package com.adonzin.dockpad;

interface IBridge {
    // Called by Shizuku when the service is being removed. Code is fixed by Shizuku.
    void destroy() = 16777114;

    String start(boolean includeBuiltIn) = 1;
    void stop() = 2;
    String status() = 3;
    boolean isRunning() = 4;
    void setTouchpadAsButton(boolean on) = 7;
    void setMultiplayer(boolean on) = 8;
    String statusJson() = 9;
    void setShortcuts(String spec) = 10;
    String captureCombo(int timeoutMs, boolean raw) = 11;
    void setLayouts(String spec) = 12;
    void setBuiltIn(String spec) = 13;
    String identifyController(int timeoutMs) = 14;
    void setPcMode(boolean on) = 15;
    void setPcSettings(String spec) = 16;
    void runCommand(String spec, int delayMs) = 17;
    void setKeyboardOpen(boolean on) = 18;
    String pollKeyboard() = 19;
    void typeKey(int code, boolean shift) = 20;
    void typeCombo(String csv) = 21;
    String recordAllInput(int ms) = 22;
    void setJoinByPress(boolean on) = 23;
    String disconnectController(String name) = 24;
    void installUpdate(String url, int version) = 25;
    void setRumble(boolean on) = 26;
    void setDockReady(boolean on) = 27;
    String settingsSnapshot() = 28;
    void setScreenOffAgain(boolean on) = 29;
}
