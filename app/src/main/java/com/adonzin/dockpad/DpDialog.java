package com.adonzin.dockpad;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialogs that work with a controller: A selects, B goes back. Dialogs that wait for a
 * button press ("Press your button…") can ignore controller buttons entirely, so the press
 * being recorded doesn't also press something on screen.
 */
final class DpDialog extends MaterialAlertDialogBuilder {
    private boolean ignoreController;

    DpDialog(@NonNull Context context) {
        super(context);
    }

    /** For dialogs that are waiting for a button press to record. */
    DpDialog ignoreControllerButtons() {
        ignoreController = true;
        return this;
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog d = super.create();
        d.setOnKeyListener((dialog, keyCode, e) -> {
            if (!Controller.isFromController(e)) return false;
            if (ignoreController) return keyCode != KeyEvent.KEYCODE_BACK;
            return Controller.handle(e, d.getWindow() == null ? null : d.getWindow().getDecorView(),
                    dialog::cancel);
        });
        return d;
    }

    /** Shared rules for turning controller buttons into "select" and "back". */
    static final class Controller {
        static boolean isFromController(KeyEvent e) {
            int s = e.getSource();
            return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        }

        /**
         * A → the same as pressing the select (centre) key on whatever is highlighted.
         * B → back. Returns true if handled.
         */
        static boolean handle(KeyEvent e, android.view.View root, Runnable back) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BUTTON_A && root != null) {
                KeyEvent center = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount(), e.getMetaState(),
                        e.getDeviceId(), e.getScanCode(), e.getFlags(), InputDevice.SOURCE_DPAD);
                return root.dispatchKeyEvent(center);
            }
            if (kc == KeyEvent.KEYCODE_BUTTON_B) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back.run();
                return true;
            }
            return false;
        }
    }
}
