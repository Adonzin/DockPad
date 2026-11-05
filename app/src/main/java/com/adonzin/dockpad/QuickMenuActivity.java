package com.adonzin.dockpad;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.Shortcut;
import com.google.android.material.color.DynamicColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The quick menu: a controller-driven panel over whatever is running. Opened by a shortcut
 * ("Open the quick menu"); B, the same shortcut, or tapping outside closes it.
 */
public class QuickMenuActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private volatile IBridge bridge;
    private boolean running, cursor, swapAB, swapXY;
    private View firstRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish()); // tap outside the panel
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.menu_panel);
        scroll.setClickable(true); // taps on the panel don't close it
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START;
        root.addView(scroll, lp);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(panel);

        TextView title = new TextView(this);
        title.setText("DockPad");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        panel.addView(title);
        status = new TextView(this);
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        status.setPadding(0, 0, 0, dp(12));
        panel.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list);

        setContentView(root);
        build();
        refreshState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish(); // the quick menu shortcut again: close
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) finish(); // something else came to the front
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ content

    /** Built-in items the user can show or hide: id, label (for the settings checklist). */
    static final String[][] ITEMS = {
            {"home", "Home"}, {"back", "Back"}, {"cursor", "Cursor mode on/off"},
            {"nintendo", "Nintendo layout (swap A/B and X/Y)"}, {"swapab", "Swap A/B"}, {"swapxy", "Swap X/Y"},
            {"dpkeyboard", "Keyboard (DockPad)"}, {"keyboard", "Show keyboard (Moonlight)"}, {"screenshot", "Screenshot"},
            {"forceclose", "Force close the game"}, {"controllers", "Controllers list"},
            {"power", "Turn DockPad on/off"}, {"openapp", "Open DockPad"}};

    private java.util.Set<String> hidden;

    private boolean shown(String id) {
        return !hidden.contains(id);
    }

    private void build() {
        list.removeAllViews();
        firstRow = null;
        controllersAnchor = null;
        hidden = new Prefs(this).menuHidden();

        if (shown("home") || shown("back")) section("Navigation");
        if (shown("home")) row(R.drawable.ic_home, "Home", null, () -> command(Codes.ACTION_HOME));
        if (shown("back")) row(R.drawable.ic_back, "Back", null, () -> command(Codes.ACTION_BACK));

        String[] tools = {"dpkeyboard", "cursor", "nintendo", "swapab", "swapxy", "keyboard", "screenshot", "forceclose"};
        boolean anyTool = false;
        for (String t : tools) anyTool |= shown(t);
        if (anyTool) section("Tools");
        if (shown("dpkeyboard")) row(R.drawable.ic_keyboard, "Keyboard",
                "Type into whatever app is open", () -> {
                    finish();
                    Context app = getApplicationContext();
                    ui.postDelayed(() -> KeyboardOverlay.show(app), 300);
                });
        if (shown("cursor")) row(R.drawable.ic_mouse, cursor ? "Turn Cursor mode off" : "Turn Cursor mode on",
                "Controllers act as a mouse and keyboard", () -> withBridge(b -> b.setPcMode(!cursor)));
        if (shown("nintendo")) row(R.drawable.ic_gamepad, "Nintendo layout: " + (swapAB && swapXY ? "on" : "off"),
                "Swaps A/B and X/Y on external controllers", () -> withBridge(b -> b.runCommand(
                        oneAction(Codes.ACTION_SWAP_FACE), 0)));
        if (shown("swapab")) row(R.drawable.ic_gamepad, "Swap A/B: " + (swapAB ? "on" : "off"), null,
                () -> withBridge(b -> b.runCommand(oneAction(Codes.ACTION_SWAP_AB), 0)));
        if (shown("swapxy")) row(R.drawable.ic_gamepad, "Swap X/Y: " + (swapXY ? "on" : "off"), null,
                () -> withBridge(b -> b.runCommand(oneAction(Codes.ACTION_SWAP_XY), 0)));
        if (shown("keyboard")) row(R.drawable.ic_keyboard, "Show keyboard (Moonlight)", null,
                () -> command(Codes.ACTION_MOONLIGHT_KEYBOARD));
        if (shown("screenshot")) row(R.drawable.ic_camera, "Screenshot", null, () -> command(Codes.ACTION_SCREENSHOT));
        if (shown("forceclose")) row(R.drawable.ic_close_app, "Force close the game",
                "Closes the app that's open right now", () -> command(Codes.ACTION_FORCE_CLOSE));

        List<Shortcut> mine = new Prefs(this).menuCommands();
        if (!mine.isEmpty()) {
            section("My commands");
            for (Shortcut c : mine) {
                row(R.drawable.ic_bolt, c.device, c.actionLabel(), () -> {
                    finish();
                    runInBackground(b -> b.runCommand(c.encode(), 350));
                });
            }
        }

        if (shown("controllers")) {
            section("Controllers");
            controllersAnchor = new LinearLayout(this);
            controllersAnchor.setOrientation(LinearLayout.VERTICAL);
            list.addView(controllersAnchor);
        }

        if (shown("power") || shown("openapp")) section("DockPad");
        if (shown("power")) row(R.drawable.ic_power, running ? "Turn DockPad off" : "Turn DockPad on", null,
                () -> withBridge(b -> {
                    new Prefs(this).bridgeWanted(!running);
                    if (running) b.stop();
                    else b.start(true);
                }));
        if (shown("openapp")) row(R.drawable.ic_open, "Open DockPad", null, () -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        if (list.getChildCount() == 0) {
            TextView t = new TextView(this);
            t.setText("Everything is hidden. Choose items in DockPad → Quick menu.");
            list.addView(t);
        }
    }

    private LinearLayout controllersAnchor;

    private void section(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(14), 0, dp(2));
        list.addView(t);
    }

    private void row(int icon, String title, String subtitle, Runnable action) {
        View r = getLayoutInflater().inflate(R.layout.item_row, list, false);
        ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) r.findViewById(R.id.row_title)).setText(title);
        TextView sub = r.findViewById(R.id.row_subtitle);
        if (subtitle == null) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(v -> action.run());
        list.addView(r);
        if (firstRow == null) firstRow = r;
    }

    // ------------------------------------------------------------------ talking to DockPad

    interface BridgeCall {
        void call(IBridge b) throws Exception;
    }

    private void runInBackground(BridgeCall c) {
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            IBridge b = bridge != null ? bridge : BridgeAccess.find(app);
            if (b == null) return;
            try {
                c.call(b);
            } catch (Exception ignored) {
            }
        }, "DockPad-menu").start();
    }

    private static String oneAction(int action) {
        Shortcut s = new Shortcut();
        s.trigger = new int[]{0};
        s.steps.add(new int[]{action});
        return s.encode();
    }

    /** Closes the menu, then runs one action on the game underneath. */
    private void command(int action) {
        String spec = oneAction(action);
        finish();
        runInBackground(b -> b.runCommand(spec, 350));
    }

    /** Runs something and refreshes the menu (it stays open). */
    private void withBridge(BridgeCall c) {
        runInBackground(b -> {
            c.call(b);
            Thread.sleep(250);
            ui.post(this::refreshState);
        });
    }

    private void refreshState() {
        ShizukuLink.connect(() -> runInBackground(b -> {
            bridge = b;
            JSONObject o = new JSONObject(b.statusJson());
            ui.post(() -> {
                boolean r = o.optBoolean("running"), c = o.optBoolean("pcMode");
                boolean ab = o.optBoolean("swapAB"), xy = o.optBoolean("swapXY");
                boolean changed = r != running || c != cursor || ab != swapAB || xy != swapXY;
                running = r;
                cursor = c;
                swapAB = ab;
                swapXY = xy;
                status.setText((running ? "On" : "Off") + (cursor ? " · Cursor mode on" : ""));
                if (changed) {
                    View focused = getCurrentFocus();
                    int index = focused == null ? -1 : list.indexOfChild(focused);
                    build();
                    if (index >= 0 && index < list.getChildCount()) list.getChildAt(index).requestFocus();
                }
                showControllers(o.optJSONArray("controllers"));
            });
        }));
    }

    private void showControllers(JSONArray ctrls) {
        if (controllersAnchor == null) return;
        controllersAnchor.removeAllViews();
        boolean any = false;
        if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
            JSONObject c = ctrls.optJSONObject(i);
            if (c == null || c.optBoolean("touchpad")) continue;
            any = true;
            View r = getLayoutInflater().inflate(R.layout.item_row, controllersAnchor, false);
            ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(
                    c.optBoolean("builtIn") ? R.drawable.ic_handheld : R.drawable.ic_gamepad);
            ((TextView) r.findViewById(R.id.row_title)).setText(
                    c.optBoolean("builtIn") ? "Built-in controls" : c.optString("name"));
            r.findViewById(R.id.row_subtitle).setVisibility(View.GONE);
            TextView badge = r.findViewById(R.id.row_badge);
            badge.setVisibility(View.VISIBLE);
            badge.setText("P" + c.optInt("player", 1));
            controllersAnchor.addView(r);
        }
        if (!any) {
            TextView t = new TextView(this);
            t.setText(running ? "No controllers found" : "DockPad is off");
            t.setPadding(dp(6), dp(6), 0, dp(6));
            controllersAnchor.addView(t);
        }
    }

    // ------------------------------------------------------------------ controller keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        // make sure something is highlighted as soon as a controller is used
        if (e.getAction() == KeyEvent.ACTION_DOWN && getCurrentFocus() == null && firstRow != null
                && (DpDialog.Controller.isFromController(e) || isDpad(kc))) {
            firstRow.requestFocus();
            if (kc != KeyEvent.KEYCODE_BUTTON_B && kc != KeyEvent.KEYCODE_BACK) return true;
        }
        if (DpDialog.Controller.isFromController(e)
                && DpDialog.Controller.handle(e, getWindow().getDecorView(), this::finish)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isDpad(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
