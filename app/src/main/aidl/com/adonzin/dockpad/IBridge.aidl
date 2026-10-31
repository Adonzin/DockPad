package com.adonzin.dockpad;

interface IBridge {
    // Called by Shizuku when the service is being removed. Code is fixed by Shizuku.
    void destroy() = 16777114;

    String start() = 1;
    void stop() = 2;
    String status() = 3;
    boolean isRunning() = 4;
}
