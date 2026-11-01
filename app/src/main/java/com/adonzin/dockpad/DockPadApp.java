package com.adonzin.dockpad;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class DockPadApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // The built-in ADB client uses Android's own TLS library, which needs this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L");
            } catch (Throwable ignored) {
            }
        }
    }
}
