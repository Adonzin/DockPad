package com.adonzin.dockpad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the quick menu looks and is laid out. Stored as JSON so it can grow into shareable theme files
 * (looks, media, sounds come in later formats); unknown fields are kept when saving.
 */
final class MenuTheme {
    static final int FORMAT = 1;

    static final String[] LAYOUTS = {"list", "compact", "grid", "carousel", "xmb", "dock", "wheel", "tabs",
            "rail", "radial", "arc", "freeform"};
    static final String[] LAYOUT_LABELS = {"List", "Compact list", "Grid of tiles", "Carousel", "XMB (cross bar)",
            "Dock", "Wheel (vertical drum)", "Tabs", "Rail", "Radial (ring)", "Edge arc", "Freeform"};
    static final String[] POSITIONS = {"left", "right", "center", "full"};
    static final String[] POSITION_LABELS = {"Left side", "Right side", "Centre", "Full screen"};
    static final String[] BANDS = {"top", "middle", "bottom"};
    static final String[] BAND_LABELS = {"Top", "Middle", "Bottom"};

    String name = "Default";
    String layout = "list";
    String position = "left";
    /** Panel width in dp (radial: the ring's diameter). */
    int width = 340;
    boolean sections = true;
    /** Carousel: where the strip sits. */
    String band = "bottom";
    /** Radial and carousel: size as a percentage of the screen (40-100). */
    int size = 80;
    int columns = 3;
    /** Freeform: item id -> {x, y, w, h} as fractions of the panel. */
    final Map<String, float[]> free = new HashMap<>();
    /** Colours, shapes, text, highlight, header. */
    MenuStyle style = new MenuStyle();
    /** Background picture/GIF/video. */
    MenuMedia.Background background = new MenuMedia.Background();
    /** Animation, sound and feedback. */
    MenuFx fx = new MenuFx();
    /** Images placed around the screen. */
    List<MenuMedia.Decoration> decorations = new ArrayList<>();

    private JSONObject raw = new JSONObject(); // everything, including fields this version doesn't know

    static MenuTheme load(Context c) {
        SharedPreferences p = c.getSharedPreferences("dockpad", Context.MODE_PRIVATE);
        return parse(p.getString("menuTheme", ""));
    }

    static MenuTheme parse(String json) {
        MenuTheme t = new MenuTheme();
        if (json == null || json.isEmpty()) return t;
        try {
            JSONObject o = new JSONObject(json);
            t.raw = o;
            t.name = o.optString("name", t.name);
            t.style = MenuStyle.parse(o.optJSONObject("style"));
            t.fx = MenuFx.parse(o.optJSONObject("fx"));
            JSONObject media = o.optJSONObject("media");
            if (media != null) {
                t.background = MenuMedia.parseBackground(media.optJSONObject("background"));
                t.decorations = MenuMedia.parseDecorations(media.optJSONArray("decorations"));
            }
            JSONObject l = o.optJSONObject("layout");
            if (l != null) {
                String type = l.optString("type", t.layout);
                if (Arrays.asList(LAYOUTS).contains(type)) t.layout = type;
                String pos = l.optString("position", t.position);
                if (Arrays.asList(POSITIONS).contains(pos)) t.position = pos;
                t.width = Math.max(200, Math.min(1200, l.optInt("width", t.width)));
                t.sections = l.optBoolean("sections", t.sections);
                String band = l.optString("band", t.band);
                if (Arrays.asList(BANDS).contains(band)) t.band = band;
                t.size = Math.max(40, Math.min(100, l.optInt("size", t.size)));
                t.columns = Math.max(2, Math.min(8, l.optInt("columns", t.columns)));
                JSONObject f = l.optJSONObject("free");
                if (f != null) for (java.util.Iterator<String> it = f.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONArray a = f.optJSONArray(id);
                    if (a == null || a.length() != 4) continue;
                    float[] r = new float[4];
                    for (int i = 0; i < 4; i++) r[i] = clamp((float) a.optDouble(i, 0));
                    r[2] = Math.max(0.04f, r[2]);
                    r[3] = Math.max(0.03f, r[3]);
                    t.free.put(id, r);
                }
            }
        } catch (Exception ignored) {
            // a broken theme falls back to the default
        }
        return t;
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject(raw.toString());
            o.put("format", FORMAT);
            o.put("name", name);
            JSONObject l = o.optJSONObject("layout");
            if (l == null) l = new JSONObject();
            l.put("type", layout);
            l.put("position", position);
            l.put("width", width);
            l.put("sections", sections);
            l.put("band", band);
            l.put("size", size);
            l.put("columns", columns);
            JSONObject f = new JSONObject();
            for (Map.Entry<String, float[]> e : free.entrySet()) {
                JSONArray a = new JSONArray();
                for (float v : e.getValue()) a.put(Math.round(v * 1000) / 1000.0);
                f.put(e.getKey(), a);
            }
            l.put("free", f);
            o.put("layout", l);
            o.put("style", style.toJson());
            JSONObject media = new JSONObject();
            media.put("background", MenuMedia.encode(background));
            media.put("decorations", MenuMedia.encode(decorations));
            o.put("media", media);
            o.put("fx", fx.toJson());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    void save(Context c) {
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit().putString("menuTheme", toJson()).apply();
    }

    /** Whether items are grouped (and kept) by section in this layout. */
    boolean bySection() {
        switch (layout) {
            case "xmb": case "tabs": return true;
            case "radial": case "arc": case "freeform": case "dock": case "rail": return false;
            default: return sections;
        }
    }

    /** Where the panel goes: "side" panels, a "band" strip, or the whole "screen". */
    String frame() {
        switch (layout) {
            case "carousel": case "dock": return "band";
            case "xmb": case "arc": return "screen";
            case "radial": return "ring";
            case "rail": return "rail";
            default: return "side";
        }
    }

    float sizeFraction() {
        return size / 100f;
    }

    static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    // ------------------------------------------------------------------ the user's own item order

    static List<String> order(Context c) {
        String s = c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).getString("menuOrder", "");
        List<String> out = new ArrayList<>();
        for (String id : s.split("\n")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    static void saveOrder(Context c, List<String> ids) {
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit()
                .putString("menuOrder", String.join("\n", ids)).apply();
    }

    static void resetArrangement(Context c) {
        MenuTheme t = load(c);
        t.free.clear();
        t.save(c);
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit().remove("menuOrder").apply();
    }
}
