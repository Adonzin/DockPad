package com.adonzin.dockpad;

interface IBridge {
    // Called by Shizuku when the service is being removed. Code is fixed by Shizuku.
    void destroy() = 16777114;

    String start(boolean includeBuiltIn) = 1;
    void stop() = 2;
    String status() = 3;
    boolean isRunning() = 4;
    void setRemaps(String spec) = 5;
    String captureKey(int timeoutMs) = 6;
}
