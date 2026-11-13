package com.adonzin.dockpad;

import android.Manifest;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adonzin.dockpad.adb.AdbManager;
import com.adonzin.dockpad.adb.Starter;
import com.google.android.material.button.MaterialButton;

import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Connecting and pairing, all in one screen: live checks of what Android needs, the pairing code
 * typed right here (with Wireless debugging opened side by side), and a precise fix for each way
 * starting can fail. Things that get fixed outside DockPad (Wi-Fi joined, Wireless debugging
 * allowed) are noticed and retried by themselves.
 */
final class ConnectDialog {
    private static ConnectDialog open;

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private LinearLayout checks, body, actions;
    private TextView title, stage, problemText;
    private ProgressBar spinner;
    private EditText code;
    private TextView pairState;

    private boolean pairing; // showing the pairing steps
    private volatile boolean busy;
    private Starter.Problem problem = Starter.Problem.NONE;
    private boolean waitedFor; // a problem we retry by ourselves once it's fixed outside DockPad
    private AdbMdns mdns;
    private volatile int pairPort = -1;
    private volatile InetAddress pairHost;
    private boolean triedSideBySide;
    private boolean pairedAtStart;

    private ConnectDialog(MainActivity a) {
        this.a = a;
    }

    /** Opens the screen and starts connecting (or pairing, when DockPad isn't paired yet). */
    static void show(MainActivity a, boolean pairFirst) {
        if (open != null && open.dialog != null && open.dialog.isShowing()) {
            if (pairFirst) open.showPairing();
            return;
        }
        open = new ConnectDialog(a);
        open.build();
        if (pairFirst || !a.prefs().paired()) open.showPairing();
        else open.connect();
    }

    /** Opens the screen on a failure that already happened (for example the Turn on button). */
    static void showFailure(MainActivity a, Starter.Result r) {
        if (!isOpen()) {
            open = new ConnectDialog(a);
            open.build();
        }
        if (!open.busy) open.failed(r);
    }

    static boolean isOpen() {
        return open != null && open.dialog != null && open.dialog.isShowing();
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        dialog = new Dialog(a, R.style.DockPad_Guide);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(16), dp(28), dp(14));
        root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface) | 0xFF000000);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(a);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton close = button("Close", R.style.ThemeOverlay_DockPad_Button_Text);
        close.setOnClickListener(v -> dismiss());
        top.addView(close);
        root.addView(top);

        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, dp(10));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        checks = new LinearLayout(a);
        checks.setOrientation(LinearLayout.VERTICAL);
        content.addView(checks);

        LinearLayout stageRow = new LinearLayout(a);
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        stageRow.setPadding(0, dp(10), 0, dp(4));
        spinner = new ProgressBar(a);
        stageRow.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        stage = new TextView(a);
        stage.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        stage.setPadding(dp(10), 0, 0, 0);
        stageRow.addView(stage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(stageRow);

        problemText = new TextView(a);
        problemText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        problemText.setPadding(0, dp(4), 0, dp(4));
        content.addView(problemText);

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(body);

        actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(4), 0, 0);
        content.addView(actions);

        TextView hint = new TextView(a);
        hint.setText("A: select · B: close");
        hint.setGravity(Gravity.CENTER);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        root.addView(hint);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color(com.google.android.material.R.attr.colorSurface)));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setOnKeyListener((d, kc, e) -> {
            View decor = w == null ? null : w.getDecorView();
            if (kc == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            if (DpDialog.Controller.navigate(e, decor)) return true;
            if (!DpDialog.Controller.isFromController(e)) return false;
            return DpDialog.Controller.handle(e, decor, this::dismiss);
        });
        dialog.setOnDismissListener(d -> cleanUp());
        dialog.show();
        ui.post(tick);
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void cleanUp() {
        ui.removeCallbacksAndMessages(null);
        stopMdns();
        if (open == this) open = null;
    }

    /** Re-checks the live state every second, and retries by itself once an outside fix is done. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderChecks();
            if (pairing && !busy && !pairedAtStart && a.prefs().paired()) pairedFromNotification();
            if (!busy && waitedFor) {
                boolean fixed = false;
                switch (problem) {
                    case NO_WIFI: fixed = Starter.wifiConnected(a); break;
                    case DEVELOPER_OPTIONS: fixed = Starter.developerOptionsOn(a); break;
                    case WIRELESS_DEBUGGING_OFF:
                    case NETWORK_NOT_ALLOWED: fixed = Starter.wirelessDebuggingOn(a); break;
                    default: break;
                }
                if (fixed) {
                    waitedFor = false;
                    connect();
                }
            }
            ui.postDelayed(this, 1000);
        }
    };

    private void renderChecks() {
        checks.removeAllViews();
        boolean dev = Starter.developerOptionsOn(a);
        boolean wifi = Starter.wifiConnected(a);
        boolean eth = Starter.ethernetConnected(a);
        boolean wd = Starter.wirelessDebuggingOn(a);
        check(dev, dev ? "Developer options are on" : "Developer options are off");
        if (wifi) check(true, eth ? "Wi-Fi connected (Ethernet too)" : "Wi-Fi connected");
        else if (eth) check(false, "Ethernet only: Wi-Fi is needed while DockPad starts");
        else check(false, Starter.wifiSwitchedOn(a) ? "Wi-Fi isn't connected" : "Wi-Fi is switched off");
        if (wd) check(true, "Wireless debugging is on");
        else if (Starter.canControlWirelessDebugging(a)) check(true, "Wireless debugging: DockPad turns it on by itself");
        else check(false, "Wireless debugging is off");
        boolean paired = a.prefs().paired();
        check(paired, paired ? "DockPad is paired" : "DockPad isn't paired yet");
        if (pairing) {
            boolean found = pairPort > 0;
            check(found, found ? "Pairing code screen found" : "Waiting for \"Pair device with pairing code\"…");
        }
        if (a.isRunning()) check(true, "DockPad is on");
    }

    // ------------------------------------------------------------------ connecting

    private void connect() {
        if (busy) return;
        busy = true;
        pairing = false;
        stopMdns();
        problem = Starter.Problem.NONE;
        title.setText("Connecting DockPad");
        body.removeAllViews();
        actions.removeAllViews();
        problemText.setText("");
        problemText.setVisibility(View.GONE);
        working("Starting…");
        renderChecks();
        Prefs prefs = a.prefs();
        new Thread(() -> {
            Starter.Result r = Starter.start(a, prefs.turnOffWirelessDebugging(),
                    t -> ui.post(() -> working(t)));
            if (r.ok) {
                prefs.paired(true);
                prefs.bridgeWanted(true);
                try {
                    prefs.applyTo(Starter.bridge(a), true);
                } catch (Exception ignored) {
                }
            }
            ui.post(() -> {
                busy = false;
                if (dialog == null || !dialog.isShowing()) return;
                if (r.ok) succeeded();
                else failed(r);
            });
        }, "DockPad-connect").start();
    }

    private void succeeded() {
        a.ownServiceStarted();
        title.setText("DockPad is on");
        done(true, "Connected and running. You won't need to do this again unless the handheld restarts "
                + "without Wi-Fi.");
        actions.removeAllViews();
        MaterialButton ok = action("Done", this::dismiss);
        ok.post(ok::requestFocus);
        renderChecks();
        ui.postDelayed(this::dismiss, 2500);
    }

    private void failed(Starter.Result r) {
        problem = r.problem;
        title.setText("DockPad couldn't turn on");
        done(false, whatFailed(r.problem));
        problemText.setVisibility(View.VISIBLE);
        problemText.setText(r.message);
        body.removeAllViews();
        actions.removeAllViews();
        MaterialButton first;
        switch (r.problem) {
            case DEVELOPER_OPTIONS:
                first = action("Open About (tap Build number 7 times)", () -> a.openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));
                action("Try again", this::connect);
                waitFor("Waiting for Developer options… DockPad tries again by itself.");
                break;
            case NO_WIFI:
                first = action("Open Wi-Fi settings", () -> a.openSettings(Settings.ACTION_WIFI_SETTINGS));
                action("Try again", this::connect);
                waitFor("Waiting for Wi-Fi… DockPad tries again by itself as soon as it's connected.");
                break;
            case WIRELESS_DEBUGGING_OFF:
                first = action("Open Wireless debugging", () -> openWirelessDebugging(false));
                action("Try again", this::connect);
                action("Pair DockPad again", this::repair);
                waitFor("Waiting for Wireless debugging… DockPad tries again by itself once it's on.");
                break;
            case NETWORK_NOT_ALLOWED:
                first = action("Try again (Android asks to allow this network)", this::connect);
                action("Open Wireless debugging to allow it", () -> openWirelessDebugging(false));
                waitFor("Once Wireless debugging is on, DockPad tries again by itself.");
                break;
            case PAIRING:
                first = action("Pair again", this::repair);
                action("Try again without pairing", this::connect);
                break;
            case CONNECT:
                first = action("Try again", this::connect);
                if (Starter.canControlWirelessDebugging(a)) {
                    action("Restart Wireless debugging and try again", this::restartWirelessDebugging);
                }
                action("Open Wi-Fi settings", () -> a.openSettings(Settings.ACTION_WIFI_SETTINGS));
                action("Pair DockPad again", this::repair);
                break;
            default:
                first = action("Try again", this::connect);
                if (Starter.canControlWirelessDebugging(a)) {
                    action("Restart Wireless debugging and try again", this::restartWirelessDebugging);
                }
                action("Pair DockPad again", this::repair);
                break;
        }
        first.post(first::requestFocus);
        renderChecks();
    }

    private static String whatFailed(Starter.Problem p) {
        switch (p) {
            case DEVELOPER_OPTIONS: return "Developer options are off";
            case NO_WIFI: return "No Wi-Fi connection";
            case WIRELESS_DEBUGGING_OFF: return "Wireless debugging is off";
            case NETWORK_NOT_ALLOWED: return "This Wi-Fi network isn't allowed yet";
            case PAIRING: return "The pairing is no longer accepted";
            case CONNECT: return "Couldn't reach Wireless debugging";
            case SERVICE: return "The service didn't start";
            default: return "Something went wrong";
        }
    }

    /** The problem is fixed outside DockPad: notice it and retry by ourselves. */
    private void waitFor(String note) {
        waitedFor = true;
        small(note);
    }

    /** Pairing finished in the notification, which starts DockPad itself: follow along. */
    private void pairedFromNotification() {
        pairing = false;
        busy = true;
        stopMdns();
        title.setText("Paired!");
        body.removeAllViews();
        actions.removeAllViews();
        working("Paired from the notification. Starting DockPad…");
        new Thread(() -> {
            for (int i = 0; i < 40 && !Starter.isRunning(a); i++) Starter.sleepQuietly(500);
            ui.post(() -> {
                busy = false;
                if (dialog != null && dialog.isShowing()) connect(); // "already running", or a fresh try
            });
        }, "DockPad-follow-pairing").start();
    }

    private void restartWirelessDebugging() {
        if (busy) return;
        busy = true;
        working("Restarting Wireless debugging…");
        actions.removeAllViews();
        new Thread(() -> {
            Starter.setWirelessDebugging(a, false);
            Starter.sleepQuietly(1500);
            ui.post(() -> {
                busy = false;
                connect();
            });
        }, "DockPad-wd-restart").start();
    }

    private void repair() {
        a.prefs().paired(false);
        a.setupChanged();
        showPairing();
    }

    // ------------------------------------------------------------------ pairing

    private void showPairing() {
        pairedAtStart = a.prefs().paired();
        pairing = true;
        waitedFor = false;
        problem = Starter.Problem.NONE;
        title.setText("Pair DockPad (one time)");
        problemText.setVisibility(View.GONE);
        body.removeAllViews();
        actions.removeAllViews();
        working("Waiting for the pairing code…");
        numbered(1, "Open Wireless debugging below. It opens next to DockPad, so this screen stays visible.");
        numbered(2, "Turn Wireless debugging on (allow it on this network if asked), then tap \"Pair device with "
                + "pairing code\".");
        numbered(3, "Type the 6-digit code here. DockPad finds the rest by itself.");
        if (!Starter.wifiConnected(a)) small(Starter.noWifiMessage(a));

        MaterialButton side = action("Open Wireless debugging side by side", () -> openWirelessDebugging(true));
        action("Open Wireless debugging full screen", () -> openWirelessDebugging(false));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        code = new EditText(a);
        code.setHint("6-digit code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setImeOptions(EditorInfo.IME_ACTION_DONE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        code.setSingleLine(true);
        code.setOnEditorActionListener((v, id, e) -> {
            pairWithCode();
            return true;
        });
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 6 && pairPort > 0) pairWithCode();
            }
        });
        row.addView(code, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton go = button("Pair", R.style.ThemeOverlay_DockPad_Button);
        go.setOnClickListener(v -> pairWithCode());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        row.addView(go, lp);
        actions.addView(row);
        pairState = new TextView(a);
        pairState.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        pairState.setPadding(0, dp(6), 0, 0);
        actions.addView(pairState);
        small("You can also type the code into DockPad's notification (pull down from the top) if Settings "
                + "opens full screen.");
        if (a.hasPairingKey()) {
            action("Paired before? Connect without pairing", () -> {
                a.prefs().paired(true);
                a.setupChanged();
                connect();
            });
        }
        side.post(side::requestFocus);

        startMdns();
        notificationFallback();
        // Once paired, DockPad can switch Wireless debugging on itself.
        if (!Starter.wirelessDebuggingOn(a) && Starter.canControlWirelessDebugging(a) && Starter.wifiConnected(a)) {
            new Thread(() -> Starter.setWirelessDebugging(a, true), "DockPad-wd-on").start();
        }
        renderChecks();
    }

    private void startMdns() {
        if (mdns != null) return;
        try {
            mdns = new AdbMdns(a, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (h, p) -> ui.post(() -> {
                pairHost = h;
                pairPort = p;
                if (pairState == null) return;
                if (p > 0) {
                    pairState.setText("Pairing code screen found. Type the code shown on it.");
                    String c = code == null ? "" : code.getText().toString().trim();
                    if (c.length() == 6) pairWithCode();
                    else if (code != null) code.requestFocus();
                } else {
                    pairState.setText("The pairing code screen closed. Tap \"Pair device with pairing code\" again.");
                }
                renderChecks();
            }));
            mdns.start();
        } catch (Exception e) {
            mdns = null;
        }
    }

    private void stopMdns() {
        if (mdns != null) {
            try {
                mdns.stop();
            } catch (Exception ignored) {
            }
            mdns = null;
        }
        pairPort = -1;
    }

    /** Also offers the code in a notification, for when Settings can't open side by side. */
    private void notificationFallback() {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestNotificationsQuietly();
            return;
        }
        try {
            PairingService.start(a);
        } catch (Exception ignored) {
        }
    }

    private void pairWithCode() {
        if (busy || code == null) return;
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            pairState.setText("The code has 6 digits.");
            return;
        }
        int p = pairPort;
        if (p <= 0) {
            pairState.setText("DockPad can't see the pairing code screen yet. In Wireless debugging, tap \"Pair device "
                    + "with pairing code\" and keep it open (side by side), then tap Pair.");
            return;
        }
        busy = true;
        working("Pairing…");
        pairState.setText("");
        String h = pairHost == null ? "127.0.0.1" : pairHost.getHostAddress();
        new Thread(() -> {
            String err = null;
            try {
                if (!AdbManager.get(a).pair(h, p, c)) err = "Android refused the pairing.";
            } catch (Exception e) {
                err = pairPort <= 0
                        ? "The pairing code screen closed before pairing finished. Open \"Pair device with pairing "
                        + "code\" again; the code changes each time."
                        : "That code wasn't accepted. Check the code shown now (it changes each time the pairing "
                        + "screen opens) and try again.";
            }
            String failure = err;
            ui.post(() -> {
                busy = false;
                if (dialog == null || !dialog.isShowing()) return;
                if (failure != null) {
                    working("Waiting for the pairing code…");
                    pairState.setText(failure);
                    code.selectAll();
                    code.requestFocus();
                    return;
                }
                a.prefs().paired(true);
                a.setupChanged();
                try {
                    a.startService(new Intent(a, PairingService.class).setAction(PairingService.ACTION_STOP));
                } catch (Exception ignored) {
                }
                connect();
            });
        }, "DockPad-pair-in-app").start();
    }

    private void openWirelessDebugging(boolean sideBySide) {
        Intent i = PairingService.wirelessDebuggingIntent();
        if (sideBySide) {
            i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            triedSideBySide = true;
        }
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            a.openSettings(Settings.ACTION_SETTINGS);
        }
        if (sideBySide) {
            ui.postDelayed(() -> {
                if (pairState != null && triedSideBySide && !a.isInMultiWindowMode()) {
                    pairState.setText("Settings didn't open side by side on this device. Pair from DockPad's "
                            + "notification instead: pull down from the top and tap \"Enter code\".");
                }
            }, 2500);
        }
    }

    // ------------------------------------------------------------------ building blocks

    private void working(String t) {
        spinner.setVisibility(View.VISIBLE);
        stage.setText(t);
    }

    private void done(boolean ok, String t) {
        spinner.setVisibility(View.GONE);
        stage.setText((ok ? "✓  " : "✕  ") + t);
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
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        checks.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void numbered(int n, String t) {
        TextView v = new TextView(a);
        v.setText(n + ".  " + t);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        v.setPadding(dp(8), dp(2), 0, dp(2));
        body.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void small(String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        v.setPadding(0, dp(8), 0, dp(4));
        actions.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private MaterialButton action(String label, Runnable r) {
        MaterialButton b = button(label, R.style.ThemeOverlay_DockPad_Button_Tonal);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        actions.addView(b, lp);
        return b;
    }

    private MaterialButton button(String label, int style) {
        MaterialButton b = new MaterialButton(new android.view.ContextThemeWrapper(a, style));
        b.setText(label);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setSingleLine(false);
        b.setMaxLines(4);
        b.setMinHeight(dp(48));
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
