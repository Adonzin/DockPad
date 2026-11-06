package com.adonzin.dockpad.service;

import android.os.Binder;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.InputDevice;

import com.adonzin.dockpad.core.EvdevDevice;


/**
 * Rumble pass-through: makes real controllers rumble (through Android, like a game would) and
 * watches the handheld's own motor, which games use for player 1.
 */
@android.annotation.SuppressLint({"NewApi", "MissingPermission", "PrivateApi", "DiscouragedPrivateApi",
        "BlockedPrivateApi", "SoonBlockedPrivateApi"})
final class Rumble {
    private static final String TAG = "DockPad";
    /** One rumble lasts at most this long unless renewed (in case a stop never arrives). */
    private static final long MAX_MS = 8000;

    private Rumble() {
    }

    /** Starts or stops rumble on a real controller. Returns false if Android can't rumble it. */
    /**
     * The Android device that can rumble for this controller: itself, or (on Retroid devices, which
     * re-create Bluetooth controllers without rumble) the original device with the same name.
     */
    static InputDevice rumbleDevice(EvdevDevice dev) {
        InputDevice best = null;
        String want = Battery.norm(dev.name);
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || VirtualPad.isOurs(d.getName())) continue;
            if (d.getVibratorManager().getVibratorIds().length == 0) continue;
            String n = Battery.norm(d.getName());
            boolean same = d.getVendorId() == dev.vendor && d.getProductId() == dev.product && n.equals(want);
            if (same) return d;
            if (best == null && !n.isEmpty() && !want.isEmpty()
                    && (n.equals(want) || n.startsWith(want) || want.startsWith(n))) best = d;
        }
        return best;
    }

    static boolean set(EvdevDevice dev, boolean on) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            InputDevice d = rumbleDevice(dev);
            if (d == null) return false;
            android.os.VibratorManager vm = d.getVibratorManager();
            if (on) {
                vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(MAX_MS, 200)));
            } else {
                vm.cancel();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "rumble " + dev.name, t);
            return false;
        }
    }

    static boolean canRumble(EvdevDevice dev) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return rumbleDevice(dev) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the handheld's motor

    interface Listener {
        void vibrating(boolean on);
    }

    private static final String LISTENER = "android.os.IVibratorStateListener";
    private static final String MANAGER = "android.os.IVibratorManagerService";

    /** Receives "vibrating on/off" from the system's vibrator service. */
    private static final class StateListener extends Binder {
        final Listener l;

        StateListener(Listener l) {
            this.l = l;
            attachInterface(null, LISTENER);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == FIRST_CALL_TRANSACTION) { // onVibrating(boolean)
                data.enforceInterface(LISTENER);
                l.vibrating(data.readInt() != 0);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static StateListener registered;

    private static int constant(Class<?> c, String name) throws Exception {
        java.lang.reflect.Field f;
        try {
            f = c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = null;
        }
        if (f == null) {
            for (java.lang.reflect.Field x : org.lsposed.hiddenapibypass.HiddenApiBypass.getStaticFields(c)) {
                if (x.getName().equals(name)) f = x;
            }
            if (f == null) throw new NoSuchFieldException(name);
        }
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** Watches the handheld's motor. Returns null when watching, else why not. */
    static synchronized String watchHandheld(Listener l) {
        if (registered != null) return null;
        try {
            IBinder svc = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "vibrator_manager");
            if (svc == null) return "no vibrator service";
            Class<?> stub = Class.forName(MANAGER + "$Stub");
            int idsCode = constant(stub, "TRANSACTION_getVibratorIds");
            int regCode = constant(stub, "TRANSACTION_registerVibratorStateListener");

            int[] ids;
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(MANAGER);
                svc.transact(idsCode, d, r, 0);
                r.readException();
                ids = r.createIntArray();
            } finally {
                d.recycle();
                r.recycle();
            }
            if (ids == null || ids.length == 0) return "no vibrators";
            StateListener listener = new StateListener(l);
            for (int id : ids) {
                Parcel d2 = Parcel.obtain(), r2 = Parcel.obtain();
                try {
                    d2.writeInterfaceToken(MANAGER);
                    d2.writeInt(id);
                    d2.writeStrongBinder(listener);
                    svc.transact(regCode, d2, r2, 0);
                    r2.readException();
                } finally {
                    d2.recycle();
                    r2.recycle();
                }
            }
            registered = listener;
            Log.i(TAG, "Watching the handheld's vibrator " + java.util.Arrays.toString(ids));
            return null;
        } catch (Throwable t) {
            while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
            Log.w(TAG, "vibrator watch", t);
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }
}
