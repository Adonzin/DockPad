package com.adonzin.dockpad;

import android.app.Dialog;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.Shortcut;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The first-run guide: a few full-screen steps from "what is this" to a working controller and
 * quick menu. Works with a controller (A selects, B goes back, L1/R1 step through). Each step's
 * checks tick themselves as things get done, also after visiting Android's settings.
 */
final class FirstRunGuide {
    static final String EDITOR_URL = "adonzin.github.io/DockPad/tools/theme-editor.html";
    private static final int STEPS = 9;

    private final MainActivity a;
    private Dialog dialog;
    private int step;
    private TextView counter;
    private LinearLayout body, status;
    private MaterialButton back, next;
    private long stepStarted;

    FirstRunGuide(MainActivity a) {
        this.a = a;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void show() {
        if (isShowing()) return;
        dialog = new Dialog(a, R.style.DockPad_Guide);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(16), dp(28), dp(14));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        counter = new TextView(a);
        counter.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        top.addView(counter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton skip = button("Skip guide", R.style.ThemeOverlay_DockPad_Button_Text);
        skip.setOnClickListener(v -> finish());
        top.addView(skip);
        root.addView(top);

        ScrollView scroll = new ScrollView(a);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(6), 0, dp(10));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        back = button("Back", R.style.ThemeOverlay_DockPad_Button_Outlined);
        back.setOnClickListener(v -> go(step - 1));
        bottom.addView(back);
        TextView hint = new TextView(a);
        hint.setText("A: select · B: back · L1/R1: steps");
        hint.setGravity(Gravity.CENTER);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        bottom.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        next = button("Next", R.style.ThemeOverlay_DockPad_Button);
        next.setOnClickListener(v -> {
            if (step == STEPS - 1) finish();
            else go(step + 1);
        });
        bottom.addView(next);
        root.addView(bottom);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color(com.google.android.material.R.attr.colorSurface)));
        }
        dialog.setCancelable(false);
        dialog.setOnKeyListener((d, kc, e) -> {
            View decor = w == null ? null : w.getDecorView();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
            if (kc == KeyEvent.KEYCODE_BUTTON_L1 || kc == KeyEvent.KEYCODE_BUTTON_R1) {
                if (down) go(step + (kc == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1));
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) go(step - 1);
                return true;
            }
            if (DpDialog.Controller.navigate(e, decor)) return true;
            if (!DpDialog.Controller.isFromController(e)) return false;
            return DpDialog.Controller.handle(e, decor, () -> go(step - 1));
        });
        dialog.show();
        step = 0;
        render();
    }

    private void finish() {
        a.prefs().guideSeen(true);
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void go(int to) {
        if (to < 0 || to >= STEPS || to == step) return;
        step = to;
        render();
    }

    /** Called when DockPad's screen comes back and on each status update: re-checks this step. */
    void refresh() {
        if (isShowing()) fillStatus();
    }

    // ------------------------------------------------------------------ steps

    private void render() {
        body.removeAllViews();
        stepStarted = System.currentTimeMillis();
        counter.setText(step == 0 ? "Welcome" : "Step " + step + " of " + (STEPS - 2));
        if (step == STEPS - 1) counter.setText("Done");
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == 0 ? "Start setup" : step == STEPS - 1 ? "Finish" : "Next");
        status = null;
        View first = null;
        switch (step) {
            case 0:
                title("Welcome to DockPad");
                text("Any controller works exactly like the built-in controls: in every game and emulator, "
                        + "with nothing to remap.");
                bullet("A quick menu over any game: Home, screenshots, controllers, your own commands.");
                bullet("Shortcuts: button combos for anything you do often.");
                bullet("Multiplayer: each extra controller is its own player.");
                text("Setup takes about two minutes and is only needed once.");
                break;
            case 1:
                title("Turn on Developer options");
                text("DockPad uses Android's developer tools to read controllers (no root needed).");
                numbered(1, "Open About, below.");
                numbered(2, "Tap \"Build number\" 7 times.");
                numbered(3, "Come back here: this step ticks itself.");
                status();
                first = action("Open About", () -> a.openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));
                break;
            case 2:
                title("Connect to Wi-Fi");
                text("Wireless debugging, which DockPad starts through, only works over Wi-Fi.");
                text("On Ethernet (a dock with a network port)? Wi-Fi is still needed, but only while DockPad "
                        + "starts: join any network, even a phone hotspot without internet. Once DockPad is on, "
                        + "Ethernet alone is fine.");
                text("Tip: if your router has a 5 GHz network, use it. Bluetooth controllers share the 2.4 GHz "
                        + "band with Wi-Fi and stay smoother on 5 GHz.");
                status();
                first = action("Open Wi-Fi settings", () -> a.openSettings(Settings.ACTION_WIFI_SETTINGS));
                break;
            case 3:
                title("Pair DockPad (one time)");
                text("This lets DockPad start itself through Wireless debugging. You only do it once.");
                numbered(1, "Press Pair. DockPad's pairing screen opens, with Wireless debugging next to it.");
                numbered(2, "Turn on Wireless debugging, open it and choose \"Pair device with pairing code\".");
                numbered(3, "Type the 6-digit code into DockPad. It connects and turns on by itself.");
                status();
                first = action("Pair", a::beginPairing);
                if (a.hasPairingKey()) action("Paired before? Connect without pairing", a::connectWithoutPairing);
                if (a.shizukuInstalled()) small("You can also start DockPad through Shizuku: open Shizuku and allow DockPad.");
                break;
            case 4:
                title("Turn DockPad on");
                text("DockPad runs in the background while you play.");
                status();
                first = action("Turn on", a::turnOn);
                MaterialSwitch auto = new MaterialSwitch(a);
                auto.setText("Start automatically after a restart");
                auto.setChecked(a.prefs().autoStart());
                auto.setOnCheckedChangeListener((b, on) -> a.prefs().autoStart(on));
                auto.setForeground(a.getDrawable(R.drawable.focus_ring));
                auto.setPadding(dp(6), dp(10), dp(6), dp(10));
                body.addView(auto);
                break;
            case 5:
                title("Connect a controller");
                text("Connect a controller over Bluetooth or USB, then press any button on it: its row below shows "
                        + "the button. With more than one controller, each one presses A to join as the next player.");
                status();
                first = action("Pair a new controller", () -> PairDialog.show(a));
                break;
            case 6:
                title("Your quick-menu button");
                text("A button combo opens the quick menu over any game. Choose one you won't press while "
                        + "playing, like Select + Start held together.");
                status();
                first = action(quickMenuCombo() == null ? "Choose the combo" : "Change the combo", a::editQuickMenuCombo);
                text("Then try it: press the combo on your controller. When you close the menu you'll be back here.");
                break;
            case 7:
                title("Make it yours (optional)");
                text("Change how the quick menu looks with a theme, or get one someone else made.");
                first = action("Choose a theme", a::chooseTheme);
                action("Get themes from the catalog", a::getThemes);
                text("To design your own on a computer, open this address in its browser:");
                TextView url = text(EDITOR_URL);
                url.setTextIsSelectable(true);
                url.setTextColor(color(androidx.appcompat.R.attr.colorPrimary));
                break;
            default:
                title("All set");
                status();
                text("Everything lives in the tabs at the top (L1/R1 switch tabs). You can open this guide again "
                        + "from Settings.");
                break;
        }
        fillStatus();
        View focus = first != null ? first : next;
        focus.post(focus::requestFocus);
    }

    /** The live checks of the current step. */
    private void fillStatus() {
        if (status == null) return;
        status.removeAllViews();
        JSONObject o = a.lastStatus();
        boolean running = a.isRunning();
        switch (step) {
            case 1: {
                boolean on = Settings.Global.getInt(a.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
                check(on, on ? "Developer options are on" : "Developer options aren't on yet");
                break;
            }
            case 2: {
                boolean wifi = a.wifiConnected();
                check(wifi, wifi ? "Connected to Wi-Fi"
                        : com.adonzin.dockpad.adb.Starter.ethernetConnected(a) ? "Ethernet only: join Wi-Fi as well while setting up"
                        : "Not connected to Wi-Fi");
                break;
            }
            case 3:
                check(a.isReady(), a.isReady() ? "DockPad is paired" : "Waiting for pairing…");
                break;
            case 4:
                check(running, running ? "DockPad is on" : a.isReady() ? "DockPad is off" : "Pair DockPad first (previous step)");
                break;
            case 5: {
                int n = 0;
                JSONArray ctrls = o == null ? null : o.optJSONArray("controllers");
                if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
                    JSONObject c = ctrls.optJSONObject(i);
                    if (c == null || c.optBoolean("builtIn") || c.optBoolean("touchpad")) continue;
                    n++;
                    int p = c.optInt("player");
                    String last = c.optString("lastButton");
                    check(true, a.prefs().controllerName(c.optString("name"))
                            + (p > 0 ? " · Player " + p : " · press A to join")
                            + (last.isEmpty() ? "" : " · pressed " + last));
                }
                if (!running) check(false, "Turn DockPad on first (previous step)");
                else if (n == 0) check(false, "No controller connected yet");
                break;
            }
            case 6: {
                String combo = quickMenuCombo();
                check(combo != null, combo == null ? "No combo chosen yet" : "Quick menu: " + combo);
                if (QuickMenuActivity.lastOpened > stepStarted) check(true, "The quick menu opened. It works!");
                break;
            }
            default: {
                int n = 0;
                JSONArray ctrls = o == null ? null : o.optJSONArray("controllers");
                if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
                    JSONObject c = ctrls.optJSONObject(i);
                    if (c != null && !c.optBoolean("builtIn") && !c.optBoolean("touchpad")) n++;
                }
                check(running, running ? "DockPad is on" : "DockPad is off: turn it on from the Home tab");
                check(n > 0, n > 0 ? (n == 1 ? "1 controller connected" : n + " controllers connected")
                        : "No controller connected right now");
                String combo = quickMenuCombo();
                check(combo != null, combo != null ? "Quick menu: " + combo : "No quick-menu combo yet (Quick menu tab)");
            }
        }
    }

    private String quickMenuCombo() {
        for (Shortcut s : a.prefs().shortcuts()) {
            if (!s.steps.isEmpty() && s.steps.get(0).length > 0 && s.steps.get(0)[0] == Codes.ACTION_QUICK_MENU) {
                return (s.hold ? "hold " : "") + s.triggerLabel();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ building blocks

    private void title(String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        v.setPadding(0, dp(4), 0, dp(8));
        body.addView(v);
    }

    private TextView text(String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        v.setPadding(0, dp(4), 0, dp(4));
        body.addView(v);
        return v;
    }

    private void small(String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        v.setPadding(0, dp(8), 0, dp(4));
        body.addView(v);
    }

    private void bullet(String t) {
        text("•  " + t).setPadding(dp(8), dp(2), 0, dp(2));
    }

    private void numbered(int n, String t) {
        text(n + ".  " + t).setPadding(dp(8), dp(2), 0, dp(2));
    }

    private void status() {
        status = new LinearLayout(a);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(0, dp(8), 0, dp(4));
        body.addView(status);
    }

    private void check(boolean done, String t) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        ImageView icon = new ImageView(a);
        icon.setImageResource(done ? R.drawable.ic_check : R.drawable.ic_pending);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color(
                done ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant)));
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView v = new TextView(a);
        v.setText(t);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setMaxLines(2);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        v.setPadding(dp(10), 0, 0, 0);
        row.addView(v);
        status.addView(row);
    }

    private MaterialButton action(String label, Runnable r) {
        MaterialButton b = button(label, R.style.ThemeOverlay_DockPad_Button_Tonal);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.addView(b, lp);
        return b;
    }

    private MaterialButton button(String label, int style) {
        MaterialButton b = new MaterialButton(new android.view.ContextThemeWrapper(a, style));
        b.setText(label);
        b.setAllCaps(false);
        b.setFocusable(true);
        return b;
    }

    private int color(int attr) {
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
