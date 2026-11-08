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
    private LinearLayout setupSteps, controllersList, shortcutsList, controllerSettings, appSettings;
    private com.google.android.material.tabs.TabLayout tabs;
    private final int[] pages = {R.id.page_home, R.id.page_controllers, R.id.page_shortcuts, R.id.page_quickmenu,
            R.id.page_settings};
    private static final String[] TAB_NAMES = {"Home", "Controllers", "Shortcuts", "Quick menu", "Settings"};
    private MenuOrganizer organiser;
    private TextView bridgeTitle, bridgeSubtitle;
    private MaterialButton bridgeButton;

    private volatile IBridge bridge;
    private boolean bound, startWhenConnected;
    private boolean lastRunning;
    private JSONObject lastStatus;
    private FirstRunGuide guide;
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
        controllerSettings = findViewById(R.id.controller_settings_list);
        appSettings = findViewById(R.id.app_settings_list);
        tabs = findViewById(R.id.tabs);
        for (String n : TAB_NAMES) tabs.addTab(tabs.newTab().setText(n));
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab t) { showPage(t.getPosition()); }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab t) { }
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab t) { }
        });
        int startTab = savedInstanceState == null ? 0 : savedInstanceState.getInt("tab", 0);
        if (startTab > 0) tabs.selectTab(tabs.getTabAt(startTab));
        bridgeTitle = findViewById(R.id.bridge_title);
        bridgeSubtitle = findViewById(R.id.bridge_subtitle);
        bridgeButton = findViewById(R.id.bridge_button);

        bridgeButton.setOnClickListener(v -> {
            if (lastRunning) stopBridge();
            else startBridge();
        });
        findViewById(R.id.add_shortcut).setOnClickListener(v -> editor.addNew());
        findViewById(R.id.rumble_test).setOnClickListener(v -> RumbleTest.show(this, bridge, bg));
        findViewById(R.id.pair_controller).setOnClickListener(v -> PairDialog.show(this));
        findViewById(R.id.open_tester).setOnClickListener(v -> startActivity(new Intent(this, TesterActivity.class)));
        findViewById(R.id.open_diagnostics).setOnClickListener(v -> showDiagnostics());
        findViewById(R.id.open_reset).setOnClickListener(v -> showReset());
        setupPcCard();
        findViewById(R.id.menu_combo).setOnClickListener(v -> editor.addNewWithAction(
                com.adonzin.dockpad.core.Codes.ACTION_QUICK_MENU));
        findViewById(R.id.menu_open).setOnClickListener(v ->
                startActivity(new Intent(this, QuickMenuActivity.class)));
        findViewById(R.id.add_command).setOnClickListener(v -> editor.addCommand());
        findViewById(R.id.theme_get).setOnClickListener(v -> CatalogDialog.show(this, this::showThemeName));
        findViewById(R.id.theme_choose).setOnClickListener(v -> ThemesDialog.choose(this, this::showThemeName));
        findViewById(R.id.theme_save).setOnClickListener(v -> ThemesDialog.saveCurrent(this, this::showThemeName));
        findViewById(R.id.theme_import).setOnClickListener(v -> ThemesDialog.importFile(this, done -> {
            themeFileDone = done;
            try {
                pickThemeFile.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
            } catch (Exception e) {
                Toast.makeText(this, "There's no file picker on this handheld. Install a file manager app, then try again.", Toast.LENGTH_LONG).show();
            }
        }, this::showThemeName));
        findViewById(R.id.theme_share).setOnClickListener(v -> ThemesDialog.share(this, MenuTheme.load(this).toJson()));
        showThemeName();
        findViewById(R.id.menu_preview).setOnClickListener(v ->
                startActivity(new Intent(this, QuickMenuActivity.class)));
        organiser = new MenuOrganizer(this, findViewById(R.id.organiser_list), this::pickMedia);
        findViewById(R.id.media_background).setOnClickListener(v -> MediaDialog.background(this, this::pickMedia));
        findViewById(R.id.media_decorations).setOnClickListener(v -> MediaDialog.decorations(this, this::pickMedia));
        findViewById(R.id.menu_fx).setOnClickListener(v -> MenuFxDialog.show(this, done -> {
            mediaDone = done;
            try {
                pickMediaFile.launch(new String[]{"audio/*"});
            } catch (Exception e) {
                Toast.makeText(this, "There's no file picker on this handheld. Install a file manager app, then try again.", Toast.LENGTH_LONG).show();
            }
        }));
        findViewById(R.id.new_section).setOnClickListener(v -> organiser.newSection());
        findViewById(R.id.reset_sections).setOnClickListener(v -> organiser.resetAll());
        commandsList = findViewById(R.id.commands_list);
        renderCommands();
        ((TextView) findViewById(R.id.version)).setText("DockPad " + BuildConfig.VERSION_NAME);
        ((TextView) findViewById(R.id.update_status)).setText("You have build " + BuildConfig.VERSION_CODE + ".");
        findViewById(R.id.check_updates).setOnClickListener(v -> checkUpdates(true));
        findViewById(R.id.backup_save).setOnClickListener(v -> saveBackup());
        findViewById(R.id.menu_look).setOnClickListener(v -> MenuLookDialog.show(this));
        findViewById(R.id.menu_style).setOnClickListener(v -> MenuStyleDialog.show(this, done -> {
            fontDone = done;
            try {
                pickFont.launch(new String[]{"font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*"});
            } catch (Exception e) {
                Toast.makeText(this, "There's no file picker on this handheld. Install a file manager app, then try again.", Toast.LENGTH_LONG).show();
            }
        }));
        findViewById(R.id.backup_restore).setOnClickListener(v -> {
            try {
                pickBackup.launch(new String[]{"application/json", "application/octet-stream", "text/plain", "*/*"});
            } catch (Exception e) {
                Toast.makeText(this, "There's no file picker on this handheld. Install a file manager app, then try again.", Toast.LENGTH_LONG).show();
            }
        });

        buildSettings();
        renderShortcuts();
        renderControllers(null);
        renderBridge(false, null);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);

        guide = new FirstRunGuide(this);
        if (!prefs.guideSeen()) {
            if (ready()) prefs.guideSeen(true); // already set up: no guide (Settings can still open it)
            else ui.post(guide::show);
        }
    }

    // ---- for the first-run guide

    boolean isReady() {
        return ready();
    }

    boolean isRunning() {
        return lastRunning;
    }

    JSONObject lastStatus() {
        return lastStatus;
    }

    boolean hasPairingKey() {
        return new java.io.File(getFilesDir(), "adb_private.key").exists();
    }

    void connectWithoutPairing() {
        prefs.paired(true);
        prefs.bridgeWanted(true);
        renderSetup();
        startOwnService(true);
    }

    void turnOn() {
        if (!lastRunning) startBridge();
    }

    void editQuickMenuCombo() {
        editor.addNewWithAction(com.adonzin.dockpad.core.Codes.ACTION_QUICK_MENU);
    }

    void chooseTheme() {
        ThemesDialog.choose(this, this::showThemeName);
    }

    void getThemes() {
        CatalogDialog.show(this, this::showThemeName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (findViewById(R.id.theme_name) != null) showThemeName();
        autoStartTried = false; // each visit may try once to bring DockPad back
        Watchdog.schedule(this);
        refreshSetup();
        if (guide != null) guide.refresh();
        if (prefs.autoUpdateCheck() && System.currentTimeMillis() - prefs.lastUpdateCheck() > 6 * 3600_000L) {
            checkUpdates(false);
        }
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

    // ------------------------------------------------------------------ tabs

    private void showPage(int index) {
        for (int i = 0; i < pages.length; i++) {
            View page = findViewById(pages[i]);
            page.setVisibility(i == index ? View.VISIBLE : View.GONE);
            if (i == index && !page.isInTouchMode()) page.requestFocus();
        }
        findViewById(R.id.scroll).scrollTo(0, 0);
    }

    /** L1/R1 move between tabs. */
    private void stepTab(int dir) {
        int idx = tabs.getSelectedTabPosition();
        int next = Math.max(0, Math.min(pages.length - 1, idx + dir));
        if (next != idx) tabs.selectTab(tabs.getTabAt(next));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tabs.getSelectedTabPosition());
    }

    // ------------------------------------------------------------------ controller navigation

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc == android.view.KeyEvent.KEYCODE_BUTTON_L1 || kc == android.view.KeyEvent.KEYCODE_BUTTON_R1) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                stepTab(kc == android.view.KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
            }
            return true;
        }
        // wrap around and L2/R2 jumps, within the open tab's page
        View page = findViewById(pages[Math.max(0, tabs.getSelectedTabPosition())]);
        if (DpDialog.Controller.navigate(e, page)) return true;
        if (DpDialog.Controller.isFromController(e)
                && DpDialog.Controller.handle(e, getWindow().getDecorView(), this::finish)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
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

    private LinearLayout commandsList;

    private void renderCommands() {
        if (organiser != null) organiser.render(); // commands are items in the organiser too
        commandsList.removeAllViews();
        List<Shortcut> all = prefs.menuCommands();
        if (all.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("None yet. Example: \"Save state\" pressing F2, or a macro.");
            none.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            none.setPadding(0, dp(4), 0, dp(4));
            commandsList.addView(none);
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            Shortcut c = all.get(i);
            View row = addInfoRow(commandsList, R.drawable.ic_bolt, c.device, c.actionLabel());
            row.setFocusable(true);
            row.setClickable(true);
            int index = i;
            row.setOnClickListener(v -> editor.editCommand(index));
        }
    }

    @Override
    public void shortcutsChanged() {
        renderCommands();
        renderShortcuts();
        pushSettings();
    }

    // ------------------------------------------------------------------ backends
    // 1) DockPad's own service, started over Wireless debugging (no other apps needed)
    // 2) Shizuku, for people who already use it

    private volatile boolean usingOwnService;
    private boolean autoStartTried;
    private boolean starting;
    private static final int REQ_NOTIFY = 7;

    boolean shizukuInstalled() {
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
                // try once per visit; failures here are shown quietly, never reset the pairing
                if (prefs.bridgeWanted() && !starting && !autoStartTried) {
                    autoStartTried = true;
                    startOwnService(false);
                }
            } else if (shizukuAllowed()) {
                bindBridge();
            }
        }
        renderSetup();
        if (!ready()) renderBridge(false, null);
    }

    boolean wifiConnected() {
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
        if (new java.io.File(getFilesDir(), "adb_private.key").exists()) {
            addSetupStep(false, "Paired before?",
                    "If DockPad was paired on this device already, try connecting without pairing again.",
                    "Connect", () -> {
                        prefs.paired(true);
                        prefs.bridgeWanted(true);
                        renderSetup();
                        startOwnService(true);
                    });
        }
        if (shizukuInstalled()) { // only for people who already use it
            TextView alt = new TextView(this);
            alt.setText("You can also start DockPad through Shizuku: open Shizuku and allow DockPad.");
            alt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            alt.setPadding(0, dp(8), 0, 0);
            setupSteps.addView(alt);
        }
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

    void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    void beginPairing() {
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
            else new DpDialog(this)
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

    private void startOwnService(boolean userAsked) {
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
                renderSetup();
                renderBridge(false, null);
                bridgeSubtitle.setText("Couldn't turn on: " + r.message);
                if (!userAsked) return; // automatic attempt: just show it on the card
                MaterialAlertDialogBuilder d = new DpDialog(this)
                        .setTitle("DockPad couldn't turn on")
                        .setMessage(r.message)
                        .setPositiveButton("OK", null);
                if (r.needsPairing) {
                    d.setNeutralButton("Pair again", (x, w) -> {
                        prefs.paired(false);
                        renderSetup();
                        beginPairing();
                    });
                }
                d.show();
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
            startOwnService(true);
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
                    lastStatus = o;
                    renderBridge(running, o);
                    if (guide != null) guide.refresh();
                    renderPc(running, running && o.optBoolean("pcMode"));
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
            if (c != null && !c.optBoolean("touchpad") && !c.optBoolean("builtIn")) n++;
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
                if (builtIn) continue; // shown in its own row above
                boolean touchpad = c.optBoolean("touchpad");
                String name = c.optString("name");
                String sub = builtIn ? "Built-in controls" : touchpad ? "Touchpad: click works as a button"
                        : prefs.layout(name).summary() + " · tap to change";
                if (!c.optBoolean("exclusive")) sub += " · couldn't take over: apps may see it twice";
                if (c.has("battery")) {
                    int pct = c.optInt("battery");
                    sub = (c.optBoolean("charging") ? "Charging, " + pct + "%" : "Battery " + pct + "%") + " · " + sub;
                }
                String last = c.optString("lastButton", "");
                if (!last.isEmpty()) sub += " · last: " + last;
                if (prefs.isRenamed(name)) sub = name + " · " + sub;
                View row = addInfoRow(controllersList,
                        builtIn ? R.drawable.ic_handheld : touchpad ? R.drawable.ic_touch : R.drawable.ic_gamepad,
                        prefs.controllerName(name), sub);
                TextView badge = row.findViewById(R.id.row_badge);
                badge.setVisibility(View.VISIBLE);
                int pl = c.optInt("player", 1);
                badge.setText(pl == 0 ? "Press A to join" : "Player " + pl);
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
        row.setDefaultFocusHighlightEnabled(false); // we draw our own outline
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        TextView sub = row.findViewById(R.id.row_subtitle);
        if (subtitle == null) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        parent.addView(row);
        return row;
    }

    // ------------------------------------------------------------------ PC control mode

    private boolean lastPcMode;

    private void setupPcCard() {
        MaterialButton toggle = findViewById(R.id.pc_toggle);
        toggle.setOnClickListener(v -> {
            IBridge b = bridge;
            if (b == null || !lastRunning) {
                Toast.makeText(this, "Turn DockPad on first.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = !lastPcMode;
            bg.execute(() -> {
                try {
                    b.setPcMode(on);
                } catch (Exception ignored) {
                }
            });
        });
        findViewById(R.id.pc_add_shortcut).setOnClickListener(v ->
                editor.addNewWithAction(com.adonzin.dockpad.core.Codes.ACTION_TOGGLE_PC_MODE));
        com.google.android.material.slider.Slider speed = findViewById(R.id.pc_speed);
        speed.setValue(Math.max(0.25f, Math.min(3f, Math.round(prefs.pcSpeed() * 4) / 4f)));
        speed.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            prefs.pcSpeed(value);
            pushSettings();
        });
        MaterialSwitch left = findViewById(R.id.pc_left);
        left.setChecked(prefs.pcPointerLeft());
        left.setOnCheckedChangeListener((b, c) -> {
            prefs.pcPointerLeft(c);
            pushSettings();
        });
    }

    private void renderPc(boolean running, boolean pcOn) {
        lastPcMode = pcOn;
        ((TextView) findViewById(R.id.pc_status)).setText(!running ? "Available when DockPad is on"
                : pcOn ? "On: controllers act as a mouse and keyboard" : "Off");
        ((MaterialButton) findViewById(R.id.pc_toggle)).setText(pcOn ? "Turn off" : "Turn on");
    }

    // ------------------------------------------------------------------ built-in controls

    private void chooseBuiltIn() {
        String[] options = {"Press a button on them", "Detect automatically"};
        new DpDialog(this)
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
        androidx.appcompat.app.AlertDialog waiting = new DpDialog(this).ignoreControllerButtons()
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
                    new DpDialog(this).setMessage("No button press detected.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                new DpDialog(this)
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

    static String holdText(int ms) {
        float sec = ms / 1000f;
        return (sec == Math.round(sec) ? String.valueOf(Math.round(sec)) : String.format(java.util.Locale.US, "%.1f", sec)) + " s";
    }

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
            String title = (s.hold ? "Hold " + holdText(s.holdMs) + " " : "") + s.triggerLabel() + "  →  " + s.actionLabel();
            View row = addInfoRow(shortcutsList, R.drawable.ic_bolt, title, s.deviceLabel());
            row.setFocusable(true);
            row.setClickable(true);
            int index = i;
            row.setOnClickListener(v -> editor.editExisting(index));
        }
    }

    // ------------------------------------------------------------------ settings

    private void buildSettings() {
        controllerSettings.removeAllViews();
        appSettings.removeAllViews();
        LinearLayout updateSettings = findViewById(R.id.update_settings_list);
        updateSettings.removeAllViews();
        addSwitch(updateSettings, "Check when DockPad opens",
                "At most every few hours. Nothing is downloaded until you choose to update.",
                prefs.autoUpdateCheck(), prefs::autoUpdateCheck);
        addSwitch(controllerSettings, "Multiplayer",
                "Player 1 is the built-in controls plus your first external controller. Each further controller becomes the next player (\"DockPad Controller 2\", \"3\", \"4\"). When off, every controller is player 1.",
                prefs.multiplayer(), v -> {
                    prefs.multiplayer(v);
                    pushSettings();
                });
        addSwitch(controllerSettings, "Rumble",
                "Passes game rumble to your controllers (those that have rumble, like PlayStation and Xbox pads). Player 1's rumble is copied from the handheld's own motor, so the handheld still buzzes too unless you turn its vibration off in the emulator. On Retroid devices the first Bluetooth controller you connect loses its rumble (the system replaces it with its own copy); controllers connected after it keep theirs.",
                prefs.rumble(), v -> {
                    prefs.rumble(v);
                    pushSettings();
                });
        addSwitch(controllerSettings, "Players join by pressing A",
                "With multiplayer on, a controller does nothing until you press A (or any face button or Start) on it. Players are numbered in the order they join, not the order they connected. Changing this asks everyone to join again.",
                prefs.joinByPress(), v -> {
                    prefs.joinByPress(v);
                    pushSettings();
                });
        addSwitch(controllerSettings, "Close the quick menu when the last controller goes",
                "If you disconnect your last external controller (from the quick menu or by switching it off) while the quick menu is open, it closes by itself, since you're back to playing on the handheld.",
                prefs.closeMenuWithoutControllers(), prefs::closeMenuWithoutControllers);
        addSwitch(appSettings, "Start automatically after a restart",
                "After pairing, DockPad turns itself back on when the device restarts (needs Wi-Fi).",
                prefs.autoStart(), prefs::autoStart);
        addSwitch(appSettings, "Turn off Wireless debugging once started",
                "DockPad only needs Wireless debugging for a moment while starting. Turning it off afterwards is safer.",
                prefs.turnOffWirelessDebugging(), prefs::turnOffWirelessDebugging);
        addSwitch(appSettings, "Stay ready while docked",
                "While the handheld is on a charger (a dock or a desk charger), DockPad keeps it out of deep sleep, so "
                        + "DockPad and your controllers are ready the moment you wake it, even after hours. Off the "
                        + "charger, sleep is normal again. It uses a little more power while charging. Diagnostics "
                        + "shows a log of each night.",
                prefs.stayReadyDocked(), v -> {
                    prefs.stayReadyDocked(v);
                    pushSettings();
                });
        View guideRow = getLayoutInflater().inflate(R.layout.item_row, appSettings, false);
        ((ImageView) guideRow.findViewById(R.id.row_icon)).setImageResource(R.drawable.ic_lib_info);
        ((TextView) guideRow.findViewById(R.id.row_title)).setText("First-run guide");
        ((TextView) guideRow.findViewById(R.id.row_subtitle)).setText(
                "Step through setup again: pairing, turning on, a controller and the quick menu.");
        guideRow.setFocusable(true);
        guideRow.setClickable(true);
        guideRow.setOnClickListener(v -> guide.show());
        appSettings.addView(guideRow);
        View resetRow = getLayoutInflater().inflate(R.layout.item_row, controllerSettings, false);
        ((ImageView) resetRow.findViewById(R.id.row_icon)).setImageResource(R.drawable.ic_gamepad);
        ((TextView) resetRow.findViewById(R.id.row_title)).setText("Reset all controller layouts");
        ((TextView) resetRow.findViewById(R.id.row_subtitle)).setText(
                "Clears every controller's button swaps and changes (shortcuts stay).");
        resetRow.setFocusable(true);
        resetRow.setClickable(true);
        resetRow.setOnClickListener(v -> new DpDialog(this)
                .setTitle("Reset all controller layouts?")
                .setMessage("Every controller goes back to the standard layout.")
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.layouts(new java.util.LinkedHashMap<>());
                    layoutsChanged();
                    Toast.makeText(this, "All controller layouts reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show());
        controllerSettings.addView(resetRow);
        addSwitch(controllerSettings, "Touchpad as a button",
                "On controllers with a touchpad (DualShock 4, DualSense), pressing the pad becomes a button you can use in shortcuts instead of a mouse.",
                prefs.touchpadAsButton(), v -> {
                    prefs.touchpadAsButton(v);
                    pushSettings();
                });
    }

    interface BoolSetter {
        void set(boolean v);
    }

    private void addSwitch(LinearLayout parent, String title, String subtitle, boolean value, BoolSetter onChange) {
        View row = getLayoutInflater().inflate(R.layout.item_switch, parent, false);
        ((TextView) row.findViewById(R.id.switch_title)).setText(title);
        ((TextView) row.findViewById(R.id.switch_subtitle)).setText(subtitle);
        MaterialSwitch sw = row.findViewById(R.id.switch_toggle);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> onChange.set(checked));
        row.setOnClickListener(v -> sw.toggle());
        parent.addView(row);
    }

    // ------------------------------------------------------------------ reset

    // ------------------------------------------------------------------ themes

    private java.util.function.Consumer<android.net.Uri> themeFileDone;

    private final androidx.activity.result.ActivityResultLauncher<String[]> pickThemeFile =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null && themeFileDone != null) themeFileDone.accept(uri);
                    });

    private void showThemeName() {
        ((TextView) findViewById(R.id.theme_name)).setText("In use: " + MenuTheme.load(this).name
                + ". Changes below change this look; save it as a theme to keep a copy.");
    }

    // ------------------------------------------------------------------ pictures and videos for the quick menu

    private java.util.function.Consumer<String> mediaDone;

    private final androidx.activity.result.ActivityResultLauncher<String[]> pickMediaFile =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null || mediaDone == null) return;
                        java.util.function.Consumer<String> done = mediaDone;
                        Toast.makeText(this, "Adding…", Toast.LENGTH_SHORT).show();
                        bg.execute(() -> {
                            try {
                                String name = MenuMedia.importFile(this, uri);
                                ui.post(() -> done.accept(name));
                            } catch (Exception e) {
                                ui.post(() -> Toast.makeText(this, "Couldn't add it: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show());
                            }
                        });
                    });

    /** Picks a picture/GIF (or video too) with Android's picker and copies it into DockPad. */
    void pickMedia(boolean videoToo, java.util.function.Consumer<String> done) {
        mediaDone = done;
        try {
            pickMediaFile.launch(videoToo ? new String[]{"image/*", "video/*"} : new String[]{"image/*"});
        } catch (Exception e) {
            Toast.makeText(this, "There's no file picker on this handheld. Install a file manager app, then try again.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ fonts for the quick menu

    private java.util.function.Consumer<String> fontDone;

    private final androidx.activity.result.ActivityResultLauncher<String[]> pickFont =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null || fontDone == null) return;
                        java.util.function.Consumer<String> done = fontDone;
                        bg.execute(() -> {
                            try {
                                String name = MenuStyle.importFont(this, uri);
                                ui.post(() -> {
                                    Toast.makeText(this, "Font added: " + name, Toast.LENGTH_SHORT).show();
                                    done.accept(name);
                                });
                            } catch (Exception e) {
                                ui.post(() -> Toast.makeText(this, "Couldn't add the font: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show());
                            }
                        });
                    });

    // ------------------------------------------------------------------ backup & sharing

    private final androidx.activity.result.ActivityResultLauncher<String[]> pickBackup =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) restoreBackup(uri);
                    });

    private void saveBackup() {
        bg.execute(() -> {
            try {
                android.net.Uri uri = Backup.save(this);
                String name = Backup.fileName(this, uri);
                ui.post(() -> new DpDialog(this)
                        .setTitle("Backup saved")
                        .setMessage("Saved as " + name + " in Download/DockPad.\n\nTo restore it later, use "
                                + "\"Restore or import a backup\" and pick that file.")
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Share", (d, w) -> {
                            Intent send = new Intent(Intent.ACTION_SEND)
                                    .setType("application/json")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_SUBJECT, "My DockPad setup")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, "Share DockPad backup"));
                        })
                        .show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void restoreBackup(android.net.Uri uri) {
        bg.execute(() -> {
            Backup.Contents b;
            try {
                b = Backup.read(this, uri);
            } catch (Exception e) {
                ui.post(() -> new DpDialog(this).setTitle("Can't use this file")
                        .setMessage(e.getMessage()).setPositiveButton("OK", null).show());
                return;
            }
            ui.post(() -> {
                String[] labels = new String[b.present.size()];
                boolean[] checked = new boolean[labels.length];
                for (int i = 0; i < labels.length; i++) {
                    int sec = b.present.get(i);
                    String d = b.describe(sec);
                    labels[i] = Backup.SECTION_LABELS[sec] + (d.isEmpty() ? "" : " · " + d);
                    checked[i] = true;
                }
                new DpDialog(this)
                        .setTitle("Restore from backup" + (b.created.isEmpty() ? "" : " (" + b.created + ")"))
                        .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                        .setPositiveButton("Restore", (d, w) -> {
                            java.util.List<Integer> chosen = new java.util.ArrayList<>();
                            for (int i = 0; i < labels.length; i++) if (checked[i]) chosen.add(b.present.get(i));
                            if (chosen.isEmpty()) return;
                            new DpDialog(this)
                                    .setTitle("Replace your current setup?")
                                    .setMessage("The chosen parts replace what you have now. Pairing and the "
                                            + "connection to this device stay as they are.")
                                    .setPositiveButton("Replace", (d2, w2) -> {
                                        Backup.restore(this, b, chosen);
                                        afterRestore();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void afterRestore() {
        prefs = new Prefs(this);
        buildSettings();
        renderShortcuts();
        renderCommands();
        lastControllersJson = "";
        ((com.google.android.material.slider.Slider) findViewById(R.id.pc_speed))
                .setValue(Math.max(0.25f, Math.min(3f, Math.round(prefs.pcSpeed() * 4) / 4f)));
        ((MaterialSwitch) findViewById(R.id.pc_left)).setChecked(prefs.pcPointerLeft());
        pushSettings();
        Toast.makeText(this, "Backup restored", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ updates

    private boolean checkingUpdates;

    private void checkUpdates(boolean manual) {
        if (checkingUpdates) return;
        checkingUpdates = true;
        TextView status = findViewById(R.id.update_status);
        if (manual) status.setText("Checking…");
        bg.execute(() -> {
            Updates.Release r = null;
            String err = null;
            try {
                r = Updates.latest();
                prefs.lastUpdateCheck(System.currentTimeMillis());
            } catch (Exception e) {
                err = e.getMessage();
            }
            Updates.Release rel = r;
            String error = err;
            ui.post(() -> {
                checkingUpdates = false;
                if (isFinishing()) return;
                if (rel == null) {
                    status.setText("You have build " + BuildConfig.VERSION_CODE + ". Couldn't check: "
                            + (error == null ? "no connection" : error));
                    return;
                }
                if (!rel.isNewer()) {
                    status.setText("You have build " + BuildConfig.VERSION_CODE + ", the newest.");
                    if (manual) Toast.makeText(this, "DockPad is up to date", Toast.LENGTH_SHORT).show();
                    return;
                }
                status.setText("You have build " + BuildConfig.VERSION_CODE + ". Build " + rel.build + " is available.");
                if (manual || rel.build != prefs.dismissedBuild()) showUpdate(rel);
            });
        });
    }

    private void showUpdate(Updates.Release rel) {
        StringBuilder msg = new StringBuilder("You have build ").append(BuildConfig.VERSION_CODE).append('.');
        if (!rel.changes.isEmpty()) {
            msg.append("\n\nWhat's new:");
            for (String c : rel.changes) msg.append("\n• ").append(c);
        }
        boolean canInstall = bridge != null && rel.apkUrl != null && !rel.apkUrl.isEmpty();
        msg.append(canInstall
                ? "\n\nDockPad downloads and installs it by itself, then reopens. Your settings stay."
                : "\n\nTurn DockPad on to install updates by itself, or download it and install it yourself.");
        DpDialog d = new DpDialog(this);
        d.setTitle("Build " + rel.build + " is available").setMessage(msg.toString())
                .setNegativeButton("Later", (x, w) -> prefs.dismissedBuild(rel.build));
        if (canInstall) {
            d.setPositiveButton("Update now", (x, w) -> {
                IBridge b = bridge;
                Toast.makeText(this, "Downloading build " + rel.build + "… DockPad reopens when it's done",
                        Toast.LENGTH_LONG).show();
                bg.execute(() -> {
                    try {
                        b.installUpdate(rel.apkUrl, rel.build);
                    } catch (Exception e) {
                        ui.post(() -> Toast.makeText(this, "Couldn't start the update: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
                    }
                });
            });
            d.setNeutralButton("Download", (x, w) -> openUrl(rel.pageUrl));
        } else {
            d.setPositiveButton("Download", (x, w) -> openUrl(rel.pageUrl));
        }
        d.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void showReset() {
        String[] options = {
                "Restart DockPad\nStops and starts DockPad's background service again.",
                "Pair again\nGoes back to the pairing step, for when connecting or starting keeps failing."};
        new DpDialog(this)
                .setTitle("Reset connection")
                .setItems(options, (d, which) -> {
                    if (which == 0) restartService();
                    else confirmPairAgain();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmPairAgain() {
        new DpDialog(this)
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
            String crash = DockPadApp.lastCrash(this);
            String text = "DockPad " + BuildConfig.VERSION_NAME + "\n" + s
                    + (crash.isEmpty() ? "" : "\n\nLast app crash:\n" + crash);
            ui.post(() -> {
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(12);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(24), dp(8), dp(24), 0);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new DpDialog(this)
                        .setTitle("Diagnostics")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .setNegativeButton("Check buttons", (d, w) -> checkButtons())
                        .setNeutralButton("Copy", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("DockPad diagnostics", text));
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        });
    }

    /** Records everything every input device reports for 10 seconds. */
    private void checkButtons() {
        IBridge b = bridge;
        if (b == null || !lastRunning) {
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
        androidx.appcompat.app.AlertDialog waiting = new DpDialog(this).ignoreControllerButtons()
                .setTitle("Press the buttons to check")
                .setMessage("For the next 10 seconds, press the buttons you want to check, one at a time, "
                        + "on any controller. Everything every device reports is recorded.")
                .setView(wrap)
                .setCancelable(false)
                .show();
        bg.execute(() -> {
            String r;
            try {
                r = b.recordAllInput(10000);
            } catch (Exception e) {
                r = "Couldn't record: " + e;
            }
            String text = "DockPad " + BuildConfig.VERSION_NAME + " — button check\n" + r;
            ui.post(() -> {
                waiting.dismiss();
                TextView tv = new TextView(this);
                tv.setText(text);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(11);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(24), dp(8), dp(24), 0);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(tv);
                new DpDialog(this)
                        .setTitle("Button check")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Copy", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("DockPad button check", text));
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
