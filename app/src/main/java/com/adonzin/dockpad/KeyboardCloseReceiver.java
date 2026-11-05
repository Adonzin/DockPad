package com.adonzin.dockpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Close keyboard" from DockPad's own notification. */
public class KeyboardCloseReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KeyboardOverlay.closeCurrent();
    }
}
