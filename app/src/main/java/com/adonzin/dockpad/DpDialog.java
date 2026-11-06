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
            if (!ignoreController && d.getWindow() != null
                    && Controller.navigate(e, d.getWindow().getDecorView())) return true;
            if (!Controller.isFromController(e)) return false;
            if (ignoreController) return keyCode != KeyEvent.KEYCODE_BACK;
            return Controller.handle(e, d.getWindow() == null ? null : d.getWindow().getDecorView(),
                    dialog::cancel);
        });
        return d;
    }

    /** Shared rules for turning controller buttons into "select" and "back". */
    static final class Controller {
        /**
         * Wrap-around (up on the first item goes to the last, and back) and L2/R2 to jump to the
         * first/last item, within {@code root}. Returns true if handled.
         */
        static boolean navigate(KeyEvent e, android.view.View root) {
            int kc = e.getKeyCode();
            boolean jump = kc == KeyEvent.KEYCODE_BUTTON_L2 || kc == KeyEvent.KEYCODE_BUTTON_R2;
            boolean upDown = kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN;
            if (root == null || (!jump && !upDown)) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return jump; // swallow L2/R2 releases
            boolean toEnd = kc == KeyEvent.KEYCODE_BUTTON_R2 || kc == KeyEvent.KEYCODE_DPAD_DOWN;
            android.view.View f = root.findFocus();
            // lists inside dialogs move their own selection
            if (f instanceof android.widget.AbsListView) {
                android.widget.AbsListView lv = (android.widget.AbsListView) f;
                int n = lv.getCount(), pos = lv.getSelectedItemPosition();
                if (n == 0) return false;
                if (jump || (e.getRepeatCount() == 0 && ((toEnd && pos == n - 1) || (!toEnd && pos == 0)))) {
                    lv.setSelection(toEnd ? (jump ? n - 1 : 0) : (jump ? 0 : n - 1));
                    return true;
                }
                return false;
            }
            java.util.List<android.view.View> all = new java.util.ArrayList<>();
            for (android.view.View v : root.getFocusables(android.view.View.FOCUS_FORWARD)) {
                if (v.isShown() && v.isEnabled()) all.add(v);
            }
            if (all.isEmpty()) return false;
            android.view.View first = all.get(0), last = all.get(all.size() - 1);
            if (jump) {
                (kc == KeyEvent.KEYCODE_BUTTON_R2 ? last : first).requestFocus();
                return true;
            }
            if (f == null || e.getRepeatCount() != 0) return false;
            android.view.View next = f.focusSearch(toEnd ? android.view.View.FOCUS_DOWN : android.view.View.FOCUS_UP);
            boolean inside = next != null && isInside(next, root);
            if (inside) return false;
            (toEnd ? first : last).requestFocus();
            return true;
        }

        private static boolean isInside(android.view.View v, android.view.View root) {
            for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == root) return true;
            return v == root;
        }

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
