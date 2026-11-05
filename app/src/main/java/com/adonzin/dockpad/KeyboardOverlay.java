package com.adonzin.dockpad;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * DockPad's on-screen keyboard. It floats over every app without taking focus, so the
 * app in front keeps receiving what you type (as real key presses from DockPad's virtual
 * keyboard). Controllers drive it through DockPad's service while it's open.
 */
final class KeyboardOverlay {
    private static KeyboardOverlay current;

    static boolean isOpen() {
        return current != null;
    }

    /** Call on the main thread. */
    static void toggle(Context c) {
        if (current != null) current.close();
        else show(c);
    }

    /** Call on the main thread. */
    static void show(Context c) {
        if (current != null) return;
        Context app = c.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "DockPad needs to restart once to show its keyboard. Turn DockPad off and on.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        current = new KeyboardOverlay(app);
        current.open();
    }

    // label, evdev key code (special keys use negative codes), shifted label
    private static final int CAPS = -1, CLOSE = -2;
    private static final Object[][][] ROWS = {
            {{"1", 2, "!"}, {"2", 3, "@"}, {"3", 4, "#"}, {"4", 5, "$"}, {"5", 6, "%"}, {"6", 7, "^"},
                    {"7", 8, "&"}, {"8", 9, "*"}, {"9", 10, "("}, {"0", 11, ")"}, {"-", 12, "_"}, {"⌫", 14, "⌫"}},
            {{"q", 16, "Q"}, {"w", 17, "W"}, {"e", 18, "E"}, {"r", 19, "R"}, {"t", 20, "T"}, {"y", 21, "Y"},
                    {"u", 22, "U"}, {"i", 23, "I"}, {"o", 24, "O"}, {"p", 25, "P"}, {"=", 13, "+"}},
            {{"a", 30, "A"}, {"s", 31, "S"}, {"d", 32, "D"}, {"f", 33, "F"}, {"g", 34, "G"}, {"h", 35, "H"},
                    {"j", 36, "J"}, {"k", 37, "K"}, {"l", 38, "L"}, {"'", 40, "\""}, {"Enter", 28, "Enter"}},
            {{"⇧ Caps", CAPS, "⇧ CAPS"}, {"z", 44, "Z"}, {"x", 45, "X"}, {"c", 46, "C"}, {"v", 47, "V"},
                    {"b", 48, "B"}, {"n", 49, "N"}, {"m", 50, "M"}, {",", 51, "<"}, {".", 52, ">"}, {"/", 53, "?"}},
            {{"Close", CLOSE, "Close"}, {"Tab", 15, "Tab"}, {"◀", 105, "◀"}, {"Space", 57, "Space"},
                    {"▶", 106, "▶"}, {"Esc", 1, "Esc"}}};

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private LinearLayout root;
    private TextView[][] keys;
    private int row = 1, col = 0;
    private boolean caps;
    private volatile boolean open;
    private volatile IBridge bridge;

    private KeyboardOverlay(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void open() {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.keyboard_panel);
        root.setPadding(dp(10), dp(8), dp(10), dp(10));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = new TextView(ctx);
        hint.setText("A type · B delete · X space · Y caps · L1/R1 move cursor · Start enter · Select close");
        hint.setTextColor(0xFFB8C0CC);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        top.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = new TextView(ctx);
        x.setText("✕ Close");
        x.setTextColor(0xFFFFFFFF);
        x.setTextSize(15);
        x.setTypeface(Typeface.DEFAULT_BOLD);
        x.setGravity(Gravity.CENTER);
        x.setBackgroundResource(R.drawable.key_bg);
        x.setPadding(dp(14), dp(8), dp(14), dp(8));
        x.setOnClickListener(v -> close());
        top.addView(x);
        top.setPadding(0, 0, 0, dp(6));
        root.addView(top);

        keys = new TextView[ROWS.length][];
        for (int r = 0; r < ROWS.length; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            keys[r] = new TextView[ROWS[r].length];
            for (int c = 0; c < ROWS[r].length; c++) {
                TextView k = new TextView(ctx);
                k.setGravity(Gravity.CENTER);
                k.setTextColor(0xFFFFFFFF);
                k.setTextSize(17);
                k.setTypeface(Typeface.DEFAULT_BOLD);
                float weight = "Space".equals(ROWS[r][c][0]) ? 4f : ((String) ROWS[r][c][0]).length() > 2 ? 1.6f : 1f;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), weight);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                final int rr = r, cc = c;
                k.setOnClickListener(v -> {
                    row = rr;
                    col = cc;
                    highlight();
                    press();
                });
                line.addView(k, lp);
                keys[r][c] = k;
            }
            root.addView(line);
        }
        relabel();
        highlight();

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM;
        try {
            wm.addView(root, p);
        } catch (Exception e) {
            Toast.makeText(ctx, "Couldn't show the keyboard: " + e.getMessage(), Toast.LENGTH_LONG).show();
            current = null;
            return;
        }
        open = true;
        showNotification();
        ShizukuLink.connect(() -> new Thread(this::pollLoop, "DockPad-keyboard").start());
    }

    private void pollLoop() {
        IBridge b = BridgeAccess.find(ctx);
        bridge = b;
        if (b == null) {
            ui.post(() -> {
                Toast.makeText(ctx, "DockPad's service isn't running, so the keyboard can't be used.",
                        Toast.LENGTH_LONG).show();
                close();
            });
            return;
        }
        try {
            b.setKeyboardOpen(true);
        } catch (Exception ignored) {
        }
        long lastOk = System.currentTimeMillis();
        while (open) {
            String events;
            try {
                events = b.pollKeyboard();
                lastOk = System.currentTimeMillis();
            } catch (Exception e) {
                // lost contact with the service for 3 s: close rather than get stuck
                events = System.currentTimeMillis() - lastOk > 3000 ? "closed" : "";
            }
            if ("closed".equals(events)) {
                ui.post(this::close);
                return;
            }
            if (!events.isEmpty()) {
                String ev = events;
                ui.post(() -> {
                    for (String e : ev.split(",")) handle(e);
                });
            }
            try {
                Thread.sleep(35);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void handle(String e) {
        if (!open) return;
        switch (e) {
            case "up": move(-1, 0); break;
            case "down": move(1, 0); break;
            case "left": move(0, -1); break;
            case "right": move(0, 1); break;
            case "a": press(); break;
            case "b": type(14, false); break;
            case "x": type(57, false); break;
            case "y": toggleCaps(); break;
            case "l1": type(105, false); break;
            case "r1": type(106, false); break;
            case "start": type(28, false); break;
            case "select": close(); break;
        }
    }

    private void move(int dr, int dc) {
        if (dr != 0) {
            // keep roughly the same horizontal position between rows of different lengths
            float pos = (col + 0.5f) / keys[row].length;
            row = (row + dr + keys.length) % keys.length;
            col = Math.min(keys[row].length - 1, (int) (pos * keys[row].length));
        } else {
            col = (col + dc + keys[row].length) % keys[row].length;
        }
        highlight();
    }

    private void press() {
        int code = (Integer) ROWS[row][col][1];
        if (code == CAPS) toggleCaps();
        else if (code == CLOSE) close();
        else type(code, caps && isShiftable(code));
    }

    /** Letters, digits and symbols take Shift; editing keys never do. */
    private static boolean isShiftable(int code) {
        return code != 14 && code != 28 && code != 15 && code != 57 && code != 105 && code != 106 && code != 1;
    }

    private void toggleCaps() {
        caps = !caps;
        relabel();
    }

    private void type(int code, boolean shift) {
        IBridge b = bridge;
        if (b == null) return;
        new Thread(() -> {
            try {
                b.typeKey(code, shift);
            } catch (Exception ignored) {
            }
        }, "DockPad-type").start();
    }

    private void relabel() {
        for (int r = 0; r < ROWS.length; r++) {
            for (int c = 0; c < ROWS[r].length; c++) {
                keys[r][c].setText((String) ROWS[r][c][caps ? 2 : 0]);
            }
        }
    }

    private void highlight() {
        for (int r = 0; r < keys.length; r++) {
            for (int c = 0; c < keys[r].length; c++) {
                keys[r][c].setBackgroundResource(r == row && c == col ? R.drawable.key_bg_selected : R.drawable.key_bg);
            }
        }
    }

    // ------------------------------------------------------------------ "Close keyboard" notification

    private static final int NOTIFY_ID = 7;
    static final String ACTION_CLOSE = "com.adonzin.dockpad.CLOSE_KEYBOARD";

    private void showNotification() {
        try {
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(ctx, 3,
                    new android.content.Intent(ctx, KeyboardCloseReceiver.class).setAction(ACTION_CLOSE),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification n = Notifications.builder(ctx, "DockPad keyboard is open",
                            "Tap to close it.")
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .addAction(new android.app.Notification.Action.Builder(
                            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_notification),
                            "Close keyboard", pi).build())
                    .build();
            Notifications.show(ctx, NOTIFY_ID, n);
        } catch (Exception ignored) {
        }
    }

    /** Closes whatever keyboard is showing (from the notification, the quick menu or a shortcut). */
    static void closeCurrent() {
        if (current != null) current.close();
    }

    void close() {
        if (current == this) current = null;
        try {
            ctx.getSystemService(android.app.NotificationManager.class).cancel(NOTIFY_ID);
        } catch (Exception ignored) {
        }
        if (!open && root == null) return;
        open = false;
        IBridge b = bridge;
        new Thread(() -> {
            try {
                if (b != null) b.setKeyboardOpen(false);
            } catch (Exception ignored) {
            }
        }, "DockPad-keyboard-close").start();
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
    }
}
