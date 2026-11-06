package com.adonzin.dockpad;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.ControllerLayout;
import com.adonzin.dockpad.core.Shortcut;
import com.google.android.material.color.DynamicColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The quick menu: a controller-driven panel over whatever is running. Opened by a shortcut
 * ("Open the quick menu"); B, the same shortcut, or tapping outside closes it. It is drawn from the
 * menu theme ({@link MenuTheme}) in one of several layouts ({@link MenuRender}); Y arranges items.
 */
public class QuickMenuActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView status, hint;
    private String statusText = "";
    private volatile IBridge bridge;
    private boolean running, cursor, swapAB, swapXY, turbo = true, turboConfigured;
    private MenuTheme theme;
    private MenuRender.Renderer renderer;
    private List<MenuRender.Item> items = new ArrayList<>();
    private int selected = -1;
    private String selectedId;
    private boolean arranging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        root = new FrameLayout(this);
        root.setOnClickListener(v -> {
            if (!arranging) finish(); // tap outside the panel
        });
        noHighlight(root);
        // over the whole screen: behind the status bar, the gesture bar and any cutout
        getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowManager.LayoutParams wl = getWindow().getAttributes();
        wl.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wl);
        setContentView(root);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); // the look's backdrop does this
        hideSystemBars();
        theme = MenuTheme.load(this);
        rebuild();
        refreshState();
    }

    /** Hides the status and navigation bars while the menu is open (a swipe from the edge shows them briefly). */
    private void hideSystemBars() {
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic == null) return;
        ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ic.hide(android.view.WindowInsets.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private static void noHighlight(View v) {
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        v.setDefaultFocusHighlightEnabled(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish(); // the quick menu shortcut again: close
    }

    // keep the controllers list (and on/off states) live while the menu is open
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            // skip if the last check is still running (but never wait more than 3 s on it)
            if (!refreshing || android.os.SystemClock.uptimeMillis() - refreshStarted > 3000) refreshState();
            ui.postDelayed(this, 1000);
        }
    };
    private volatile boolean refreshing;
    private volatile long refreshStarted;

    @Override
    protected void onResume() {
        super.onResume();
        ui.postDelayed(poll, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
        if (arranging) saveArrangement();
        if (!isFinishing()) { // something else came to the front
            quickClose = true;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        stopMusic();
        // let a closing sound finish before letting go of the sounds
        android.media.SoundPool p = pool;
        pool = null;
        if (p != null) new Handler(Looper.getMainLooper()).postDelayed(p::release, 1500);
        super.onDestroy();
    }

    private void back() {
        play("back");
        finish();
    }

    private boolean closing, quickClose;

    /** Closes with the theme's closing animation and sound (or at once, if something else took over). */
    @Override
    public void finish() {
        if (closing) return;
        closing = true;
        stopMusic();
        View panel = currentPanel;
        String anim = theme == null ? "none" : theme.fx.close;
        if (quickClose || panel == null || anim.equals("none") || isDestroyed()) {
            reallyFinish();
            return;
        }
        play("close");
        long ms = (long) (180 * theme.fx.speedFactor());
        animatePanel(panel, anim, false, ms, this::reallyFinish);
        root.animate().alpha(0f).setDuration(ms).start();
    }

    private void reallyFinish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ content

    /** Built-in items the user can show or hide: id, label (for the settings checklist). */
    static final String[][] ITEMS = {
            {"home", "Home"}, {"back", "Back"}, {"cursor", "Cursor mode on/off"},
            {"turbo", "Rapid fire on/off"}, {"turboedit", "Rapid fire buttons (edit)"}, {"nintendo", "Nintendo layout (swap A/B and X/Y)"}, {"swapab", "Swap A/B"}, {"swapxy", "Swap X/Y"},
            {"dpkeyboard", "Keyboard (DockPad)"}, {"keyboard", "Show keyboard (Moonlight)"}, {"screenshot", "Screenshot"},
            {"forceclose", "Force close the game"}, {"controllers", "Controllers list"}, {"builtinctrl", "Built-in controls in the controllers list"},
            {"pair", "Pair a new controller"},
            {"power", "Turn DockPad on/off"}, {"openapp", "Open DockPad"}};

    private java.util.Set<String> hidden;
    private String section = "";

    private boolean shown(String id) {
        return !hidden.contains(id);
    }

    private void add(String id, int icon, String title, String subtitle, Runnable action) {
        if (shown(id)) items.add(new MenuRender.Item(id, id, section, icon, title, subtitle, null, action));
    }

    /** The items, in their default order (the user's order is applied after). */
    private List<MenuRender.Item> collect() {
        items = new ArrayList<>();
        hidden = new Prefs(this).menuHidden();

        section = "Navigation";
        add("home", R.drawable.ic_home, "Home", null, () -> command(Codes.ACTION_HOME));
        add("back", R.drawable.ic_back, "Back", null, () -> command(Codes.ACTION_BACK));

        section = "Tools";
        add("dpkeyboard", R.drawable.ic_keyboard, KeyboardOverlay.isOpen() ? "Close keyboard" : "Keyboard",
                "Type into whatever app is open", () -> {
                    finish();
                    Context app = getApplicationContext();
                    ui.postDelayed(() -> KeyboardOverlay.toggle(app), 300);
                });
        add("cursor", R.drawable.ic_mouse, cursor ? "Turn Cursor mode off" : "Turn Cursor mode on",
                "Controllers act as a mouse and keyboard", () -> withBridge(b -> b.setPcMode(!cursor)));
        add("turbo", R.drawable.ic_bolt, "Rapid fire: " + (turbo ? "on" : "off"),
                turboConfigured ? "For the buttons set in each controller's settings"
                        : "No rapid-fire buttons set yet (DockPad → controller settings)",
                () -> withBridge(b -> b.runCommand(oneAction(Codes.ACTION_TOGGLE_TURBO), 0)));
        add("turboedit", R.drawable.ic_bolt, "Rapid fire buttons…",
                "Choose which buttons repeat, and how fast", this::editTurbo);
        add("nintendo", R.drawable.ic_gamepad, "Nintendo layout: " + (swapAB && swapXY ? "on" : "off"),
                "Swaps A/B and X/Y on external controllers", () -> withBridge(b -> b.runCommand(
                        oneAction(Codes.ACTION_SWAP_FACE), 0)));
        add("swapab", R.drawable.ic_gamepad, "Swap A/B: " + (swapAB ? "on" : "off"), null,
                () -> withBridge(b -> b.runCommand(oneAction(Codes.ACTION_SWAP_AB), 0)));
        add("swapxy", R.drawable.ic_gamepad, "Swap X/Y: " + (swapXY ? "on" : "off"), null,
                () -> withBridge(b -> b.runCommand(oneAction(Codes.ACTION_SWAP_XY), 0)));
        add("keyboard", R.drawable.ic_keyboard, "Show keyboard (Moonlight)", null,
                () -> command(Codes.ACTION_MOONLIGHT_KEYBOARD));
        add("screenshot", R.drawable.ic_camera, "Screenshot", null, () -> command(Codes.ACTION_SCREENSHOT));
        add("forceclose", R.drawable.ic_close_app, "Force close the game",
                "Closes the app that's open right now", () -> command(Codes.ACTION_FORCE_CLOSE));

        section = "My commands";
        for (Shortcut c : new Prefs(this).menuCommands()) {
            String id = commandId(c);
            boolean stay = isAdjustment(c);
            items.add(new MenuRender.Item(id, id, section, R.drawable.ic_bolt, c.device, c.actionLabel(), null, () -> {
                if (stay) { // volume, brightness, media: keep the menu, so it can be pressed again
                    runInBackground(b -> b.runCommand(c.encode(), 0));
                    return;
                }
                finish();
                runInBackground(b -> b.runCommand(c.encode(), 350));
            }));
        }

        section = "Controllers";
        if (shown("controllers")) addControllers();
        add("pair", R.drawable.ic_add, "Pair a new controller…",
                "Find a controller in pairing mode and connect it", () -> PairDialog.show(this));

        section = "DockPad";
        add("power", R.drawable.ic_power, running ? "Turn DockPad off" : "Turn DockPad on", null,
                () -> withBridge(b -> {
                    new Prefs(this).bridgeWanted(!running);
                    if (running) b.stop();
                    else b.start(true);
                }));
        add("openapp", R.drawable.ic_open, "Open DockPad", null, () -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        return organise(items);
    }

    /** Section of each section name, for ordering. */
    private final Map<String, Integer> sectionRank = new HashMap<>();

    /** The user's own section names, sections and item names (DockPad → Organise sections & names). */
    private List<MenuRender.Item> organise(List<MenuRender.Item> in) {
        MenuCustom mc = MenuCustom.load(this);
        List<String> keys = mc.sections();
        sectionRank.clear();
        MenuRender.sectionIcons.clear();
        List<MenuRender.Item> out = new ArrayList<>();
        for (MenuRender.Item it : in) {
            String key = mc.sectionOf(it.group, it.section);
            String shown = mc.sectionName(key);
            if (!sectionRank.containsKey(shown)) sectionRank.put(shown, Math.max(0, keys.indexOf(key)));
            String title = it.group.equals("controllers") ? it.title : mc.titleOf(it.id, it.title);
            int icon = it.group.equals("controllers") ? it.icon : mc.iconOf(it.id, it.icon);
            MenuRender.Item item = new MenuRender.Item(it.id, it.group, shown, icon, title, it.subtitle, it.badge, it.action);
            if (!it.group.equals("controllers")) item.iconFile = mc.iconFileOf(it.id);
            out.add(item);
            int sIcon = IconLibrary.resolve(mc.sectionIcon.get(key));
            if (sIcon != 0) MenuRender.sectionIcons.put(shown, sIcon);
        }
        items = out;
        return out;
    }

    /** Commands that only adjust something (volume, brightness, media): the menu stays open for them. */
    static boolean isAdjustment(Shortcut c) {
        if (c.steps.isEmpty()) return false;
        for (int[] step : c.steps) {
            for (int code : step) {
                boolean ok = code == Codes.ACTION_BRIGHTNESS_UP || code == Codes.ACTION_BRIGHTNESS_DOWN
                        || code == Codes.ACTION_MUTE || code == Codes.ACTION_PLAY_PAUSE
                        || code == Codes.ACTION_NEXT_TRACK || code == Codes.ACTION_PREVIOUS_TRACK
                        || code == Codes.KEY_VOLUMEUP || code == Codes.KEY_VOLUMEDOWN
                        || code == Codes.KB + Codes.KEY_VOLUMEUP || code == Codes.KB + Codes.KEY_VOLUMEDOWN
                        || code == Codes.KB + 113 /* KEY_MUTE */;
                if (!ok) return false;
            }
        }
        return true;
    }

    static String commandId(Shortcut c) {
        return "cmd:" + Integer.toHexString(c.encode().hashCode());
    }

    /** Each built-in item's own icon (for the organiser). */
    static int defaultIcon(String id) {
        switch (id) {
            case "home": return R.drawable.ic_home;
            case "back": return R.drawable.ic_back;
            case "dpkeyboard": case "keyboard": return R.drawable.ic_keyboard;
            case "cursor": return R.drawable.ic_mouse;
            case "nintendo": case "swapab": case "swapxy": case "controllers": return R.drawable.ic_gamepad;
            case "screenshot": return R.drawable.ic_camera;
            case "forceclose": return R.drawable.ic_close_app;
            case "pair": return R.drawable.ic_add;
            case "power": return R.drawable.ic_power;
            case "openapp": return R.drawable.ic_open;
            default: return R.drawable.ic_bolt;
        }
    }

    /** Which section each built-in item starts in (for the organiser). */
    static String defaultSection(String id) {
        switch (id) {
            case "home": case "back": return "Navigation";
            case "controllers": case "pair": return "Controllers";
            case "power": case "openapp": return "DockPad";
            default: return id.startsWith("cmd:") ? "My commands" : "Tools";
        }
    }

    private JSONArray lastControllers;
    private String controllersKey = "";

    /** Remembers the controllers; returns true if what the menu shows for them changed. */
    private boolean showControllers(JSONArray ctrls) {
        externals.clear();
        if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
            JSONObject c = ctrls.optJSONObject(i);
            if (c != null && !c.optBoolean("touchpad") && !c.optBoolean("builtIn")
                    && !externals.contains(c.optString("name"))) externals.add(c.optString("name"));
        }
        StringBuilder key = new StringBuilder(running ? "on" : "off");
        if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
            JSONObject c = ctrls.optJSONObject(i);
            if (c != null) key.append('|').append(c.optString("name")).append(c.optInt("player")).append(c.optBoolean("touchpad"))
                    .append(c.optInt("battery", -1)).append(c.optBoolean("charging"));
        }
        lastControllers = ctrls;
        if (key.toString().equals(controllersKey)) return false;
        controllersKey = key.toString();
        return true;
    }

    private void addControllers() {
        Prefs prefs = new Prefs(this);
        boolean any = false;
        JSONArray ctrls = lastControllers;
        if (ctrls != null) for (int i = 0; i < ctrls.length(); i++) {
            JSONObject c = ctrls.optJSONObject(i);
            if (c == null || c.optBoolean("touchpad")) continue;
            boolean builtIn = c.optBoolean("builtIn");
            if (builtIn && !shown("builtinctrl")) continue;
            any = true;
            String name = c.optString("name");
            int pl = c.optInt("player", 1);
            String badge = pl == 0 ? "Press A to join" : "P" + pl;
            String sub = null;
            Runnable action = () -> { };
            if (!builtIn) {
                sub = "Select to disconnect";
                if (c.has("battery")) {
                    int pct = c.optInt("battery");
                    sub = (c.optBoolean("charging") ? "Charging, " + pct + "%" : "Battery " + pct + "%")
                            + (pct <= 15 && !c.optBoolean("charging") ? " (low)" : "") + " · " + sub;
                }
                action = () -> confirmDisconnect(name);
            }
            items.add(new MenuRender.Item("ctrl:" + name + ":" + i, "controllers", section,
                    builtIn ? R.drawable.ic_handheld : R.drawable.ic_gamepad,
                    builtIn ? "Built-in controls" : prefs.controllerName(name), builtIn ? "Always player 1" : sub, badge, action));
        }
        if (!any) {
            items.add(new MenuRender.Item("ctrl:none", "controllers", section, 0,
                    running ? "No controllers found" : "DockPad is off", null, null, null));
        }
    }

    /** Puts the items in the user's order (by groups; within sections when they're shown). */
    private List<MenuRender.Item> ordered(List<MenuRender.Item> in) {
        List<String> order = MenuTheme.order(this);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        List<String> sections = new ArrayList<>();
        for (MenuRender.Item it : in) if (!sections.contains(it.section)) sections.add(it.section);
        List<MenuRender.Item> out = new ArrayList<>(in);
        boolean bySection = theme.bySection();
        // stable sort: section first (if shown), then the user's rank, then the default order
        List<MenuRender.Item> original = new ArrayList<>(in);
        out.sort((a, b) -> {
            if (bySection) {
                int s = Integer.compare(sectionRank.getOrDefault(a.section, sections.indexOf(a.section) + 100),
                        sectionRank.getOrDefault(b.section, sections.indexOf(b.section) + 100));
                if (s != 0) return s;
            }
            int ra = rank.containsKey(a.group) ? rank.get(a.group) : Integer.MAX_VALUE;
            int rb = rank.containsKey(b.group) ? rank.get(b.group) : Integer.MAX_VALUE;
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(original.indexOf(a), original.indexOf(b));
        });
        return out;
    }

    /** Builds the menu again (keeping the highlight on the same item). */
    private void rebuild() {
        stopLoop();
        if (renderer != null && selected >= 0 && selected < renderer.selectable.size()) {
            selectedId = renderer.selectable.get(selected).id;
        }
        List<MenuRender.Item> list = ordered(collect());
        renderer = MenuRender.create(this, theme);
        renderer.build(list);
        root.removeAllViews();
        String frame = theme.frame();
        View panel;
        int defaultDim; // how dark the game gets behind each style, unless the look says otherwise
        switch (frame) {
            case "ring":
                defaultDim = 70;
                panel = radialPanel();
                break;
            case "band":
                defaultDim = 55;
                panel = carouselPanel();
                break;
            case "screen":
                defaultDim = theme.layout.equals("xmb") ? 60 : 50;
                panel = screenPanel();
                break;
            case "rail":
                defaultDim = 35;
                panel = railPanel();
                break;
            default:
                defaultDim = 35;
                panel = sidePanel();
        }
        root.setBackgroundColor(theme.style.backdrop(theme.style.dim >= 0 ? theme.style.dim : defaultDim));
        root.addView(panel);
        applyStyle(panel);
        applyMedia(panel);
        currentPanel = panel;
        if (!opened) {
            opened = true;
            openEffects(panel);
        }
        for (int i = 0; i < renderer.tiles.size(); i++) {
            int index = i;
            View t = renderer.tiles.get(i);
            MenuRender.Item item = renderer.selectable.get(i);
            if (!arranging || !theme.layout.equals("freeform")) t.setOnClickListener(v -> {
                play("select");
                haptic(true);
                item.action.run();
            });
            t.setOnFocusChangeListener((v, has) -> {
                // ignore focus Android moves on its own while a style hides items (tabs, XMB)
                if (has && !selecting) {
                    selected = index;
                    selecting = true;
                    try {
                        renderer.onSelected(index);
                    } finally {
                        selecting = false;
                    }
                }
            });
        }
        int want = 0;
        if (selectedId != null) {
            for (int i = 0; i < renderer.selectable.size(); i++) {
                if (renderer.selectable.get(i).id.equals(selectedId)) want = i;
            }
        }
        selected = renderer.tiles.isEmpty() ? -1 : Math.min(want, renderer.tiles.size() - 1);
        int focus = selected;
        ui.post(() -> select(focus));
        if (arranging && theme.layout.equals("freeform")) enableTouchArranging();
    }

    private void select(int index) {
        if (renderer == null || index < 0 || index >= renderer.tiles.size()) return;
        selected = index;
        selecting = true;
        try {
            renderer.onSelected(index); // first: some styles reveal the item here (tabs, XMB)
            renderer.tiles.get(index).requestFocus();
        } finally {
            selecting = false;
        }
    }

    private boolean selecting;

    /** A move the player made: select, with sound, feedback and highlight motion. */
    private void move(int index) {
        boolean changed = index != selected;
        select(index);
        if (!changed) return;
        play("move");
        haptic(false);
        View t = index >= 0 && index < renderer.tiles.size() ? renderer.tiles.get(index) : null;
        if (t == null) return;
        highlightMotion(t);
    }

    // ---- highlight motions

    private android.animation.ValueAnimator loop;
    private Runnable loopReset;

    private void stopLoop() {
        if (loop != null) loop.cancel();
        loop = null;
        if (loopReset != null) loopReset.run();
        loopReset = null;
    }

    private void highlightMotion(View t) {
        stopLoop();
        float k = theme.fx.strength();
        // styles that place items by size or position: use motions that don't fight them
        boolean scales = renderer instanceof MenuRender.CarouselRenderer || renderer instanceof MenuStyles.DockRenderer
                || renderer instanceof MenuStyles.WheelRenderer || theme.style.highlight.equals("grow");
        boolean movesX = renderer instanceof MenuRender.CarouselRenderer || renderer instanceof MenuStyles.DockRenderer;
        boolean movesY = renderer instanceof MenuStyles.WheelRenderer;
        String m = theme.fx.highlight;
        if (scales && (m.equals("bump") || m.equals("jelly"))) m = movesY ? "wiggle" : "hop";
        if (movesX && m.equals("shake")) m = "wiggle";
        if (movesY && m.equals("hop")) m = "wiggle";
        if (movesY && m.equals("float")) m = "sway";
        switch (m) {
            case "bump":
                t.animate().scaleX(1 + 0.08f * k).scaleY(1 + 0.08f * k).setDuration(70)
                        .withEndAction(() -> t.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
                break;
            case "hop": {
                float base = t.getTranslationY();
                t.animate().translationY(base - dp(7) * k).setDuration(80)
                        .withEndAction(() -> t.animate().translationY(base).setDuration(140)
                                .setInterpolator(new android.view.animation.BounceInterpolator()).start()).start();
                break;
            }
            case "wiggle":
                keyframes(t, View.ROTATION, 0, -3 * k, 3 * k, -1.5f * k, 0);
                break;
            case "shake": {
                float base = t.getTranslationX(), d = dp(5) * k;
                keyframes(t, View.TRANSLATION_X, base, base - d, base + d, base - d * 0.6f, base + d * 0.3f, base);
                break;
            }
            case "jelly": {
                android.animation.AnimatorSet set = new android.animation.AnimatorSet();
                set.playTogether(
                        android.animation.ObjectAnimator.ofFloat(t, View.SCALE_X, 1, 1 + 0.14f * k, 1 - 0.05f * k, 1),
                        android.animation.ObjectAnimator.ofFloat(t, View.SCALE_Y, 1, 1 - 0.1f * k, 1 + 0.04f * k, 1));
                set.setDuration(320);
                set.start();
                break;
            }
            case "flip":
                t.setRotationY(0);
                t.animate().rotationY(360).setDuration((long) (380 / Math.max(0.5f, k)))
                        .withEndAction(() -> t.setRotationY(0)).start();
                break;
            case "spin":
                t.setRotation(0);
                t.animate().rotation(360).setDuration((long) (420 / Math.max(0.5f, k)))
                        .withEndAction(() -> t.setRotation(0)).start();
                break;
            case "flash": {
                float a = t.getAlpha();
                keyframes(t, View.ALPHA, a, Math.max(0.1f, a - 0.5f * k), a, Math.max(0.2f, a - 0.3f * k), a);
                break;
            }
            case "pulse":
                if (scales) loopOn(t, View.ALPHA, t.getAlpha(), Math.max(0.3f, t.getAlpha() - 0.25f * k), 700);
                else loopOn(t, View.SCALE_X, 1f, 1 + 0.05f * k, 650);
                break;
            case "float":
                loopOn(t, View.TRANSLATION_Y, t.getTranslationY(), t.getTranslationY() - dp(4) * k, 900);
                break;
            case "sway":
                loopOn(t, View.ROTATION, 0f, 2.5f * k, 800);
                break;
            default:
        }
    }

    private void keyframes(View t, android.util.Property<View, Float> prop, float... values) {
        android.animation.ObjectAnimator an = android.animation.ObjectAnimator.ofFloat(t, prop, values);
        an.setDuration(260 + 30L * values.length);
        an.start();
    }

    /** A motion that keeps going while the item stays highlighted (sways both ways for rotation). */
    private void loopOn(View t, android.util.Property<View, Float> prop, float from, float to, long ms) {
        boolean both = prop == View.ROTATION;
        float start = both ? -to : from;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(start, to);
        va.setDuration((long) (ms * theme.fx.speedFactor()));
        va.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        va.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        va.addUpdateListener(an -> {
            float v = (float) an.getAnimatedValue();
            prop.set(t, v);
            if (prop == View.SCALE_X) View.SCALE_Y.set(t, v);
        });
        loopReset = () -> {
            prop.set(t, from);
            if (prop == View.SCALE_X) View.SCALE_Y.set(t, from);
        };
        loop = va;
        va.start();
    }


    /** List, grid and freeform: a panel at the side, centre or full screen. */
    private View sidePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(24), dp(16), dp(16));
        panel.setClickable(true); // taps on the panel don't close it
        noHighlight(panel);
        TextView title = new TextView(this);
        title.setText("DockPad");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        title.setVisibility(theme.style.showTitle ? View.VISIBLE : View.GONE);
        panel.addView(title);
        status = new TextView(this);
        status.setText(headerText());
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        status.setPadding(0, 0, 0, dp(8));
        panel.addView(status);
        boolean free = theme.layout.equals("freeform") || theme.layout.equals("wheel");
        if (free) {
            panel.addView(renderer.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ScrollView scroll = new ScrollView(this);
            noHighlight(scroll);
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scroll.addView(renderer.view);
            panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        if (renderer.tiles.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Everything is hidden. Choose items in DockPad → Shortcuts → Quick menu.");
            panel.addView(t, 2);
        }
        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        hint.setPadding(dp(4), dp(8), 0, 0);
        panel.addView(hint);
        updateHint();

        MenuStyle st = theme.style;
        GradientDrawable bg;
        float r = 24;
        FrameLayout.LayoutParams lp;
        switch (theme.position) {
            case "right":
                bg = st.panel(this, new float[]{r, r, 0, 0, 0, 0, r, r});
                lp = new FrameLayout.LayoutParams(dp(theme.width), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
                break;
            case "center":
                bg = st.panel(this, new float[]{r, r, r, r, r, r, r, r});
                lp = new FrameLayout.LayoutParams(dp(theme.width), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
                lp.topMargin = lp.bottomMargin = dp(24);
                break;
            case "full":
                bg = st.panel(this, new float[8]);
                lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                break;
            default:
                bg = st.panel(this, new float[]{0, 0, r, r, r, r, 0, 0});
                lp = new FrameLayout.LayoutParams(dp(theme.width), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        }
        panel.setBackground(bg);
        panel.setLayoutParams(lp);
        return panel;
    }

    /** Radial: a ring in the middle of the screen, the game dimmed behind it. */
    private View radialPanel() {
        FrameLayout holder = new FrameLayout(this);
        noHighlight(holder);
        int avail = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) - dp(40); // leave room for the hint
        int size = (int) (avail * theme.sizeFraction());
        holder.setBackground(theme.style.panel(this, null));
        holder.setClickable(true);
        holder.addView(renderer.view, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        status = null;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        noHighlight(column);
        column.addView(holder, new LinearLayout.LayoutParams(size, size));
        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        column.addView(hint);
        updateHint();
        column.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return column;
    }

    /** XMB and edge arc: the style uses the whole screen. */
    private View screenPanel() {
        FrameLayout f = new FrameLayout(this);
        noHighlight(f);
        // XMB fills the screen: the look's panel colours and gradient are its backdrop
        if (theme.layout.equals("xmb")) f.setBackground(theme.style.panel(this, new float[8]));
        f.setClickable(!theme.layout.equals("arc")); // arc: taps away from it close the menu
        f.addView(renderer.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setText(headerText());
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        status.setPadding(dp(20), dp(14), dp(20), 0);
        f.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, theme.layout.equals("arc") && !theme.position.equals("right")
                ? Gravity.TOP | Gravity.END : Gravity.TOP | Gravity.START));
        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        hint.setPadding(dp(20), 0, dp(20), dp(10));
        f.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        updateHint();
        f.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** Rail: a thin column at the side, with the name bubble beside it. */
    private View railPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        noHighlight(col);
        col.setClipChildren(false);
        col.addView(renderer.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1));
        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        hint.setPadding(dp(10), dp(4), dp(10), dp(8));
        col.addView(hint);
        updateHint();
        status = null;
        col.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT, theme.position.equals("right") ? Gravity.END : Gravity.START));
        return col;
    }

    /** Carousel: a full-width strip at the top, middle or bottom. */
    private View carouselPanel() {
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.VERTICAL);
        band.setPadding(0, dp(12), 0, dp(10));
        band.setClickable(true);
        noHighlight(band);
        band.setClipChildren(false);
        band.setClipToPadding(false);
        status = new TextView(this);
        status.setText(headerText());
        status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        status.setPadding(dp(20), 0, 0, dp(4));
        band.addView(status);
        band.addView(renderer.view);
        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        band.addView(hint);
        updateHint();
        band.setBackground(theme.style.panel(this, new float[8]));
        int g = theme.band.equals("top") ? Gravity.TOP : theme.band.equals("middle") ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM;
        band.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, g));
        return band;
    }

    /** The header line: title (for strips), status, clock and battery, as the look asks. */
    private String headerText() {
        MenuStyle st = theme.style;
        List<String> parts = new ArrayList<>();
        boolean side = theme.frame().equals("side");
        if (!side && st.showTitle) parts.add("DockPad");
        if (st.showStatus && !statusText.isEmpty()) parts.add(statusText);
        if (st.showClock) parts.add(android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()));
        if (st.showBattery) {
            android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
            if (bm != null) {
                int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (pct > 0) parts.add("🔋 " + pct + "%" + (bm.isCharging() ? " ⚡" : ""));
            }
        }
        return String.join("  ·  ", parts);
    }

    // ------------------------------------------------------------------ animation, sound, feedback

    private View currentPanel;
    private boolean opened;
    private android.media.SoundPool pool;
    private final Map<String, Integer> soundIds = new HashMap<>();
    private final java.util.Set<Integer> loadedSounds = new java.util.HashSet<>();
    private android.media.MediaPlayer music;
    private String pendingSound;

    private void openEffects(View panel) {
        loadSounds();
        play("open");
        startMusic();
        MenuFx fx = theme.fx;
        long ms = (long) (220 * fx.speedFactor());
        if (!fx.open.equals("none")) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(ms).start();
            animatePanel(panel, fx.open, true, ms, null);
        }
        if (fx.items.equals("stagger")) {
            boolean slides = renderer instanceof MenuRender.ListRenderer || renderer instanceof MenuRender.GridRenderer
                    || renderer instanceof MenuStyles.TabsRenderer;
            int n = renderer.tiles.size();
            long step = Math.max(12, Math.min(40, (long) (300 * fx.speedFactor()) / Math.max(1, n)));
            for (int i = 0; i < n; i++) {
                View t = renderer.tiles.get(i);
                float a = t.getAlpha();
                t.setAlpha(0f);
                if (slides) t.setTranslationY(dp(14));
                android.view.ViewPropertyAnimator an = t.animate().alpha(a).setStartDelay(ms / 2 + i * step)
                        .setDuration((long) (160 * fx.speedFactor()));
                if (slides) an.translationY(0);
                an.start();
            }
        }
    }

    /** Animates a panel in (or out, reversed). */
    private void animatePanel(View p, String kind, boolean in, long ms, Runnable end) {
        float w = Math.max(p.getWidth(), dp(320)), h = Math.max(p.getHeight(), dp(240));
        float fromX = 0, fromY = 0, fromScale = 1, fromRot = 0, fromAlpha = 0;
        switch (kind) {
            case "slide": {
                String frame = theme.frame();
                float k = Math.min(1f, theme.fx.strength());
                if (frame.equals("band")) fromY = (theme.band.equals("top") ? -h : h) * k;
                else if (frame.equals("side") || frame.equals("rail")) {
                    fromX = (theme.position.equals("right") ? w : theme.position.equals("left") ? -w : 0) * k;
                    if (fromX == 0) fromY = dp(80) * theme.fx.strength();
                } else fromY = dp(80) * theme.fx.strength();
                fromAlpha = 0.2f;
                break;
            }
            case "pop": fromScale = Math.max(0.2f, 1 - 0.15f * theme.fx.strength()); break;
            case "zoom": fromScale = 1 + 0.25f * theme.fx.strength(); break;
            case "spin":
                fromScale = Math.max(0.15f, 1 - 0.4f * theme.fx.strength());
                fromRot = -90 * theme.fx.strength();
                break;
            default: // fade
        }
        android.view.ViewPropertyAnimator an = p.animate().setDuration(ms);
        if (in) {
            p.setTranslationX(fromX);
            p.setTranslationY(fromY);
            p.setScaleX(fromScale);
            p.setScaleY(fromScale);
            p.setRotation(fromRot);
            p.setAlpha(fromAlpha);
            an.translationX(0).translationY(0).scaleX(1).scaleY(1).rotation(0).alpha(1)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator());
        } else {
            an.translationX(fromX).translationY(fromY).scaleX(fromScale).scaleY(fromScale).rotation(fromRot).alpha(0)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator());
        }
        if (end != null) an.withEndAction(end);
        an.start();
    }

    private void loadSounds() {
        MenuFx fx = theme.fx;
        boolean any = false;
        for (String e : MenuFx.EVENTS) if (!fx.soundFor(e).isEmpty()) any = true;
        if (!any) return;
        pool = new android.media.SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        pool.setOnLoadCompleteListener((sp, id, status) -> {
            if (status != 0) return;
            loadedSounds.add(id);
            // the opening sound may have been asked for before it was ready
            if (pendingSound != null && id == soundIds.getOrDefault(pendingSound, -1)) {
                String ev = pendingSound;
                pendingSound = null;
                play(ev);
            }
        });
        for (String e : MenuFx.EVENTS) {
            String spec = fx.soundFor(e);
            int id = 0;
            if (spec.startsWith("pack:")) {
                int res = MenuFx.packSound(spec.substring(5), e);
                if (res != 0) id = pool.load(this, res, 1);
            } else if (spec.startsWith("file:")) {
                java.io.File f = new java.io.File(MenuMedia.dir(this), spec.substring(5));
                if (f.isFile()) id = pool.load(f.getAbsolutePath(), 1);
            }
            if (id != 0) soundIds.put(e, id);
        }
    }

    private void play(String event) {
        if (pool == null) return;
        Integer id = soundIds.get(event);
        if (id == null) return;
        if (!loadedSounds.contains(id)) {
            pendingSound = event;
            return;
        }
        float v = theme.fx.volume / 100f;
        pool.play(id, v, v, 1, 0, 1f);
    }

    private void startMusic() {
        String file = theme.fx.music;
        if (file.isEmpty()) return;
        java.io.File f = new java.io.File(MenuMedia.dir(this), file);
        if (!f.isFile()) return;
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setLooping(true);
            float v = theme.fx.musicVolume / 100f;
            mp.setVolume(v, v);
            mp.setOnPreparedListener(android.media.MediaPlayer::start);
            mp.prepareAsync();
            music = mp;
        } catch (Exception e) {
            android.util.Log.w("DockPad", "menu music", e);
        }
    }

    private void stopMusic() {
        if (music != null) {
            try {
                music.release();
            } catch (Exception ignored) {
            }
            music = null;
        }
    }

    /** A short buzz of the handheld (kept under 40 ms, so rumble pass-through ignores it). */
    private void haptic(boolean select) {
        String mode = theme.fx.haptics;
        if (mode.equals("off") || (!select && !mode.equals("all"))) return;
        android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        int amp = Math.max(1, Math.min(255, Math.round(theme.fx.hapticStrength * 2.55f)));
        v.vibrate(android.os.VibrationEffect.createOneShot(select ? 28 : 14, amp));
    }

    // ------------------------------------------------------------------ pictures, GIFs, video

    private android.media.MediaPlayer player;

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    /** Background (screen or panel) and decorations, from the theme. */
    private void applyMedia(View panel) {
        releasePlayer();
        MenuMedia.Background bg = theme.background;
        if (bg.isSet() && MenuMedia.exists(this, bg.file)) {
            boolean video = MenuMedia.isVideo(bg.file);
            if (bg.target.equals("panel") && !video) underPanels(root, bg);
            else root.addView(screenBackground(bg, video), 0);
        }
        int behindAt = root.indexOfChild(panel);
        for (MenuMedia.Decoration d : theme.decorations) {
            View v = decoration(d);
            if (v == null) continue;
            if (d.front) root.addView(v);
            else root.addView(v, behindAt++);
        }
    }

    private int screenW() {
        return getWindowManager().getCurrentWindowMetrics().getBounds().width();
    }

    private int screenH() {
        return getWindowManager().getCurrentWindowMetrics().getBounds().height();
    }

    /** Puts the picture under every panel the style drew (keeping their shape and colours on top). */
    private void underPanels(View v, MenuMedia.Background bg) {
        if (v.getBackground() instanceof MenuStyle.PanelDrawable) {
            MenuStyle.PanelDrawable p = (MenuStyle.PanelDrawable) v.getBackground();
            android.graphics.drawable.Drawable img = MenuMedia.load(this, bg.file, Math.max(screenW(), screenH()));
            if (img != null) {
                img = MenuMedia.blurred(this, img, bg.blur);
                MenuMedia.Cover cover = new MenuMedia.Cover(img, bg.scale.equals("fit"), p.radiiPx, p.oval, bg.dim);
                v.setBackground(new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{cover, p}));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) underPanels(g.getChildAt(i), bg);
        }
    }

    /** A picture, GIF or looping video over the whole screen, behind the menu. */
    private View screenBackground(MenuMedia.Background bg, boolean video) {
        FrameLayout holder = new FrameLayout(this);
        noHighlight(holder);
        View content;
        if (video) {
            android.view.TextureView tv = new android.view.TextureView(this);
            java.io.File file = new java.io.File(MenuMedia.dir(this), bg.file);
            tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    try {
                        android.media.MediaPlayer mp = new android.media.MediaPlayer();
                        mp.setDataSource(file.getAbsolutePath());
                        mp.setSurface(new android.view.Surface(st));
                        mp.setLooping(true);
                        mp.setVolume(0, 0);
                        mp.setOnPreparedListener(m -> {
                            // fill or fit the screen without stretching
                            float vw = m.getVideoWidth(), vh = m.getVideoHeight();
                            if (vw > 0 && vh > 0) {
                                float k = bg.scale.equals("fit") ? Math.min(w / vw, h / vh) : Math.max(w / vw, h / vh);
                                android.graphics.Matrix mx = new android.graphics.Matrix();
                                mx.setScale(vw * k / w, vh * k / h, w / 2f, h / 2f);
                                tv.setTransform(mx);
                            }
                            m.start();
                        });
                        mp.prepareAsync();
                        player = mp;
                    } catch (Exception e) {
                        android.util.Log.w("DockPad", "background video", e);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                    releasePlayer();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {
                }
            });
            content = tv;
        } else {
            android.graphics.drawable.Drawable img = MenuMedia.load(this, bg.file, Math.max(screenW(), screenH()));
            View iv = new View(this);
            if (img != null) {
                boolean animated = img instanceof android.graphics.drawable.AnimatedImageDrawable;
                if (!animated) img = MenuMedia.blurred(this, img, bg.blur);
                iv.setBackground(new MenuMedia.Cover(img, bg.scale.equals("fit"), null, false, 0));
            }
            content = iv;
        }
        // GIFs and video blur on the fly
        if (bg.blur > 0 && (video || content.getBackground() instanceof MenuMedia.Cover
                && ((MenuMedia.Cover) content.getBackground()).inner instanceof android.graphics.drawable.AnimatedImageDrawable)) {
            float r = bg.blur * 2f;
            content.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP));
        }
        holder.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (bg.dim > 0) {
            View dim = new View(this);
            dim.setBackgroundColor(Math.round(bg.dim * 2.55f) << 24);
            holder.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        holder.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    /** One decoration: a picture or GIF at a spot on screen, maybe repeated along an edge. */
    private View decoration(MenuMedia.Decoration d) {
        if (!MenuMedia.exists(this, d.file)) return null;
        int W = screenW(), H = screenH();
        int h = Math.max(4, Math.round(H * d.size / 100f));
        android.graphics.drawable.Drawable first = MenuMedia.load(this, d.file, Math.max(h * 2, 64));
        if (first == null) return null;
        float aspect = first.getIntrinsicWidth() / (float) Math.max(1, first.getIntrinsicHeight());
        int w = Math.max(4, Math.round(h * aspect));
        int g = gravity(d.anchor);
        View v;
        if (d.repeat.equals("none")) {
            v = decorationImage(first, d);
            v.setLayoutParams(new FrameLayout.LayoutParams(w, h, g));
        } else {
            boolean across = d.repeat.equals("across");
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(across ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            int n = Math.min(40, (across ? W / Math.max(1, w) : H / Math.max(1, h)) + 2);
            for (int i = 0; i < n; i++) {
                // each copy needs its own picture, so GIFs animate in all of them
                android.graphics.drawable.Drawable img = i == 0 ? first : MenuMedia.load(this, d.file, Math.max(h * 2, 64));
                if (img == null) break;
                strip.addView(decorationImage(img, d), new LinearLayout.LayoutParams(w, h));
            }
            int stripG = across ? (g & Gravity.VERTICAL_GRAVITY_MASK) | Gravity.LEFT : (g & Gravity.HORIZONTAL_GRAVITY_MASK) | Gravity.TOP;
            strip.setLayoutParams(new FrameLayout.LayoutParams(across ? ViewGroup.LayoutParams.WRAP_CONTENT : w,
                    across ? h : ViewGroup.LayoutParams.WRAP_CONTENT, stripG));
            v = strip;
        }
        v.setTranslationX(W * d.x / 100f);
        v.setTranslationY(H * d.y / 100f);
        v.setAlpha(d.opacity / 100f);
        noHighlight(v);
        v.setClickable(false);
        return v;
    }

    private View decorationImage(android.graphics.drawable.Drawable img, MenuMedia.Decoration d) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(img);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        if (d.mirror) iv.setScaleX(-1f);
        noHighlight(iv);
        return iv;
    }

    private static int gravity(String anchor) {
        switch (anchor) {
            case "top-left": return Gravity.TOP | Gravity.LEFT;
            case "top": return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case "top-right": return Gravity.TOP | Gravity.RIGHT;
            case "left": return Gravity.CENTER_VERTICAL | Gravity.LEFT;
            case "center": return Gravity.CENTER;
            case "right": return Gravity.CENTER_VERTICAL | Gravity.RIGHT;
            case "bottom-left": return Gravity.BOTTOM | Gravity.LEFT;
            case "bottom": return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            default: return Gravity.BOTTOM | Gravity.RIGHT;
        }
    }

    /** Applies the look's text, icon and highlight settings to everything drawn. */
    private void applyStyle(View v) {
        MenuStyle st = theme.style;
        int text = st.text(this), accent = st.accent(this);
        float scale = st.textSize / 100f;
        styleTree(v, st, text, accent, scale);
        for (View t : renderer.tiles) {
            if (!st.ring()) t.setForeground(null);
            android.animation.StateListAnimator anim = st.growAnimator();
            if (anim != null) t.setStateListAnimator(anim);
        }
        if (hint != null) hint.setTextColor(arranging ? 0xFFFFD54F : (text & 0x00FFFFFF) | 0xB3000000);
    }

    private void styleTree(View v, MenuStyle st, int text, int accent, float scale) {
        if (v instanceof TextView && !"badge".equals(v.getTag()) && !(v instanceof android.widget.Button)) {
            TextView t = (TextView) v;
            boolean sub = t.getId() == R.id.row_subtitle;
            t.setTextColor(sub ? (text & 0x00FFFFFF) | 0xB3000000 : text);
            boolean bold = t.getTypeface() != null && t.getTypeface().isBold();
            t.setTypeface(st.typeface(this, bold));
            if (scale != 1f) {
                if (t.getAutoSizeTextType() == TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) {
                    t.setAutoSizeTextTypeUniformWithConfiguration(Math.round(8 * scale), Math.round(16 * scale), 1,
                            android.util.TypedValue.COMPLEX_UNIT_SP);
                } else {
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * scale);
                }
            }
        } else if (v instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            if (iv.getImageTintList() != null) iv.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(g.getChildAt(i), st, text, accent, scale);
        }
    }

    private void updateHint() {
        if (hint == null) return;
        boolean free = theme.layout.equals("freeform"), radial = theme.layout.equals("radial");
        if (arranging) {
            hint.setText(free ? "ARRANGING · D-pad/stick: move · L1/R1: smaller/bigger · drag by touch · A or Y: done"
                    : "ARRANGING · D-pad: move this item · A or Y: done");
            hint.setTextColor(0xFFFFD54F);
        } else {
            String h;
            switch (theme.layout) {
                case "radial": h = "Stick: aim · D-pad ◀▶: around · ▲▼ or L1/R1: rings · Y: arrange · B: close"; break;
                case "arc": h = "Stick: aim · ▲▼: along · ◀▶ or L1/R1: rings · Y: arrange · B: close"; break;
                case "carousel": h = "◀▶: browse · ▲▼: sections · Y: arrange · B: close"; break;
                case "dock": h = "◀▶: browse · Y: arrange · B: close"; break;
                case "xmb": h = "◀▶: sections · ▲▼: items · Y: arrange · B: close"; break;
                case "wheel": h = "▲▼: turn · ◀▶: sections · Y: arrange · B: close"; break;
                case "tabs": h = "L1/R1: tabs · D-pad: tiles · Y: arrange · B: close"; break;
                default: h = "Y: arrange items · B: close";
            }
            hint.setText(h);
            hint.setTextColor((theme.style.text(this) & 0x00FFFFFF) | 0xB3000000);
        }
    }

    // ------------------------------------------------------------------ arranging

    private void toggleArranging() {
        if (renderer == null || renderer.tiles.isEmpty()) return;
        arranging = !arranging;
        if (!arranging) saveArrangement();
        updateHint();
        if (arranging && theme.layout.equals("freeform")) enableTouchArranging();
        if (!arranging) rebuild();
    }

    /** Moves the selected item (and its group) by some places in the user's order. */
    private void arrangeBy(int step) {
        if (step == 0 || selected < 0) return;
        List<String> groups = new ArrayList<>();
        List<String> groupSections = new ArrayList<>();
        for (MenuRender.Item it : renderer.selectable) {
            if (!groups.contains(it.group)) {
                groups.add(it.group);
                groupSections.add(it.section);
            }
        }
        String g = renderer.selectable.get(selected).group;
        int from = groups.indexOf(g);
        int to = Math.max(0, Math.min(groups.size() - 1, from + step));
        boolean bySection = theme.bySection();
        if (bySection) {
            // stay inside the section
            String sec = groupSections.get(from);
            while (to != from && !groupSections.get(to).equals(sec)) to += step > 0 ? -1 : 1;
        }
        if (to == from) return;
        groups.remove(from);
        groups.add(to, g);
        // keep the order of hidden or absent items too
        List<String> all = new ArrayList<>(groups);
        for (String old : MenuTheme.order(this)) if (!all.contains(old)) all.add(old);
        MenuTheme.saveOrder(this, all);
        selectedId = renderer.selectable.get(selected).id;
        rebuild();
    }

    private void saveArrangement() {
        if (renderer instanceof MenuRender.FreeRenderer) {
            MenuRender.FreeRenderer f = (MenuRender.FreeRenderer) renderer;
            for (int i = 0; i < f.selectable.size(); i++) {
                MenuRender.Item it = f.selectable.get(i);
                String key = it.group.equals("controllers") ? "controllers" : it.id;
                if (key.equals("controllers") && theme.free.containsKey("controllers")
                        && !it.id.equals(firstControllerId(f))) continue; // the block follows its first item
                theme.free.put(key, f.rects.get(i).clone());
            }
            theme.save(this);
        }
    }

    private static String firstControllerId(MenuRender.FreeRenderer f) {
        for (MenuRender.Item it : f.selectable) if (it.group.equals("controllers")) return it.id;
        return "";
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void enableTouchArranging() {
        if (!(renderer instanceof MenuRender.FreeRenderer)) return;
        MenuRender.FreeRenderer f = (MenuRender.FreeRenderer) renderer;
        for (int i = 0; i < f.tiles.size(); i++) {
            int index = i;
            float[] last = new float[2];
            f.tiles.get(i).setOnTouchListener((v, e) -> {
                if (!arranging) return false;
                View canvas = f.canvas;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        last[0] = e.getRawX();
                        last[1] = e.getRawY();
                        select(index);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = (e.getRawX() - last[0]) / Math.max(1, canvas.getWidth());
                        float dy = (e.getRawY() - last[1]) / Math.max(1, canvas.getHeight());
                        last[0] = e.getRawX();
                        last[1] = e.getRawY();
                        f.nudge(index, dx, dy, 1f);
                        return true;
                    default:
                        return true;
                }
            });
        }
    }

    // ------------------------------------------------------------------ controller keys and stick

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean fresh = down && e.getRepeatCount() == 0;
        if (kc == KeyEvent.KEYCODE_BUTTON_Y) {
            if (fresh) toggleArranging();
            return true;
        }
        if (arranging && (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER
                || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_BUTTON_B || kc == KeyEvent.KEYCODE_BACK)) {
            if (fresh) toggleArranging();
            return true;
        }
        if (kc == KeyEvent.KEYCODE_BUTTON_L1 || kc == KeyEvent.KEYCODE_BUTTON_R1) {
            boolean grow = kc == KeyEvent.KEYCODE_BUTTON_R1;
            if (down && arranging && renderer instanceof MenuRender.FreeRenderer && selected >= 0) {
                ((MenuRender.FreeRenderer) renderer).nudge(selected, 0, 0, grow ? 1.1f : 0.9f);
            } else if (fresh && !arranging && renderer != null) {
                int i = renderer.shoulder(selected, grow ? 1 : -1);
                if (i >= 0) move(i);
            }
            return true;
        }
        int dir = direction(kc);
        if (dir >= 0 && renderer != null) {
            if (down) onDirection(dir, fresh, e.getRepeatCount());
            return true;
        }
        if (arranging) return true; // nothing else while arranging
        // make sure something is highlighted as soon as a controller is used
        if (down && getCurrentFocus() == null && renderer != null && !renderer.tiles.isEmpty()
                && DpDialog.Controller.isFromController(e) && kc != KeyEvent.KEYCODE_BUTTON_B
                && kc != KeyEvent.KEYCODE_BACK) {
            select(Math.max(0, selected));
            return true;
        }
        if (DpDialog.Controller.isFromController(e)
                && DpDialog.Controller.handle(e, getWindow().getDecorView(), this::back)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** One step of the D-pad (keys, or the hat read directly). */
    private void onDirection(int dir, boolean fresh, int repeat) {
        if (renderer == null) return;
        if (arranging) {
            if (renderer instanceof MenuRender.FreeRenderer) {
                if (selected < 0) return;
                float step = repeat > 6 ? 0.025f : 0.012f;
                ((MenuRender.FreeRenderer) renderer).nudge(selected,
                        dir == MenuRender.LEFT ? -step : dir == MenuRender.RIGHT ? step : 0,
                        dir == MenuRender.UP ? -step : dir == MenuRender.DOWN ? step : 0, 1f);
            } else {
                arrangeBy(renderer.arrangeStep(dir));
            }
            return;
        }
        if (getCurrentFocus() == null && selected < 0) {
            select(0);
            return;
        }
        int next = renderer.move(selected, dir, fresh);
        if (next >= 0) move(next);
    }

    private float hatX, hatY;

    /** The handheld's D-pad arrives as a hat (motion), not keys: read it ourselves where we take the stick. */
    private void readHat(MotionEvent e) {
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int dir = -1;
        if (hx < -0.5f && hatX >= -0.5f) dir = MenuRender.LEFT;
        else if (hx > 0.5f && hatX <= 0.5f) dir = MenuRender.RIGHT;
        else if (hy < -0.5f && hatY >= -0.5f) dir = MenuRender.UP;
        else if (hy > 0.5f && hatY <= 0.5f) dir = MenuRender.DOWN;
        hatX = hx;
        hatY = hy;
        if (dir >= 0) onDirection(dir, true, 0);
    }

    private static int direction(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: return MenuRender.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return MenuRender.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return MenuRender.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return MenuRender.RIGHT;
            default: return -1;
        }
    }

    private long stickHeldSince;

    /** Radial: aim with the stick. Freeform while arranging: move with the stick. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        boolean joystick = (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && e.getAction() == MotionEvent.ACTION_MOVE;
        if (joystick && renderer != null) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float mag = (float) Math.hypot(x, y);
            if (arranging && renderer instanceof MenuRender.FreeRenderer) {
                readHat(e);
                if (mag > 0.25f && selected >= 0) {
                    ((MenuRender.FreeRenderer) renderer).nudge(selected, x * 0.02f, y * 0.02f, 1f);
                }
                return true;
            }
            if (renderer instanceof MenuRender.RadialRenderer || renderer instanceof MenuStyles.ArcRenderer) {
                readHat(e);
                if (!arranging && mag > 0.55f) {
                    int i = renderer.aim(x, y, selected);
                    if (i >= 0 && i != selected) move(i);
                }
                return true; // the stick doesn't also move the highlight like a D-pad
            }
        }
        return super.dispatchGenericMotionEvent(e);
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
        refreshing = true;
        refreshStarted = android.os.SystemClock.uptimeMillis();
        ShizukuLink.connect(() -> runInBackground(b -> {
            bridge = b;
            JSONObject o;
            try {
                o = new JSONObject(b.statusJson());
            } finally {
                refreshing = false;
            }
            ui.post(() -> {
                if (isFinishing()) return;
                boolean r = o.optBoolean("running"), c = o.optBoolean("pcMode");
                boolean ab = o.optBoolean("swapAB"), xy = o.optBoolean("swapXY");
                boolean tb = o.optBoolean("turbo", true), tc = o.optBoolean("turboConfigured");
                boolean changed = r != running || c != cursor || ab != swapAB || xy != swapXY
                        || tb != turbo || tc != turboConfigured;
                turbo = tb;
                turboConfigured = tc;
                running = r;
                cursor = c;
                swapAB = ab;
                swapXY = xy;
                statusText = (running ? "On" : "Off") + (cursor ? " · Cursor mode on" : "");
                if (status != null) status.setText(headerText());
                if (showControllers(o.optJSONArray("controllers")) || changed) rebuild();
            });
        }));
    }


    private void confirmDisconnect(String name) {
        new DpDialog(this).setTitle("Disconnect " + new Prefs(this).controllerName(name) + "?")
                .setMessage("It disconnects from Bluetooth (most controllers then switch off). Press its "
                        + "home button to connect it again.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    android.content.Context app = getApplicationContext();
                    runInBackground(b -> {
                        String err = b.disconnectController(name);
                        ui.post(() -> {
                            if (err == null || err.isEmpty()) {
                                android.widget.Toast.makeText(app, name + " disconnected",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } else if (!isFinishing()) {
                                new DpDialog(this).setTitle("Didn't disconnect").setMessage(err)
                                        .setPositiveButton("OK", null).show();
                            }
                            refreshState();
                        });
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** External controllers from the last status, for the rapid-fire editor. */
    private final java.util.List<String> externals = new java.util.ArrayList<>();

    private void editTurbo() {
        if (externals.isEmpty()) {
            android.widget.Toast.makeText(this, running ? "Connect a controller first" : "Turn DockPad on first",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (externals.size() == 1) {
            editTurboFor(externals.get(0));
            return;
        }
        String[] names = externals.toArray(new String[0]);
        String[] shown = new String[names.length];
        Prefs prefs = new Prefs(this);
        for (int i = 0; i < names.length; i++) shown[i] = prefs.controllerName(names[i]);
        new DpDialog(this).setTitle("Which controller?")
                .setItems(shown, (d, w) -> editTurboFor(names[w]))
                .setNegativeButton("Cancel", null).show();
    }

    private static final String[] SPEED_LABELS = {"Slow (6 a second)", "Normal (10 a second)",
            "Fast (15 a second)", "Very fast (20 a second)"};
    private static final int[] SPEEDS = {6, 10, 15, 20};

    private void editTurboFor(String name) {
        Prefs prefs = new Prefs(this);
        ControllerLayout l = prefs.layout(name);
        String[] labels = ControllerLayout.TURBO_LABELS;
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) checked[i] = l.turbo.contains(ControllerLayout.TURBO_CODES[i]);
        new DpDialog(this).setTitle("Rapid fire: " + prefs.controllerName(name))
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("Next", (d, w) -> {
                    l.turbo.clear();
                    for (int i = 0; i < labels.length; i++) if (checked[i]) l.turbo.add(ControllerLayout.TURBO_CODES[i]);
                    if (l.turbo.isEmpty()) saveTurbo(prefs, l);
                    else pickSpeed(prefs, l);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickSpeed(Prefs prefs, ControllerLayout l) {
        int sel = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - l.turboRate) < Math.abs(SPEEDS[sel] - l.turboRate)) sel = i;
        }
        new DpDialog(this).setTitle("How fast?")
                .setSingleChoiceItems(SPEED_LABELS, sel, (d, w) -> {
                    l.turboRate = SPEEDS[w];
                    d.dismiss();
                    saveTurbo(prefs, l);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveTurbo(Prefs prefs, ControllerLayout l) {
        prefs.saveLayout(l);
        String spec = prefs.layoutsSpec();
        android.widget.Toast.makeText(this, l.turbo.isEmpty() ? "Rapid fire cleared"
                : "Rapid fire saved", android.widget.Toast.LENGTH_SHORT).show();
        withBridge(b -> b.setLayouts(spec));
    }


    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
