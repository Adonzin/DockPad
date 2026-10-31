package com.adonzin.dockpad.service;

import android.os.Build;
import android.view.InputEvent;

import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Access to non-public Android APIs. Only works inside the Shizuku service process,
 * which runs with ADB ("shell") privileges.
 */
final class Hidden {
    private Hidden() {}

    private static Object inputManager;
    private static Method injectMethod;

    static void exemptHiddenApis() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
            } catch (Throwable ignored) {
            }
        }
    }

    static synchronized Object inputManager() throws Exception {
        if (inputManager != null) return inputManager;
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal"); // Android 14+
            inputManager = g.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            inputManager = c.getMethod("getInstance").invoke(null);
        }
        return inputManager;
    }

    /** Injects an event asynchronously. Returns false if Android rejected it. */
    static boolean inject(InputEvent event) throws Exception {
        Object im = inputManager();
        if (injectMethod == null) {
            injectMethod = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        Object r = injectMethod.invoke(im, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    /** Enables/disables an Android input device (fallback way to hide it from apps). */
    static boolean setDeviceEnabled(int deviceId, boolean enabled) {
        try {
            Object im = inputManager();
            Method m = im.getClass().getMethod(enabled ? "enableInputDevice" : "disableInputDevice", int.class);
            m.invoke(im, deviceId);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int EVIOCGRAB = 0x40044590;

    /**
     * Takes exclusive control of an evdev device, so Android (and every app) stops receiving
     * its events. Released automatically when the file is closed or the process dies.
     * Returns null on success, or an error description.
     */
    static String grab(FileDescriptor fd) {
        StringBuilder errors = new StringBuilder();
        Object[][] candidates;
        try {
            Field f = Class.forName("libcore.io.Libcore").getField("os");
            candidates = new Object[][]{{null, Class.forName("android.system.Os")}, {f.get(null), f.get(null).getClass()}};
        } catch (Throwable t) {
            try {
                candidates = new Object[][]{{null, Class.forName("android.system.Os")}};
            } catch (ClassNotFoundException e) {
                return "no Os class";
            }
        }
        for (Object[] c : candidates) {
            Object target = c[0];
            Class<?> cls = (Class<?>) c[1];
            for (Method m : cls.getMethods()) {
                if (!m.getName().equals("ioctlInt")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 2 && p[0] == FileDescriptor.class && p[1] == int.class) {
                        m.invoke(target, fd, EVIOCGRAB);
                        return null;
                    }
                    if (p.length == 3 && p[0] == FileDescriptor.class && p[1] == int.class) {
                        Object ref = newIntRef(p[2]);
                        m.invoke(target, fd, EVIOCGRAB, ref);
                        return null;
                    }
                } catch (InvocationTargetException e) {
                    errors.append(m).append(": ").append(e.getCause()).append("; ");
                } catch (Throwable e) {
                    errors.append(m).append(": ").append(e).append("; ");
                }
            }
        }
        return errors.length() == 0 ? "ioctl not available" : errors.toString();
    }

    private static Object newIntRef(Class<?> type) throws Exception {
        Constructor<?> k = type.getDeclaredConstructor(int.class);
        k.setAccessible(true);
        return k.newInstance(1);
    }
}
