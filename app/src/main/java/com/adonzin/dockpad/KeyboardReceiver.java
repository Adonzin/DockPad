package com.adonzin.dockpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** DockPad's service asks the app to show the keyboard (from a shortcut). */
public class KeyboardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (KeyboardOverlay.ACTION_CLOSE.equals(intent.getAction())) KeyboardOverlay.closeCurrent();
        else KeyboardOverlay.show(context);
    }
}
