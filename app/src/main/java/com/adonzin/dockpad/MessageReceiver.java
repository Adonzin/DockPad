package com.adonzin.dockpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Shows short messages on behalf of DockPad's background service (e.g. "Screenshot saved"). */
public class MessageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String m = intent.getStringExtra("message");
        if (m != null && !m.isEmpty()) Toast.makeText(context, m, Toast.LENGTH_SHORT).show();
    }
}
