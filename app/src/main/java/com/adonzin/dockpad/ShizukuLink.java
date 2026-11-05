package com.adonzin.dockpad;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import com.adonzin.dockpad.service.BridgeService;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/** Connects to DockPad's service when it runs through Shizuku (used by the tiles). */
final class ShizukuLink {
    private ShizukuLink() {}

    private static volatile IBridge bridge;
    private static boolean binding;
    private static final List<Runnable> waiting = new ArrayList<>();

    /** Same settings as the app uses, so this attaches to the already-running service. */
    static Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(
                new ComponentName(BuildConfig.APPLICATION_ID, BridgeService.class.getName()))
                .daemon(true)
                .processNameSuffix("bridge")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);
    }

    static boolean available() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static IBridge bridge() {
        IBridge b = bridge;
        if (b != null && b.asBinder() != null && b.asBinder().pingBinder()) return b;
        bridge = null;
        return null;
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (ShizukuLink.class) {
                binding = false;
                if (binder != null && binder.pingBinder()) bridge = IBridge.Stub.asInterface(binder);
                for (Runnable r : waiting) r.run();
                waiting.clear();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
        }
    };

    /** Call on the main thread. Runs onReady once connected (or right away if already). */
    static void connect(Runnable onReady) {
        if (bridge() != null || !available()) {
            onReady.run();
            return;
        }
        synchronized (ShizukuLink.class) {
            waiting.add(onReady);
            if (binding) return;
            binding = true;
        }
        try {
            Shizuku.bindUserService(args(), conn);
        } catch (Throwable t) {
            synchronized (ShizukuLink.class) {
                binding = false;
                for (Runnable r : waiting) r.run();
                waiting.clear();
            }
        }
    }
}
