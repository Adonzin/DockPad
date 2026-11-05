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
}
