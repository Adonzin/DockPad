package com.adonzin.dockpad.service;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Bluetooth from the shell process (which may connect/disconnect paired devices). */
@android.annotation.TargetApi(33)
@android.annotation.SuppressLint({"MissingPermission", "NewApi", "BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"})
final class Bt {
    private static final String TAG = "DockPad";

    private Bt() {
    }

    private static BluetoothAdapter adapter() throws Exception {
        AttributionSource src = new AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build();
        Method m;
        try {
            m = BluetoothAdapter.class.getDeclaredMethod("createAdapter", AttributionSource.class);
        } catch (NoSuchMethodException e) {
            m = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(BluetoothAdapter.class,
                    "createAdapter", AttributionSource.class);
        }
        m.setAccessible(true);
        return (BluetoothAdapter) m.invoke(null, src);
    }

    private static boolean isConnected(BluetoothDevice d) {
        try {
            return (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Disconnects the paired Bluetooth controller with this name. Returns null on success, else why not. */
    static String disconnect(String name) {
        if (Build.VERSION.SDK_INT < 33) return "Needs Android 13 or newer";
        try {
            BluetoothAdapter a = adapter();
            if (a == null || !a.isEnabled()) return "Bluetooth is off";
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            List<BluetoothDevice> connected = new ArrayList<>();
            if (bonded != null) for (BluetoothDevice d : bonded) if (isConnected(d)) connected.add(d);
            String want = norm(name);
            BluetoothDevice pick = null;
            for (BluetoothDevice d : connected) if (norm(d.getName()).equals(want)) pick = d;
            if (pick == null) for (BluetoothDevice d : connected) {
                String n = norm(d.getName());
                if (!n.isEmpty() && !want.isEmpty() && (n.contains(want) || want.contains(n))) pick = d;
            }
            if (pick == null) {
                // the system may rename controllers; fall back to the only connected controller-type device
                List<BluetoothDevice> pads = new ArrayList<>();
                for (BluetoothDevice d : connected) {
                    BluetoothClass c = d.getBluetoothClass();
                    if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) pads.add(d);
                }
                if (pads.size() == 1) pick = pads.get(0);
            }
            if (pick == null) return "It isn't a connected Bluetooth controller (USB controllers can just be unplugged)";
            Object r = BluetoothDevice.class.getMethod("disconnect").invoke(pick);
            Log.i(TAG, "Bluetooth disconnect " + pick.getName() + ": " + r);
            if (r instanceof Integer && (Integer) r != 0) return "Bluetooth refused (code " + r + ")";
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.w(TAG, "Bluetooth disconnect", e.getCause());
            return "Couldn't disconnect: " + e.getCause();
        } catch (Throwable t) {
            Log.w(TAG, "Bluetooth disconnect", t);
            return "Couldn't disconnect: " + t;
        }
    }
}
