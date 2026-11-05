package com.adonzin.dockpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Watchdog.schedule(context);
        Prefs prefs = new Prefs(context);
        if (prefs.paired() && prefs.autoStart() && prefs.bridgeWanted()) {
            try {
                StarterService.start(context);
            } catch (Exception ignored) {
            }
        }
    }
}
