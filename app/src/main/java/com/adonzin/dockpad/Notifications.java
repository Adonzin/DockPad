package com.adonzin.dockpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

final class Notifications {
    static final String CHANNEL = "dockpad";
    private Notifications() {}

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DockPad setup and start-up",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Pairing and automatic start");
            nm.createNotificationChannel(ch);
        }
    }

    static Notification.Builder builder(Context c, String title, String text) {
        ensureChannel(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true);
    }

    static void foreground(Service s, int id, Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            s.startForeground(id, n);
        }
    }

    static void show(Context c, int id, Notification n) {
        try {
            c.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }
}
