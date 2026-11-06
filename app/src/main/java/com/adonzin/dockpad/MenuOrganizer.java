package com.adonzin.dockpad;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.adonzin.dockpad.core.Shortcut;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quick menu organiser, shown right in the Quick menu tab: each section with its items, and on
 * every row the controls for it (show, order, move, rename).
 */
final class MenuOrganizer {
    private final Activity a;
    private final LinearLayout list;
    private final MediaDialog.Importer importer;
    private String focusTag;

    MenuOrganizer(Activity a, LinearLayout list, MediaDialog.Importer importer) {
        this.a = a;
        this.list = list;
        this.importer = importer;
    }

    /** id, original name. */
    private List<String[]> catalog() {
        List<String[]> out = new ArrayList<>();
        for (String[] it : QuickMenuActivity.ITEMS) {
            if (it[0].equals("builtinctrl")) continue; // an option, not an item
            out.add(new String[]{it[0], it[1]});
        }
        for (Shortcut c : new Prefs(a).menuCommands()) out.add(new String[]{QuickMenuActivity.commandId(c), c.device});
        return out;
    }

    /** Each section's items in the order the menu shows them. */
    private Map<String, List<String[]>> bySection(MenuCustom mc, List<String[]> cat) {
        List<String> order = MenuTheme.order(a);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        Map<String, List<String[]>> out = new HashMap<>();
        for (String k : mc.sections()) out.put(k, new ArrayList<>());
        for (String[] it : cat) {
            String k = mc.sectionOf(it[0], QuickMenuActivity.defaultSection(it[0]));
            if (!out.containsKey(k)) out.put(k, new ArrayList<>());
            out.get(k).add(it);
        }
        for (List<String[]> l : out.values()) {
            List<String[]> original = new ArrayList<>(l);
            l.sort((x, y) -> {
                int rx = rank.getOrDefault(x[0], Integer.MAX_VALUE), ry = rank.getOrDefault(y[0], Integer.MAX_VALUE);
                if (rx != ry) return Integer.compare(rx, ry);
                return Integer.compare(original.indexOf(x), original.indexOf(y));
            });
        }
        return out;
    }

    void render() {
        View f = a.getCurrentFocus();
        if (f != null && f.getTag() instanceof String && list.findViewWithTag(f.getTag()) == f) focusTag = (String) f.getTag();
        list.removeAllViews();
        MenuCustom mc = MenuCustom.load(a);
        List<String[]> cat = catalog();
        Map<String, List<String[]>> secs = bySection(mc, cat);
        Set<String> hidden = new Prefs(a).menuHidden();
        List<String> keys = mc.sections();
        for (int s = 0; s < keys.size(); s++) {
            String key = keys.get(s);
            sectionRow(mc, key, s == 0, s == keys.size() - 1);
            List<String[]> items = secs.get(key);
            if (items.isEmpty()) {
                TextView t = new TextView(a);
                t.setText("Empty. Use “Move…” on any item to put it here.");
                t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                t.setPadding(dp(12), dp(4), 0, dp(8));
                list.addView(t);
            }
            for (int i = 0; i < items.size(); i++) {
                itemRow(mc, key, items.get(i), hidden, i == 0, i == items.size() - 1);
            }
        }
        // the built-in controls: an option of the controllers list
        MaterialCheckBox builtIn = new MaterialCheckBox(a);
        builtIn.setText("Also list the built-in controls under the controllers");
        builtIn.setChecked(!hidden.contains("builtinctrl"));
        builtIn.setForeground(a.getDrawable(R.drawable.focus_ring));
        builtIn.setTag("opt:builtin");
        builtIn.setOnCheckedChangeListener((b, on) -> setHidden("builtinctrl", !on));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(10);
        list.addView(builtIn, bl);
        if (focusTag != null) {
            View again = list.findViewWithTag(focusTag);
            if (again != null) again.post(again::requestFocus);
        }
    }

    // ------------------------------------------------------------------ rows

    private void sectionRow(MenuCustom mc, String key, boolean first, boolean last) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(2));
        int sIcon = IconLibrary.resolve(mc.sectionIcon.get(key));
        row.addView(iconButton(sIcon != 0 ? sIcon : R.drawable.ic_lib_apps, sIcon == 0, "sec:" + key + ":icon",
                () -> pickIcon("Icon for “" + mc.sectionName(key) + "” (XMB and tabs show it)", mc.sectionIcon.get(key), name -> {
                    MenuCustom m = MenuCustom.load(a);
                    if (name == null) m.sectionIcon.remove(key);
                    else m.sectionIcon.put(key, name);
                    m.save(a);
                    render();
                })));
        TextView name = new TextView(a);
        String shown = mc.sectionName(key);
        name.setText(shown.toUpperCase() + (shown.equals(key) || mc.isExtra(key) ? "" : "   (was " + key + ")"));
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(button("Rename", "sec:" + key + ":rename", () -> ask("Rename section", mc.sectionName(key), n -> {
            MenuCustom m = MenuCustom.load(a);
            if (n.equals(key)) m.sectionNames.remove(key);
            else m.sectionNames.put(key, n);
            m.save(a);
            render();
        })));
        if (!first) row.addView(button("▲", "sec:" + key + ":up", () -> {
            MenuCustom m = MenuCustom.load(a);
            m.moveSection(key, -1);
            m.save(a);
            render();
        }));
        if (!last) row.addView(button("▼", "sec:" + key + ":down", () -> {
            MenuCustom m = MenuCustom.load(a);
            m.moveSection(key, 1);
            m.save(a);
            render();
        }));
        if (mc.isExtra(key)) row.addView(button("Delete", "sec:" + key + ":delete", () -> {
            MenuCustom m = MenuCustom.load(a);
            m.deleteSection(key);
            m.save(a);
            render();
        }));
        list.addView(row);
    }

    private void itemRow(MenuCustom mc, String key, String[] it, Set<String> hidden, boolean first, boolean last) {
        String id = it[0];
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, 0, 0);
        MaterialCheckBox show = new MaterialCheckBox(a);
        show.setChecked(!hidden.contains(id));
        show.setForeground(a.getDrawable(R.drawable.focus_ring));
        show.setTag("item:" + id + ":show");
        show.setOnCheckedChangeListener((b, on) -> {
            setHidden(id, !on);
            title(row).setAlpha(on ? 1f : 0.5f);
        });
        row.addView(show);
        if (!id.equals("controllers")) {
            View ib = iconButton(mc.iconOf(id, QuickMenuActivity.defaultIcon(id)), false, "item:" + id + ":icon",
                    () -> pickIcon("Icon for “" + mc.titleOf(id, it[1]) + "”", mc.itemIcon.get(id), name -> {
                        MenuCustom m = MenuCustom.load(a);
                        if (name == null) m.itemIcon.remove(id);
                        else m.itemIcon.put(id, name);
                        m.save(a);
                        render();
                    }));
            String file = mc.iconFileOf(id);
            if (file != null && ib instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable d = MenuMedia.load(a, file, dp(80));
                if (d != null) {
                    ((android.widget.ImageView) ib).setImageTintList(null);
                    ((android.widget.ImageView) ib).setImageDrawable(d);
                }
            }
            row.addView(ib);
        }
        TextView title = new TextView(a);
        String t = mc.titleOf(id, it[1]);
        title.setText(t + (t.equals(it[1]) ? "" : "   (" + it[1] + ")"));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setAlpha(hidden.contains(id) ? 0.5f : 1f);
        title.setTag("title");
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!first) row.addView(button("▲", "item:" + id + ":up", () -> moveItem(key, id, -1)));
        if (!last) row.addView(button("▼", "item:" + id + ":down", () -> moveItem(key, id, 1)));
        row.addView(button("Move…", "item:" + id + ":move", () -> chooseSection(id, key, it[1])));
        if (!id.equals("controllers")) {
            row.addView(button("Rename", "item:" + id + ":rename", () ->
                    ask("Rename “" + it[1] + "”", mc.titleOf(id, it[1]).replaceAll(": (on|off)$", ""), n -> {
                        MenuCustom m = MenuCustom.load(a);
                        if (n.equals(it[1])) m.itemTitle.remove(id);
                        else m.itemTitle.put(id, n);
                        m.save(a);
                        render();
                    })));
        }
        list.addView(row);
    }

    private static TextView title(LinearLayout row) {
        return (TextView) row.findViewWithTag("title");
    }

    private MaterialButton button(String text, String tag, Runnable action) {
        MaterialButton b = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTag(tag);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            focusTag = tag;
            action.run();
        });
        return b;
    }

    /** A small square button showing an icon; selecting it opens the icon picker. */
    private View iconButton(int icon, boolean faded, String tag, Runnable action) {
        android.widget.ImageView v = new android.widget.ImageView(a);
        v.setImageResource(icon);
        v.setImageTintList(android.content.res.ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
        v.setAlpha(faded ? 0.45f : 1f);
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        v.setBackgroundResource(R.drawable.focus_ring);
        v.setFocusable(true);
        v.setClickable(true);
        v.setTag(tag);
        v.setContentDescription("Change icon");
        v.setOnClickListener(x -> {
            focusTag = tag;
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMarginEnd(dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    interface Picked {
        void icon(String name); // null: the original
    }

    private void pickIcon(String title, String current, Picked done) {
        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(8);
        grid.setPadding(dp(16), dp(8), dp(16), 0);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        View first = null;
        // the original first
        TextView original = new TextView(a);
        original.setText("Original");
        original.setGravity(Gravity.CENTER);
        original.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        cell(grid, original, current == null, () -> {
            dialog[0].dismiss();
            done.icon(null);
        });
        first = original;
        for (int i = 0; i < IconLibrary.NAMES.length; i++) {
            String name = IconLibrary.NAMES[i][0];
            android.widget.ImageView v = new android.widget.ImageView(a);
            v.setImageResource(IconLibrary.IDS[i]);
            v.setImageTintList(android.content.res.ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
            v.setPadding(dp(10), dp(10), dp(10), dp(10));
            v.setContentDescription(IconLibrary.NAMES[i][1]);
            View c = cell(grid, v, name.equals(current), () -> {
                dialog[0].dismiss();
                done.icon(name);
            });
            if (name.equals(current)) first = c;
        }
        // the user's own images
        for (String file : MenuMedia.list(a, false)) {
            android.widget.ImageView v = new android.widget.ImageView(a);
            v.setImageDrawable(MenuMedia.load(a, file, dp(96)));
            v.setPadding(dp(6), dp(6), dp(6), dp(6));
            v.setContentDescription(file);
            View c = cell(grid, v, ("file:" + file).equals(current), () -> {
                dialog[0].dismiss();
                done.icon("file:" + file);
            });
            if (("file:" + file).equals(current)) first = c;
        }
        TextView mine = new TextView(a);
        mine.setText("Your image…");
        mine.setGravity(Gravity.CENTER);
        mine.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        cell(grid, mine, false, () -> {
            dialog[0].dismiss();
            importer.pick(false, name -> done.icon("file:" + name));
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(grid);
        dialog[0] = new DpDialog(a).setTitle(title).setView(sv).setNegativeButton("Cancel", null).show();
        View start = first;
        grid.post(start::requestFocus);
    }

    private View cell(android.widget.GridLayout grid, View v, boolean current, Runnable pick) {
        v.setFocusable(true);
        v.setClickable(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(current ? color(com.google.android.material.R.attr.colorPrimaryContainer) : 0x14FFFFFF);
        v.setBackground(bg);
        v.setForeground(a.getDrawable(R.drawable.focus_ring));
        v.setOnClickListener(x -> pick.run());
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
        lp.width = dp(48);
        lp.height = dp(48);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(v, lp);
        return v;
    }

    private int color(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        a.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ------------------------------------------------------------------ changes

    private void setHidden(String id, boolean hide) {
        Prefs p = new Prefs(a);
        Set<String> h = p.menuHidden();
        if (hide) h.add(id);
        else h.remove(id);
        h.remove("");
        p.menuHidden(h);
    }

    /** Moves an item up/down within its section, by rewriting the menu's order. */
    private void moveItem(String key, String id, int delta) {
        MenuCustom mc = MenuCustom.load(a);
        Map<String, List<String[]>> secs = bySection(mc, catalog());
        List<String[]> mine = secs.get(key);
        int i = -1;
        for (int k = 0; k < mine.size(); k++) if (mine.get(k)[0].equals(id)) i = k;
        int j = i + delta;
        if (i < 0 || j < 0 || j >= mine.size()) return;
        String[] x = mine.remove(i);
        mine.add(j, x);
        List<String> order = new ArrayList<>();
        for (String k : mc.sections()) for (String[] it : secs.get(k)) order.add(it[0]);
        for (String old : MenuTheme.order(a)) if (!order.contains(old)) order.add(old);
        MenuTheme.saveOrder(a, order);
        focusTag = "item:" + id + (j == 0 ? ":down" : j == mine.size() - 1 ? ":up" : delta < 0 ? ":up" : ":down");
        render();
    }

    private void chooseSection(String id, String fromKey, String original) {
        MenuCustom mc = MenuCustom.load(a);
        List<String> keys = mc.sections();
        String[] names = new String[keys.size()];
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = mc.sectionName(keys.get(i));
            if (keys.get(i).equals(fromKey)) sel = i;
        }
        new DpDialog(a).setTitle("Move “" + mc.titleOf(id, original) + "” to")
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    d.dismiss();
                    MenuCustom m = MenuCustom.load(a);
                    String to = keys.get(w);
                    if (to.equals(QuickMenuActivity.defaultSection(id))) m.itemSection.remove(id);
                    else m.itemSection.put(id, to);
                    m.save(a);
                    focusTag = "item:" + id + ":move";
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void newSection() {
        ask("New section", "", name -> {
            MenuCustom m = MenuCustom.load(a);
            if (m.isSection(name)) {
                Toast.makeText(a, "There's already a section called that", Toast.LENGTH_SHORT).show();
                return;
            }
            m.extraSections.add(name);
            m.save(a);
            render();
        });
    }

    void resetAll() {
        new DpDialog(a).setTitle("Reset names, sections and order?")
                .setMessage("Every item goes back to its own section, name and place, and sections you made are "
                        + "removed. Which items are shown stays as it is.")
                .setPositiveButton("Reset", (d, w) -> {
                    MenuCustom.reset(a);
                    MenuTheme.resetArrangement(a);
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    interface Named {
        void name(String name);
    }

    private void ask(String title, String current, Named done) {
        EditText e = new EditText(a);
        e.setText(current);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(e);
        new DpDialog(a).setTitle(title).setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.length() > 40) n = n.substring(0, 40);
                    if (!n.isEmpty()) done.name(n);
                })
                .setNegativeButton("Cancel", null)
                .show();
        e.requestFocus();
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
