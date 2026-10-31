package com.adonzin.dockpad;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adonzin.dockpad.core.Shortcut;
import com.adonzin.dockpad.service.BridgeService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity implements ShortcutEditor.Host {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQ = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private ShortcutEditor editor;

    private MaterialCardView setupCard;
    private LinearLayout setupSteps, controllersList, shortcutsList, settingsList;
    private TextView bridgeTitle, bridgeSubtitle;
    private MaterialButton bridgeButton;

    private volatile IBridge bridge;
    private boolean bound, startWhenConnected;
    private boolean lastRunning;
    private String lastControllersJson = "";

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, BridgeService.class.getName()))
            .daemon(true)
            .processNameSuffix("bridge")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) return;
            IBridge b = IBridge.Stub.asInterface(binder);
            bridge = b;
            boolean start = startWhenConnected || prefs.bridgeWanted();
            startWhenConnected = false;
            pushSettings();
            if (start) bg.execute(() -> {
                try {
                    if (!b.isRunning()) b.start(prefs.includeBuiltIn());
                } catch (Exception ignored) {
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            bound = false;
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> ui.post(this::refreshSetup);
    private final Shizuku.OnBinderDeadListener binderDead = () -> ui.post(() -> {
        bridge = null;
        bound = false;
        refreshSetup();
    });
    private final Shizuku.OnRequestPermissionResultListener permResult =
            (code, result) -> ui.post(this::refreshSetup);

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollStatus();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        editor = new ShortcutEditor(this, this);

        setupCard = findViewById(R.id.setup_card);
        setupSteps = findViewById(R.id.setup_steps);
        controllersList = findViewById(R.id.controllers_list);
        shortcutsList = findViewById(R.id.shortcuts_list);
        settingsList = findViewById(R.id.settings_list);
        bridgeTitle = findViewById(R.id.bridge_title);
        bridgeSubtitle = findViewById(R.id.bridge_subtitle);
        bridgeButton = findViewById(R.id.bridge_button);

        bridgeButton.setOnClickListener(v -> {
            if (lastRunning) stopBridge();
            else startBridge();
        });
        findViewById(R.id.add_shortcut).setOnClickListener(v -> editor.addNew());
        findViewById(R.id.open_tester).setOnClickListener(v -> startActivity(new Intent(this, TesterActivity.class)));
        findViewById(R.id.open_diagnostics).setOnClickListener(v -> showDiagnostics());
        ((TextView) findViewById(R.id.version)).setText("DockPad " + BuildConfig.VERSION_NAME);

        buildSettings();
        renderShortcuts();
        renderControllers(null);
        renderBridge(false, null);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
        if (bound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, false); // the bridge keeps running
            } catch (Throwable ignored) {
            }
        }
        bg.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ShortcutEditor.Host

    @Override
    public IBridge bridge() {
        return bridge;
    }

    @Override
    public void runInBackground(Runnable r) {
        bg.execute(r);
    }

    @Override
    public Prefs prefs() {
        return prefs;
    }

    @Override
    public void shortcutsChanged() {
        renderShortcuts();
        pushSettings();
    }

    // ------------------------------------------------------------------ Shizuku setup

    private boolean shizukuInstalled() {
        try {
            getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shizukuAllowed() {
        try {
            return shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshSetup() {
        boolean installed = shizukuInstalled() || shizukuRunning();
        boolean running = shizukuRunning();
        boolean allowed = shizukuAllowed();

        setupSteps.removeAllViews();
        addSetupStep(installed, "Install Shizuku", "A free app that lets DockPad manage controllers.",
                installed ? null : "Get it", this::openShizukuStore);
        addSetupStep(running, "Start Shizuku",
                "Open Shizuku and tap Start under \"Start via Wireless debugging\". Needed again after every restart.",
                installed && !running ? "Open" : null, this::openShizuku);
        addSetupStep(allowed, "Allow DockPad", "Give DockPad permission in Shizuku.",
                running && !allowed ? "Allow" : null, () -> {
                    try {
                        Shizuku.requestPermission(REQ);
                    } catch (Throwable ignored) {
                    }
                });
        setupCard.setVisibility(allowed ? View.GONE : View.VISIBLE);
        bridgeButton.setEnabled(allowed);
        if (allowed) bindBridge();
        else renderBridge(false, null);
    }

    private void addSetupStep(boolean done, String title, String subtitle, String action, Runnable onAction) {
        View row = getLayoutInflater().inflate(R.layout.item_row, setupSteps, false);
        ImageView icon = row.findViewById(R.id.row_icon);
        icon.setImageResource(done ? R.drawable.ic_check : R.drawable.ic_pending);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(done ? "Done" : subtitle);
        MaterialButton btn = row.findViewById(R.id.row_action);
        if (action != null) {
            btn.setVisibility(View.VISIBLE);
            btn.setText(action);
            btn.setOnClickListener(v -> onAction.run());
        }
        setupSteps.addView(row);
    }

    private void openShizukuStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU_PACKAGE)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        }
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (i != null) startActivity(i);
        else openShizukuStore();
    }

    private void bindBridge() {
        if (bound) return;
        try {
            Shizuku.bindUserService(serviceArgs, connection);
            bound = true;
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't start DockPad's service: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ bridge on/off

    private void startBridge() {
        if (!shizukuAllowed()) {
            refreshSetup();
            return;
        }
        prefs.bridgeWanted(true);
        IBridge b = bridge;
        if (b != null) {
            bg.execute(() -> {
                try {
                    b.start(prefs.includeBuiltIn());
                } catch (Exception ignored) {
                }
            });
        } else {
            startWhenConnected = true;
            bindBridge();
        }
        bridgeTitle.setText("Turning on…");
    }

    private void stopBridge() {
        prefs.bridgeWanted(false);
        IBridge b = bridge;
        bg.execute(() -> {
            try {
                if (b != null) b.stop();
            } catch (Exception ignored) {
            }
        });
    }

    private void pushSettings() {
        IBridge b = bridge;
        if (b == null) return;
        boolean mp = prefs.multiplayer(), tp = prefs.touchpadAsButton(), bi = prefs.includeBuiltIn();
        String spec = prefs.shortcutsSpec();
        bg.execute(() -> {
            try {
                b.setMultiplayer(mp);
                b.setTouchpadAsButton(tp);
                b.setShortcuts(spec);
                if (b.isRunning()) b.start(bi); // applies the built-in setting
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------------------------------------------ status

    private void pollStatus() {
        IBridge b = bridge;
        if (b == null) return;
        bg.execute(() -> {
            String json;
            try {
                json = b.statusJson();
            } catch (Exception e) {
                json = null;
            }
            String j = json;
            ui.post(() -> {
                try {
                    JSONObject o = j == null ? null : new JSONObject(j);
                    boolean running = o != null && o.optBoolean("running");
                    renderBridge(running, o);
                    String ctrls = o == null ? "" : o.optJSONArray("controllers") + "|" + o.optJSONArray("players");
                    if (!ctrls.equals(lastControllersJson)) {
                        lastControllersJson = ctrls;
                        renderControllers(running ? o : null);
                    }
                } catch (Exception ignored) {
                }
            });
        });
    }

    private void renderBridge(boolean running, JSONObject o) {
        lastRunning = running;
        bridgeButton.setText(running ? "Turn off" : "Turn on");
        if (!shizukuAllowed()) {
            bridgeTitle.setText("DockPad is off");
            bridgeSubtitle.setText("Finish the setup above first.");
            return;
        }
        if (!running) {
            bridgeTitle.setText("DockPad is off");
            bridgeSubtitle.setText("Controllers work as they normally do.");
            return;
        }
        JSONArray ctrls = o == null ? null : o.optJSONArray("controllers");
        int n = 0;
        if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
            JSONObject c = ctrls.optJSONObject(i);
            if (c != null && !c.optBoolean("touchpad")) n++;
        }
        String err = o == null ? "" : o.optString("error", "");
        if (o != null && o.isNull("builtIn")) {
            bridgeTitle.setText("DockPad is on");
            bridgeSubtitle.setText("Waiting for the built-in controls…");
        } else {
            bridgeTitle.setText("DockPad is on");
            bridgeSubtitle.setText(n == 1 ? "1 controller connected" : n + " controllers connected");
        }
        JSONArray players = o == null ? null : o.optJSONArray("players");
        if (players != null) for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.optJSONObject(i);
            if (p != null && !p.optBoolean("active")) {
                bridgeSubtitle.setText("Problem with \"" + p.optString("name") + "\". Open Diagnostics.");
            }
        }
        if (!err.isEmpty() && n == 0) bridgeSubtitle.append("\nLast error: " + err);
    }

    private void renderControllers(JSONObject o) {
        controllersList.removeAllViews();
        JSONArray ctrls = o == null ? null : o.optJSONArray("controllers");
        if (ctrls == null || ctrls.length() == 0) {
            addInfoRow(controllersList, R.drawable.ic_gamepad,
                    o == null ? "Turn DockPad on to see your controllers." : "No controllers found.",
                    o == null ? null : "Connect a controller over Bluetooth or USB. It appears here within a few seconds.");
        } else {
            for (int i = 0; i < ctrls.length(); i++) {
                JSONObject c = ctrls.optJSONObject(i);
                if (c == null) continue;
                boolean builtIn = c.optBoolean("builtIn");
                boolean touchpad = c.optBoolean("touchpad");
                String sub = builtIn ? "Built-in controls" : touchpad ? "Touchpad: click works as a button" : "External controller";
                if (!c.optBoolean("exclusive")) sub += " · couldn't take over: apps may see it twice";
                String last = c.optString("lastButton", "");
                if (!last.isEmpty()) sub += " · last: " + last;
                View row = addInfoRow(controllersList,
                        builtIn ? R.drawable.ic_handheld : touchpad ? R.drawable.ic_touch : R.drawable.ic_gamepad,
                        c.optString("name"), sub);
                TextView badge = row.findViewById(R.id.row_badge);
                badge.setVisibility(View.VISIBLE);
                badge.setText("Player " + c.optInt("player", 1));
            }
        }
        JSONArray players = o == null ? null : o.optJSONArray("players");
        if (players != null && players.length() > 0) {
            StringBuilder b = new StringBuilder("In your emulators, choose ");
            for (int i = 0; i < players.length(); i++) {
                JSONObject p = players.optJSONObject(i);
                if (p == null) continue;
                if (i > 0) b.append(i == players.length() - 1 ? " and " : ", ");
                b.append('"').append(p.optString("name")).append("\" for player ").append(p.optInt("player"));
            }
            b.append('.');
            TextView tip = new TextView(this);
            tip.setText(b);
            tip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            tip.setPadding(0, dp(8), 0, 0);
            controllersList.addView(tip);
        }
    }

    private View addInfoRow(ViewGroup parent, int icon, String title, String subtitle) {
        View row = getLayoutInflater().inflate(R.layout.item_row, parent, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        TextView sub = row.findViewById(R.id.row_subtitle);
        if (subtitle == null) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        parent.addView(row);
        return row;
    }

    // ------------------------------------------------------------------ shortcuts

    private void renderShortcuts() {
        shortcutsList.removeAllViews();
        List<Shortcut> all = prefs.shortcuts();
        if (all.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No shortcuts yet. Example: Select + B → Back.");
            none.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            none.setPadding(0, dp(4), 0, dp(4));
            shortcutsList.addView(none);
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            Shortcut s = all.get(i);
            String title = (s.hold ? "Hold " : "") + s.triggerLabel() + "  →  " + s.actionLabel();
            View row = addInfoRow(shortcutsList, R.drawable.ic_bolt, title, s.deviceLabel());
            row.setFocusable(true);
            row.setClickable(true);
            int index = i;
            row.setOnClickListener(v -> editor.editExisting(index));
        }
    }

    // ------------------------------------------------------------------ settings

    private void buildSettings() {
        settingsList.removeAllViews();
        addSwitch("Include the built-in controls",
                "Send the built-in controls through the DockPad Controller too, so emulators work the same handheld or docked.",
                prefs.includeBuiltIn(), v -> {
                    prefs.includeBuiltIn(v);
                    pushSettings();
                });
        addSwitch("Multiplayer",
                "Each extra controller becomes the next player (DockPad Controller 2, 3, 4). When off, every controller is player 1.",
                prefs.multiplayer(), v -> {
                    prefs.multiplayer(v);
                    pushSettings();
                });
        addSwitch("Touchpad as a button",
                "On controllers with a touchpad (DualShock 4, DualSense), pressing the pad becomes a button you can use in shortcuts instead of a mouse.",
                prefs.touchpadAsButton(), v -> {
                    prefs.touchpadAsButton(v);
                    pushSettings();
                });
    }

    interface BoolSetter {
        void set(boolean v);
    }

    private void addSwitch(String title, String subtitle, boolean value, BoolSetter onChange) {
        View row = getLayoutInflater().inflate(R.layout.item_switch, settingsList, false);
        ((TextView) row.findViewById(R.id.switch_title)).setText(title);
        ((TextView) row.findViewById(R.id.switch_subtitle)).setText(subtitle);
        MaterialSwitch sw = row.findViewById(R.id.switch_toggle);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> onChange.set(checked));
        row.setOnClickListener(v -> sw.toggle());
        settingsList.addView(row);
    }

    // ------------------------------------------------------------------ diagnostics

    private void showDiagnostics() {
        IBridge b = bridge;
        bg.execute(() -> {
            String s;
            try {
                s = b == null ? "DockPad's service isn't connected (is Shizuku running?)." : b.status();
            } catch (Exception e) {
                s = "Couldn't reach DockPad's service: " + e;
            }
            String text = "DockPad " + BuildConfig.VERSION_NAME + "\n" + s;
            ui.post(() -> {
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(12);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(24), dp(8), dp(24), 0);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Diagnostics")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Copy", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("DockPad diagnostics", text));
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
