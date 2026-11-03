package com.adonzin.dockpad;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

import com.adonzin.dockpad.adb.Starter;
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

public class MainActivity extends AppCompatActivity implements ShortcutEditor.Host, ControllerDialog.Host {
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQ = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private ShortcutEditor editor;
    private ControllerDialog controllerDialog;

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
            if (binder == null || !binder.pingBinder() || usingOwnService) return;
            IBridge b = IBridge.Stub.asInterface(binder);
            bridge = b;
            boolean start = startWhenConnected || prefs.bridgeWanted();
            startWhenConnected = false;
            pushSettings();
            if (start) bg.execute(() -> {
                try {
                    if (!b.isRunning()) b.start(true);
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
        controllerDialog = new ControllerDialog(this, this);

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
        findViewById(R.id.open_reset).setOnClickListener(v -> showReset());
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
    public boolean bridgeRunning() {
        return lastRunning;
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
    public void layoutsChanged() {
        lastControllersJson = ""; // re-render rows with the new summary
        pushSettings();
    }

    @Override
    public void shortcutsChanged() {
        renderShortcuts();
        pushSettings();
    }

    // ------------------------------------------------------------------ backends
    // 1) DockPad's own service, started over Wireless debugging (no other apps needed)
    // 2) Shizuku, for people who already use it

    private volatile boolean usingOwnService;
    private boolean starting;
    private static final int REQ_NOTIFY = 7;

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

    private boolean ready() {
        return usingOwnService || prefs.paired() || shizukuAllowed();
    }

    private void refreshSetup() {
        bg.execute(() -> {
            boolean own = Starter.isRunning(this);
            ui.post(() -> applyBackend(own));
        });
    }

    private void applyBackend(boolean ownRunning) {
        if (ownRunning) {
            if (!usingOwnService) {
                usingOwnService = true;
                bridge = Starter.bridge(this);
                if (bound) { // don't run two bridges at once
                    try {
                        Shizuku.unbindUserService(serviceArgs, connection, true);
                    } catch (Throwable ignored) {
                    }
                    bound = false;
                }
                pushSettings(true);
            }
        } else {
            if (usingOwnService) {
                usingOwnService = false;
                bridge = null;
            }
            if (prefs.paired()) {
                if (prefs.bridgeWanted() && !starting) startOwnService();
            } else if (shizukuAllowed()) {
                bindBridge();
            }
        }
        renderSetup();
        if (!ready()) renderBridge(false, null);
    }

    private boolean wifiConnected() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }

    private void renderSetup() {
        setupSteps.removeAllViews();
        boolean ready = ready();
        setupCard.setVisibility(ready ? View.GONE : View.VISIBLE);
        bridgeButton.setEnabled(ready);
        if (ready) return;
        boolean dev = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        boolean wifi = wifiConnected();
        addSetupStep(dev, "Turn on Developer options",
                "In Settings → About, tap \"Build number\" 7 times.",
                dev ? null : "Open", () -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));
        addSetupStep(wifi, "Connect to Wi-Fi", "Wireless debugging only works over Wi-Fi.",
                wifi ? null : "Open", () -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        addSetupStep(false, "Pair DockPad (one time only)",
                "Tap Pair. In Wireless debugging, turn it on and tap \"Pair device with pairing code\". "
                        + "Then type the 6-digit code into DockPad's notification (pull down from the top).",
                "Pair", this::beginPairing);
        TextView alt = new TextView(this);
        alt.setText("Already use Shizuku? Start it and allow DockPad. That works too.");
        alt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        alt.setPadding(0, dp(8), 0, 0);
        setupSteps.addView(alt);
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

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void beginPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        beginPairingNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginPairingNow();
            else new MaterialAlertDialogBuilder(this)
                    .setTitle("Notifications needed")
                    .setMessage("DockPad asks for the pairing code in a notification, so you can type it while the "
                            + "Wireless debugging screen stays open. Please allow notifications and try again.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private void beginPairingNow() {
        PairingService.start(this);
        Toast.makeText(this, "Open Wireless debugging → Pair device with pairing code", Toast.LENGTH_LONG).show();
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless"); // highlights Wireless debugging
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:show_fragment_args", args);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            openSettings(Settings.ACTION_SETTINGS);
        }
    }

    private void bindBridge() {
        if (bound || usingOwnService) return;
        try {
            Shizuku.bindUserService(serviceArgs, connection);
            bound = true;
        } catch (Throwable t) {
            Toast.makeText(this, "Couldn't start DockPad's service: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startOwnService() {
        starting = true;
        bridgeTitle.setText("Turning on…");
        bridgeSubtitle.setText("Connecting to Wireless debugging…");
        bg.execute(() -> {
            Starter.Result r = Starter.start(this, prefs.turnOffWirelessDebugging());
            ui.post(() -> {
                starting = false;
                if (r.ok) {
                    usingOwnService = false; // re-attach below
                    applyBackend(true);
                    return;
                }
                if (r.needsPairing) prefs.paired(false);
                renderSetup();
                renderBridge(false, null);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("DockPad couldn't turn on")
                        .setMessage(r.message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    // ------------------------------------------------------------------ bridge on/off

    private void startBridge() {
        prefs.bridgeWanted(true);
        IBridge b = bridge;
        if (usingOwnService && b != null) {
            bg.execute(() -> {
                try {
                    b.start(true);
                } catch (Exception ignored) {
                }
            });
        } else if (prefs.paired()) {
            startOwnService();
            return;
        } else if (shizukuAllowed()) {
            if (b != null) {
                bg.execute(() -> {
                    try {
                        b.start(true);
                    } catch (Exception ignored) {
                    }
                });
            } else {
                startWhenConnected = true;
                bindBridge();
            }
        } else {
            renderSetup();
            return;
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
        pushSettings(false);
    }

    private void pushSettings(boolean startIfWanted) {
        IBridge b = bridge;
        if (b == null) return;
        bg.execute(() -> {
            try {
                prefs.applyTo(b, startIfWanted);
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
                if (usingOwnService) ui.post(this::refreshSetup);
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
        if (!ready()) {
            bridgeTitle.setText("DockPad is off");
            bridgeSubtitle.setText("Finish the setup above first.");
            return;
        }
        if (!running) {
            bridgeTitle.setText("DockPad is off");
            bridgeSubtitle.setText("Turn on to use external controllers as your built-in controls.");
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
            bridgeSubtitle.setText(n == 0 ? "No external controller connected"
                    : n == 1 ? "1 external controller connected" : n + " external controllers connected");
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
        if (o != null) {
            View row;
            if (o.isNull("builtIn")) {
                row = addInfoRow(controllersList, R.drawable.ic_error, "Built-in controls not found",
                        "Tap to choose them by pressing one of their buttons.");
            } else {
                String how = o.optString("builtInHow");
                String howText = "chosen".equals(how) ? "chosen by you" : "detected".equals(how)
                        ? "detected automatically" : "recognised";
                row = addInfoRow(controllersList, R.drawable.ic_handheld, "Built-in controls",
                        o.optString("builtIn") + " (" + howText + "). External controllers on player 1 act as these. Tap to change.");
                TextView badge = row.findViewById(R.id.row_badge);
                badge.setVisibility(View.VISIBLE);
                badge.setText("Player 1");
            }
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> chooseBuiltIn());
        }
        if (ctrls == null || ctrls.length() == 0) {
            addInfoRow(controllersList, R.drawable.ic_gamepad,
                    o == null ? "Turn DockPad on to see your controllers." : "No external controllers connected.",
                    o == null ? null : "Connect a controller over Bluetooth or USB. It appears here within a few seconds.");
        } else {
            for (int i = 0; i < ctrls.length(); i++) {
                JSONObject c = ctrls.optJSONObject(i);
                if (c == null) continue;
                boolean builtIn = c.optBoolean("builtIn");
                boolean touchpad = c.optBoolean("touchpad");
                String name = c.optString("name");
                String sub = builtIn ? "Built-in controls" : touchpad ? "Touchpad: click works as a button"
                        : prefs.layout(name).summary() + " · tap to change";
                if (!c.optBoolean("exclusive")) sub += " · couldn't take over: apps may see it twice";
                String last = c.optString("lastButton", "");
                if (!last.isEmpty()) sub += " · last: " + last;
                View row = addInfoRow(controllersList,
                        builtIn ? R.drawable.ic_handheld : touchpad ? R.drawable.ic_touch : R.drawable.ic_gamepad,
                        c.optString("name"), sub);
                TextView badge = row.findViewById(R.id.row_badge);
                badge.setVisibility(View.VISIBLE);
                badge.setText("Player " + c.optInt("player", 1));
                if (!touchpad && !builtIn) {
                    row.setFocusable(true);
                    row.setClickable(true);
                    row.setOnClickListener(v -> controllerDialog.show(name));
                }
            }
        }
        JSONArray players = o == null ? null : o.optJSONArray("players");
        if (o != null) {
            StringBuilder b = new StringBuilder("Player 1 needs no setup: emulators already set up for the "
                    + "built-in controls respond to it.");
            if (players != null) for (int i = 0; i < players.length(); i++) {
                JSONObject p = players.optJSONObject(i);
                if (p == null || p.optInt("player") < 2) continue;
                b.append(" For player ").append(p.optInt("player")).append(", choose \"")
                        .append(p.optString("name")).append("\" in your emulator once.");
            }
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

    // ------------------------------------------------------------------ built-in controls

    private void chooseBuiltIn() {
        String[] options = {"Press a button on them", "Detect automatically"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Which are the built-in controls?")
                .setItems(options, (d, which) -> {
                    if (which == 1) {
                        prefs.builtIn("");
                        pushSettings();
                        lastControllersJson = "";
                    } else {
                        identifyBuiltIn();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void identifyBuiltIn() {
        IBridge b = bridge;
        if (b == null) {
            Toast.makeText(this, "Turn DockPad on first.", Toast.LENGTH_SHORT).show();
            return;
        }
        com.google.android.material.progressindicator.LinearProgressIndicator progress =
                new com.google.android.material.progressindicator.LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog waiting = new MaterialAlertDialogBuilder(this)
                .setTitle("Press a button on the built-in controls")
                .setMessage("Any button on the device's own controls (not an external controller).")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .show();
        bg.execute(() -> {
            String r;
            try {
                r = b.identifyController(15000);
            } catch (Exception e) {
                r = "";
            }
            String res = r;
            ui.post(() -> {
                if (!waiting.isShowing()) return;
                waiting.dismiss();
                String[] f = res.split("\t");
                if (f.length != 3) {
                    new MaterialAlertDialogBuilder(this).setMessage("No button press detected.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Use \"" + f[0] + "\" as the built-in controls?")
                        .setMessage("External controllers on player 1 will act as this controller.")
                        .setPositiveButton("Use it", (d, w) -> {
                            prefs.builtIn(res);
                            pushSettings();
                            lastControllersJson = "";
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
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
        addSwitch("Multiplayer",
                "Player 1 is the built-in controls plus your first external controller. Each further controller becomes the next player (\"DockPad Controller 2\", \"3\", \"4\"). When off, every controller is player 1.",
                prefs.multiplayer(), v -> {
                    prefs.multiplayer(v);
                    pushSettings();
                });
        addSwitch("Start automatically after a restart",
                "After pairing, DockPad turns itself back on when the device restarts (needs Wi-Fi).",
                prefs.autoStart(), prefs::autoStart);
        addSwitch("Turn off Wireless debugging once started",
                "DockPad only needs Wireless debugging for a moment while starting. Turning it off afterwards is safer.",
                prefs.turnOffWirelessDebugging(), prefs::turnOffWirelessDebugging);
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

    // ------------------------------------------------------------------ reset

    private void showReset() {
        String[] options = {
                "Restart DockPad\nStops and starts DockPad's background service again.",
                "Pair again\nGoes back to the pairing step, for when connecting or starting keeps failing."};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reset connection")
                .setItems(options, (d, which) -> {
                    if (which == 0) restartService();
                    else confirmPairAgain();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPairAgain() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Pair again?")
                .setMessage("DockPad will turn off and show the pairing step again. "
                        + "Your shortcuts, button layouts and settings stay as they are.")
                .setPositiveButton("Pair again", (d, w) -> pairAgain())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopEverything() {
        IBridge b = bridge;
        try {
            if (b != null) b.stop();
        } catch (Exception ignored) {
        }
        Starter.bridge(this).exit(); // DockPad's own service, if running
        ui.post(() -> {
            if (bound) {
                try {
                    Shizuku.unbindUserService(serviceArgs, connection, true);
                } catch (Throwable ignored) {
                }
            }
            bound = false;
            bridge = null;
            usingOwnService = false;
        });
        try {
            stopService(new Intent(this, PairingService.class));
        } catch (Exception ignored) {
        }
    }

    private void restartService() {
        Toast.makeText(this, "Restarting DockPad…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            stopEverything();
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
            prefs.bridgeWanted(true);
            ui.post(this::refreshSetup); // starts again (own service or Shizuku)
        });
    }

    private void pairAgain() {
        bg.execute(() -> {
            stopEverything();
            prefs.paired(false);
            ui.post(() -> {
                Toast.makeText(this, "Ready to pair again.", Toast.LENGTH_SHORT).show();
                recreate();
            });
        });
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
