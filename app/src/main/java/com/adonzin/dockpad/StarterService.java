package com.adonzin.dockpad;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;

import com.adonzin.dockpad.adb.Starter;

/** Starts DockPad in the background after a restart (built-in mode). */
public class StarterService extends Service {
    private static final int ID = 3;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, StarterService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notifications.foreground(this, ID, Notifications.builder(this, "Starting DockPad",
                "Connecting to Wireless debugging…").setOngoing(true).build());
        new Thread(this::run, "DockPad-autostart").start();
        return START_NOT_STICKY;
    }

    private boolean wifiConnected() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private void run() {
        Prefs prefs = new Prefs(this);
        for (int i = 0; i < 60 && !wifiConnected(); i++) sleep(1000); // wait for Wi-Fi
        Starter.Result r = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            r = Starter.start(this, prefs.turnOffWirelessDebugging());
            if (r.ok || r.needsPairing) break;
            sleep(3000);
        }
        if (r != null && r.ok) {
            try {
                prefs.applyTo(Starter.bridge(this), true);
            } catch (Exception ignored) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            String msg = r == null ? "Unknown problem." : r.message;
            if (!wifiConnected()) msg = "No Wi-Fi connection. DockPad needs Wi-Fi to start. Open DockPad once you're connected.";
            stopForeground(STOP_FOREGROUND_DETACH);
            Notifications.show(this, ID, Notifications.builder(this, "DockPad didn't start", msg)
                    .setAutoCancel(true).build());
        }
        stopSelf();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
