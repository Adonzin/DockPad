package com.adonzin.dockpad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The user's own organisation of the quick menu: section names and order, extra sections, which
 * section each item is in, and item names. Kept apart from the look (themes), so it survives
 * switching themes.
 */
final class MenuCustom {
    static final String[] DEFAULT_SECTIONS = {"Navigation", "Tools", "My commands", "Controllers", "DockPad"};

    /** Section key -> shown name. */
    final Map<String, String> sectionNames = new HashMap<>();
    /** Item group -> section key. */
    final Map<String, String> itemSection = new HashMap<>();
    /** Item id -> shown name. */
    final Map<String, String> itemTitle = new HashMap<>();
    /** Item id -> icon name (IconLibrary). */
    final Map<String, String> itemIcon = new HashMap<>();
    /** Section key -> icon name. */
    final Map<String, String> sectionIcon = new HashMap<>();
    /** Section keys in the user's order. */
    final List<String> sectionOrder = new ArrayList<>();
    /** Sections the user made. */
    final List<String> extraSections = new ArrayList<>();

    static MenuCustom load(Context c) {
        MenuCustom m = new MenuCustom();
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("dockpad", Context.MODE_PRIVATE)
                    .getString("menuCustom", "{}"));
            readMap(o.optJSONObject("sectionNames"), m.sectionNames);
            readMap(o.optJSONObject("itemSection"), m.itemSection);
            readMap(o.optJSONObject("itemTitle"), m.itemTitle);
            readMap(o.optJSONObject("itemIcon"), m.itemIcon);
            readMap(o.optJSONObject("sectionIcon"), m.sectionIcon);
            readList(o.optJSONArray("sectionOrder"), m.sectionOrder);
            readList(o.optJSONArray("extraSections"), m.extraSections);
        } catch (Exception ignored) {
        }
        return m;
    }

    void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("sectionNames", new JSONObject(sectionNames));
            o.put("itemSection", new JSONObject(itemSection));
            o.put("itemTitle", new JSONObject(itemTitle));
            o.put("itemIcon", new JSONObject(itemIcon));
            o.put("sectionIcon", new JSONObject(sectionIcon));
            o.put("sectionOrder", new JSONArray(sectionOrder));
            o.put("extraSections", new JSONArray(extraSections));
            c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit().putString("menuCustom", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void reset(Context c) {
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit().remove("menuCustom").apply();
    }

    private static void readMap(JSONObject o, Map<String, String> into) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = o.optString(k, "");
            if (!v.isEmpty()) into.put(k, v);
        }
    }

    private static void readList(JSONArray a, List<String> into) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "");
            if (!v.isEmpty() && !into.contains(v)) into.add(v);
        }
    }

    String sectionName(String key) {
        String n = sectionNames.get(key);
        return n == null || n.isEmpty() ? key : n;
    }

    /** Every section key, in the user's order (defaults and extras not yet ordered come after). */
    List<String> sections() {
        List<String> all = new ArrayList<>();
        for (String k : sectionOrder) if (isSection(k) && !all.contains(k)) all.add(k);
        for (String k : DEFAULT_SECTIONS) if (!all.contains(k)) all.add(k);
        for (String k : extraSections) if (!all.contains(k)) all.add(k);
        return all;
    }

    boolean isSection(String key) {
        for (String d : DEFAULT_SECTIONS) if (d.equals(key)) return true;
        return extraSections.contains(key);
    }

    boolean isExtra(String key) {
        return extraSections.contains(key);
    }

    /** The section key an item is in. */
    String sectionOf(String group, String defaultSection) {
        String k = itemSection.get(group);
        return k != null && isSection(k) ? k : defaultSection;
    }

    /** The name to show for an item; a renamed on/off item keeps showing its state. */
    String titleOf(String id, String original) {
        String t = itemTitle.get(id);
        if (t == null || t.isEmpty()) return original;
        if (original.endsWith(": on")) return t + ": on";
        if (original.endsWith(": off")) return t + ": off";
        return t;
    }

    /** The user's own image for an item ("file:<name>"), or null. */
    String iconFileOf(String id) {
        String v = itemIcon.get(id);
        return v != null && v.startsWith("file:") ? v.substring(5) : null;
    }

    int iconOf(String id, int original) {
        int r = IconLibrary.resolve(itemIcon.get(id));
        return r != 0 ? r : original;
    }

    void moveSection(String key, int delta) {
        List<String> all = sections();
        int i = all.indexOf(key), j = i + delta;
        if (i < 0 || j < 0 || j >= all.size()) return;
        all.remove(i);
        all.add(j, key);
        sectionOrder.clear();
        sectionOrder.addAll(all);
    }

    void deleteSection(String key) {
        if (!isExtra(key)) return;
        extraSections.remove(key);
        sectionOrder.remove(key);
        sectionNames.remove(key);
        sectionIcon.remove(key);
        itemSection.values().removeIf(v -> v.equals(key)); // its items go back to their own sections
    }
}
