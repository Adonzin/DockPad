package com.adonzin.dockpad;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Animation, sound and handheld feedback for the quick menu (part of a theme). */
final class MenuFx {
    static final String[] OPENS = {"none", "fade", "slide", "pop", "zoom", "spin"};
    static final String[] OPEN_LABELS = {"None", "Fade", "Slide in", "Pop", "Zoom", "Spin (best for rings)"};
    static final String[] SPEEDS = {"fast", "normal", "slow"};
    static final String[] SPEED_LABELS = {"Fast", "Normal", "Slow"};
    static final String[] ITEMS = {"none", "stagger"};
    static final String[] ITEM_LABELS = {"All at once", "One after another"};
    static final String[] HIGHLIGHTS = {"none", "bump", "hop", "wiggle", "shake", "jelly", "flip", "spin", "flash",
            "pulse", "float", "sway"};
    static final String[] HIGHLIGHT_LABELS = {"None", "Bump", "Hop", "Wiggle", "Shake", "Jelly", "Flip", "Spin", "Flash",
            "Pulse (keeps going)", "Float (keeps going)", "Sway (keeps going)"};
    static final String[] SECTION_MOVES = {"none", "fade", "slide", "zoom"};
    static final String[] SECTION_MOVE_LABELS = {"Instant", "Fade", "Slide", "Zoom"};
    static final String[] HAPTICS = {"off", "select", "all"};
    static final String[] HAPTIC_LABELS = {"Off", "When selecting", "When moving and selecting"};
    static final String[] EVENTS = {"open", "close", "move", "select", "back"};
    static final String[] EVENT_LABELS = {"Opening", "Closing", "Moving", "Selecting", "Back"};
    static final String[] PACKS = {"off", "soft", "retro", "glass", "click"};
    static final String[] PACK_LABELS = {"Off", "Soft", "Retro", "Glass", "Clicky"};

    String open = "none", close = "none", speed = "normal", items = "none", highlight = "none";
    String pack = "off";
    /** Changing sections (opening one, going back, tabs, XMB columns, radial rings): how, and how smoothly (0-100). */
    String sectionMove = "slide";
    int smoothness = 40;
    /** How strong the animations are, percent (10-200). */
    int intensity = 100;
    /** event -> "pack:<name>" (that pack's sound), "file:<name>", or "" for silence. Empty map: the pack. */
    final Map<String, String> sounds = new HashMap<>();
    int volume = 70;
    String music = "";
    int musicVolume = 40;
    String haptics = "off";
    int hapticStrength = 50;

    float strength() {
        return intensity / 100f;
    }

    /** How long a section change takes (0 = instant). */
    long sectionMs() {
        return sectionMove.equals("none") || smoothness <= 0 ? 0 : Math.round(70 + smoothness * 4.3f);
    }

    /** Smoother settings ease out more gently. */
    android.view.animation.Interpolator sectionEase() {
        return new android.view.animation.DecelerateInterpolator(1f + smoothness / 50f);
    }

    float speedFactor() {
        return speed.equals("fast") ? 0.6f : speed.equals("slow") ? 1.7f : 1f;
    }

    /** What plays for an event: a pack's sound, a file, or nothing. */
    String soundFor(String event) {
        String s = sounds.get(event);
        if (s != null) return s;
        return pack.equals("off") ? "" : "pack:" + pack;
    }

    static MenuFx parse(JSONObject o) {
        MenuFx f = new MenuFx();
        if (o == null) return f;
        f.open = oneOf(o.optString("open", f.open), OPENS, "none");
        f.close = oneOf(o.optString("close", f.close), OPENS, "none");
        f.speed = oneOf(o.optString("speed", f.speed), SPEEDS, "normal");
        f.items = oneOf(o.optString("items", f.items), ITEMS, "none");
        f.highlight = oneOf(o.optString("highlight", f.highlight), HIGHLIGHTS, "none");
        f.pack = oneOf(o.optString("pack", f.pack), PACKS, "off");
        f.intensity = clamp(o.optInt("intensity", f.intensity), 10, 200);
        f.sectionMove = oneOf(o.optString("sectionMove", f.sectionMove), SECTION_MOVES, "slide");
        f.smoothness = clamp(o.optInt("smoothness", f.smoothness), 0, 100);
        JSONObject s = o.optJSONObject("sounds");
        if (s != null) for (Iterator<String> it = s.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = s.optString(k, "");
            boolean ok = v.isEmpty() || (v.startsWith("pack:") && contains(PACKS, v.substring(5)))
                    || (v.startsWith("file:") && !MenuMedia.safeName(v.substring(5)).isEmpty());
            if (contains(EVENTS, k) && ok) f.sounds.put(k, v);
        }
        f.volume = clamp(o.optInt("volume", f.volume), 0, 100);
        f.music = MenuMedia.safeName(o.optString("music", ""));
        f.musicVolume = clamp(o.optInt("musicVolume", f.musicVolume), 0, 100);
        f.haptics = oneOf(o.optString("haptics", f.haptics), HAPTICS, "off");
        f.hapticStrength = clamp(o.optInt("hapticStrength", f.hapticStrength), 5, 100);
        return f;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("open", open);
            o.put("close", close);
            o.put("speed", speed);
            o.put("items", items);
            o.put("highlight", highlight);
            o.put("pack", pack);
            o.put("intensity", intensity);
            o.put("sectionMove", sectionMove);
            o.put("smoothness", smoothness);
            o.put("sounds", new JSONObject(sounds));
            o.put("volume", volume);
            o.put("music", music);
            o.put("musicVolume", musicVolume);
            o.put("haptics", haptics);
            o.put("hapticStrength", hapticStrength);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static boolean contains(String[] a, String v) {
        for (String x : a) if (x.equals(v)) return true;
        return false;
    }

    private static String oneOf(String v, String[] options, String fallback) {
        return contains(options, v) ? v : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The built-in sound for a pack and event. */
    static int packSound(String pack, String event) {
        switch (pack + "_" + event) {
            case "soft_open": return R.raw.snd_soft_open;
            case "soft_close": return R.raw.snd_soft_close;
            case "soft_move": return R.raw.snd_soft_move;
            case "soft_select": return R.raw.snd_soft_select;
            case "soft_back": return R.raw.snd_soft_back;
            case "retro_open": return R.raw.snd_retro_open;
            case "retro_close": return R.raw.snd_retro_close;
            case "retro_move": return R.raw.snd_retro_move;
            case "retro_select": return R.raw.snd_retro_select;
            case "retro_back": return R.raw.snd_retro_back;
            case "glass_open": return R.raw.snd_glass_open;
            case "glass_close": return R.raw.snd_glass_close;
            case "glass_move": return R.raw.snd_glass_move;
            case "glass_select": return R.raw.snd_glass_select;
            case "glass_back": return R.raw.snd_glass_back;
            case "click_open": return R.raw.snd_click_open;
            case "click_close": return R.raw.snd_click_close;
            case "click_move": return R.raw.snd_click_move;
            case "click_select": return R.raw.snd_click_select;
            case "click_back": return R.raw.snd_click_back;
            default: return 0;
        }
    }
}
