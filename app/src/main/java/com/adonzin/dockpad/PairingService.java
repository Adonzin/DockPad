package com.adonzin.dockpad;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.adonzin.dockpad.adb.AdbManager;
import com.adonzin.dockpad.adb.Starter;

import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * One-time pairing with Wireless debugging. Finds the pairing port automatically and asks
 * for the 6-digit code in a notification, so the Wireless debugging screen can stay open.
 */
public class PairingService extends Service {
    static final String ACTION_START = "start", ACTION_CODE = "code", ACTION_STOP = "stop";
    private static final int ID = 2;
    private static final String KEY_CODE = "pairing_code";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AdbMdns mdns;
    private volatile int port = -1;
    private volatile InetAddress host;
    private volatile boolean busy;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, PairingService.class).setAction(ACTION_START));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finish();
            return START_NOT_STICKY;
        }
        if (ACTION_CODE.equals(action)) {
            Bundle in = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = in == null ? null : in.getCharSequence(KEY_CODE);
            Notifications.foreground(this, ID, waiting("Pairing…", "Checking the code.", false));
            if (code != null) pair(code.toString().trim());
            return START_NOT_STICKY;
        }
        Notifications.foreground(this, ID, waiting("Waiting for the pairing code",
                "In Developer options → Wireless debugging, tap \"Pair device with pairing code\".", false));
        if (mdns == null) {
            mdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (h, p) -> {
                host = h;
                port = p;
                if (p > 0 && !busy) ui.post(() -> Notifications.show(this, ID,
                        waiting("Enter the pairing code", "Type the 6-digit code shown under \"Pair with device\".", true)));
            });
            mdns.start();
        }
        ui.postDelayed(this::finish, 10 * 60 * 1000); // give up after 10 minutes
        return START_NOT_STICKY;
    }

    /** Android's developer settings, scrolled to Wireless debugging. */
    static Intent wirelessDebuggingIntent() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless"); // highlights Wireless debugging
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:show_fragment_args", args);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** A notification button that opens Wireless debugging. */
    static Notification.Action openWirelessDebuggingAction(Context c) {
        PendingIntent open = PendingIntent.getActivity(c, 4, wirelessDebuggingIntent(),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notification),
                "Open Wireless debugging", open).build();
    }

    private Notification waiting(String title, String text, boolean askForCode) {
        Notification.Builder b = Notifications.builder(this, title, text).setOngoing(true);
        b.addAction(openWirelessDebuggingAction(this));
        if (askForCode) {
            PendingIntent reply = PendingIntent.getForegroundService(this, 1,
                    new Intent(this, PairingService.class).setAction(ACTION_CODE),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build();
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), "Enter code", reply)
                    .addRemoteInput(input).build());
        }
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, PairingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification), "Cancel", cancel).build());
        return b.build();
    }

    private void pair(String code) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            Prefs prefs = new Prefs(this);
            try {
                int p = port;
                if (p <= 0) throw new Exception("The pairing screen isn't open. Open \"Pair device with pairing code\" again.");
                String h = host == null ? "127.0.0.1" : host.getHostAddress();
                boolean ok = AdbManager.get(this).pair(h, p, code);
                if (!ok) throw new Exception("Pairing was refused.");
                prefs.paired(true);
                ui.post(() -> Notifications.show(this, ID, Notifications.builder(this, "Paired!",
                        "Starting DockPad…").setOngoing(true).build()));
                if (mdns != null) mdns.stop();
                Starter.Result r = Starter.start(this, prefs.turnOffWirelessDebugging());
                if (r.ok) {
                    prefs.bridgeWanted(true);
                    try {
                        prefs.applyTo(Starter.bridge(this), true);
                    } catch (Exception ignored) {
                    }
                }
                String msg = r.ok ? "DockPad is set up and running. You won't need to pair again."
                        : "Paired, but starting failed: " + r.message;
                ui.post(() -> {
                    stopForeground(STOP_FOREGROUND_DETACH);
                    Notification.Builder nb = Notifications.builder(this,
                            r.ok ? "DockPad is ready" : "DockPad paired", msg).setAutoCancel(true);
                    if (!r.ok && (r.problem == Starter.Problem.WIRELESS_DEBUGGING_OFF
                            || r.problem == Starter.Problem.NETWORK_NOT_ALLOWED)) {
                        nb.addAction(openWirelessDebuggingAction(this));
                    }
                    Notifications.show(this, ID, nb.build());
                    finish();
                });
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                busy = false;
                ui.post(() -> Notifications.show(this, ID, waiting("Pairing failed",
                        m + " Check the code and try again.", port > 0)));
            }
        }, "DockPad-pair").start();
    }

    private void finish() {
        if (mdns != null) {
            try {
                mdns.stop();
            } catch (Exception ignored) {
            }
            mdns = null;
        }
        ui.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
