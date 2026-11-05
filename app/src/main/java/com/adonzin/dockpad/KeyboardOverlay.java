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

    // ------------------------------------------------------------------ layout

    /** A key: what it shows, what it types. */
    private static final class Key {
        final String label, capsLabel;
        final int code;          // evdev key code, or a special (< 0)
        final boolean shift;     // always typed with Shift (symbols)
        final float weight;

        Key(String label, String capsLabel, int code, boolean shift, float weight) {
            this.label = label;
            this.capsLabel = capsLabel;
            this.code = code;
            this.shift = shift;
            this.weight = weight;
        }
    }

    private static final int CAPS = -1, CLOSE = -2, PAGE = -3;
    private static final int CTRL = 29, SHIFT = 42, ALT = 56, WIN = 125; // sticky modifiers on the F page
    private static final String[] PAGE_NAMES = {"abc", "Sym", "Fn"};

    private static Key k(String l, int code) { return new Key(l, l, code, false, 1f); }
    private static Key k(String l, String caps, int code) { return new Key(l, caps, code, false, 1f); }
    private static Key s(String l, int code) { return new Key(l, l, code, true, 1f); }
    private static Key w(String l, int code, float weight) { return new Key(l, l, code, false, weight); }

    private static Key[] bottom(String pageKey) {
        return new Key[]{w(pageKey, PAGE, 1.6f), w("Tab", 15, 1.3f), k("◀", 105), w("Space", 57, 4f), k("▶", 106),
                w("Esc", 1, 1.3f)};
    }

    private static final Key[][][] PAGES = {
            { // letters
                    {k("1", "!", 2), k("2", "@", 3), k("3", "#", 4), k("4", "$", 5), k("5", "%", 6), k("6", "^", 7),
                            k("7", "&", 8), k("8", "*", 9), k("9", "(", 10), k("0", ")", 11), k("-", "_", 12),
                            w("⌫", 14, 1.3f)},
                    {k("q", "Q", 16), k("w", "W", 17), k("e", "E", 18), k("r", "R", 19), k("t", "T", 20),
                            k("y", "Y", 21), k("u", "U", 22), k("i", "I", 23), k("o", "O", 24), k("p", "P", 25),
                            k("=", "+", 13)},
                    {k("a", "A", 30), k("s", "S", 31), k("d", "D", 32), k("f", "F", 33), k("g", "G", 34),
                            k("h", "H", 35), k("j", "J", 36), k("k", "K", 37), k("l", "L", 38), k("'", "\"", 40),
                            w("Enter", 28, 1.6f)},
                    {new Key("⇧ Caps", "⇧ CAPS", CAPS, false, 1.6f), k("z", "Z", 44), k("x", "X", 45),
                            k("c", "C", 46), k("v", "V", 47), k("b", "B", 48), k("n", "N", 49), k("m", "M", 50),
                            k(",", "<", 51), k(".", ">", 52), k("/", "?", 53)},
                    bottom("Sym ▸")},
            { // symbols
                    {s("!", 2), s("@", 3), s("#", 4), s("$", 5), s("%", 6), s("^", 7), s("&", 8), s("*", 9),
                            s("(", 10), s(")", 11), w("⌫", 14, 1.3f)},
                    {k("`", 41), s("~", 41), k("-", 12), s("_", 12), k("=", 13), s("+", 13), k("[", 26), k("]", 27),
                            s("{", 26), s("}", 27), k("\\", 43), s("|", 43)},
                    {k(";", 39), s(":", 39), k("'", 40), s("\"", 40), k(",", 51), s("<", 51), k(".", 52), s(">", 52),
                            k("/", 53), s("?", 53), w("Enter", 28, 1.6f)},
                    bottom("Fn ▸")},
            { // function keys, editing keys, sticky modifiers
                    {k("F1", 59), k("F2", 60), k("F3", 61), k("F4", 62), k("F5", 63), k("F6", 64), w("⌫", 14, 1.3f)},
                    {k("F7", 65), k("F8", 66), k("F9", 67), k("F10", 68), k("F11", 87), k("F12", 88), w("Del", 111, 1.3f)},
                    {k("Ctrl", CTRL), k("Alt", ALT), k("Win", WIN), k("Shift", SHIFT), w("PrtSc", 99, 1.2f),
                            k("Ins", 110), w("Enter", 28, 1.3f)},
                    {k("Home", 102), k("End", 107), k("PgUp", 104), k("PgDn", 109), k("▲", 103), k("▼", 108)},
                    bottom("abc ▸")}};

    private static boolean isModifier(int code) {
        return code == CTRL || code == SHIFT || code == ALT || code == WIN;
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private LinearLayout root, grid;
    private TextView closeKey;
    private TextView[][] keys; // row 0 is the top bar ([✕ Close]); the page's rows follow
    private int page, row = 2, col = 0;
    private boolean caps;
    private final java.util.Set<Integer> mods = new java.util.LinkedHashSet<>();
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
        hint.setText("A type · B delete · X space · Y caps · L1/R1 cursor · L2/R2 page · Start enter · Select close");
        hint.setTextColor(0xFFB8C0CC);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        top.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        closeKey = new TextView(ctx);
        closeKey.setText("✕ Close");
        closeKey.setTextColor(0xFFFFFFFF);
        closeKey.setTextSize(15);
        closeKey.setTypeface(Typeface.DEFAULT_BOLD);
        closeKey.setGravity(Gravity.CENTER);
        closeKey.setPadding(dp(14), dp(8), dp(14), dp(8));
        closeKey.setOnClickListener(v -> close());
        top.addView(closeKey);
        top.setPadding(0, 0, 0, dp(6));
        root.addView(top);

        grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid);
        buildPage();

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

    private void buildPage() {
        grid.removeAllViews();
        Key[][] rows = PAGES[page];
        keys = new TextView[rows.length + 1][];
        keys[0] = new TextView[]{closeKey};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            keys[r + 1] = new TextView[rows[r].length];
            for (int c = 0; c < rows[r].length; c++) {
                TextView t = new TextView(ctx);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(17);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), rows[r][c].weight);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                final int rr = r + 1, cc = c;
                t.setOnClickListener(v -> {
                    row = rr;
                    col = cc;
                    refresh();
                    press();
                });
                line.addView(t, lp);
                keys[r + 1][c] = t;
            }
            grid.addView(line);
        }
        row = Math.min(row, keys.length - 1);
        col = Math.min(col, keys[row].length - 1);
        refresh();
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
            case "y": caps = !caps; refresh(); break;
            case "l1": type(105, false); break;
            case "r1": type(106, false); break;
            case "l2": switchPage(-1); break;
            case "r2": switchPage(1); break;
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
            if (row == 0) col = 0; // the top bar only has Close
        } else {
            col = (col + dc + keys[row].length) % keys[row].length;
        }
        refresh();
    }

    private void switchPage(int delta) {
        page = (page + delta + PAGES.length) % PAGES.length;
        buildPage();
    }

    private Key selectedKey() {
        return row == 0 ? null : PAGES[page][row - 1][col];
    }

    private void press() {
        if (row == 0) {
            close();
            return;
        }
        Key key = selectedKey();
        if (key.code == CAPS) {
            caps = !caps;
            refresh();
        } else if (key.code == PAGE) {
            switchPage(1);
        } else if (isModifier(key.code)) {
            if (!mods.remove(key.code)) mods.add(key.code); // sticky: applies to the next key
            refresh();
        } else {
            boolean shift = key.shift || (caps && isShiftable(key.code) && page == 0);
            if (mods.isEmpty()) {
                type(key.code, shift);
            } else {
                StringBuilder combo = new StringBuilder();
                for (int m : mods) combo.append(m).append(',');
                if (shift && !mods.contains(SHIFT)) combo.append(SHIFT).append(',');
                combo.append(key.code);
                typeCombo(combo.toString());
                mods.clear();
                refresh();
            }
        }
    }

    /** Letters, digits and symbols take Shift; editing keys never do. */
    private static boolean isShiftable(int code) {
        return code != 14 && code != 28 && code != 15 && code != 57 && code != 105 && code != 106 && code != 1;
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

    private void typeCombo(String csv) {
        IBridge b = bridge;
        if (b == null) return;
        new Thread(() -> {
            try {
                b.typeCombo(csv);
            } catch (Exception ignored) {
            }
        }, "DockPad-type").start();
    }

    /** Labels (caps), highlight (selected key) and active modifiers. */
    private void refresh() {
        closeKey.setBackgroundResource(row == 0 ? R.drawable.key_bg_selected : R.drawable.key_bg);
        Key[][] rows = PAGES[page];
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                Key key = rows[r][c];
                TextView t = keys[r + 1][c];
                t.setText(caps && page == 0 ? key.capsLabel : key.label);
                boolean selected = row == r + 1 && col == c;
                boolean active = (isModifier(key.code) && mods.contains(key.code)) || (key.code == CAPS && caps);
                t.setBackgroundResource(active
                        ? (selected ? R.drawable.key_bg_active_selected : R.drawable.key_bg_active)
                        : (selected ? R.drawable.key_bg_selected : R.drawable.key_bg));
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
