package com.adonzin.dockpad;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.adonzin.dockpad.core.Shortcut;

import java.util.ArrayList;
import java.util.List;

/** Organise the quick menu: rename and reorder sections, make new ones, move and rename items. */
final class MenuOrganizer {
    private MenuOrganizer() {
    }

    /** id, original name. */
    private static List<String[]> catalog(Activity a) {
        List<String[]> out = new ArrayList<>();
        for (String[] it : QuickMenuActivity.ITEMS) {
            if (it[0].equals("builtinctrl")) continue; // an option, not an item
            out.add(new String[]{it[0], it[1]});
        }
        for (Shortcut c : new Prefs(a).menuCommands()) out.add(new String[]{QuickMenuActivity.commandId(c), c.device});
        return out;
    }

    static void show(Activity a) {
        MenuCustom mc = MenuCustom.load(a);
        List<String> keys = mc.sections();
        List<String[]> cat = catalog(a);
        List<String> rows = new ArrayList<>();
        for (String k : keys) {
            int n = 0;
            for (String[] it : cat) if (mc.sectionOf(it[0], QuickMenuActivity.defaultSection(it[0])).equals(k)) n++;
            String name = mc.sectionName(k);
            rows.add(name + (name.equals(k) || mc.isExtra(k) ? "" : "  (was " + k + ")") + "  ·  " + n
                    + (n == 1 ? " item" : " items"));
        }
        rows.add("＋ New section…");
        rows.add("Reset all names and sections");
        new DpDialog(a).setTitle("Organise sections & names")
                .setItems(rows.toArray(new String[0]), (d, w) -> {
                    if (w < keys.size()) section(a, keys.get(w));
                    else if (w == keys.size()) ask(a, "New section", "", name -> {
                        MenuCustom m = MenuCustom.load(a);
                        if (m.isSection(name)) {
                            Toast.makeText(a, "There's already a section called that", Toast.LENGTH_SHORT).show();
                        } else {
                            m.extraSections.add(name);
                            m.save(a);
                        }
                        show(a);
                    });
                    else new DpDialog(a).setTitle("Reset all names and sections?")
                                .setMessage("Every item goes back to its own section and name, and sections you made are removed.")
                                .setPositiveButton("Reset", (d2, w2) -> {
                                    MenuCustom.reset(a);
                                    show(a);
                                })
                                .setNegativeButton("Cancel", (d2, w2) -> show(a)).show();
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private static void section(Activity a, String key) {
        MenuCustom mc = MenuCustom.load(a);
        List<String> opts = new ArrayList<>();
        opts.add("Items in this section…");
        opts.add("Rename…");
        opts.add("Move up");
        opts.add("Move down");
        if (!mc.sectionName(key).equals(key) && !mc.isExtra(key)) opts.add("Use the original name (" + key + ")");
        if (mc.isExtra(key)) opts.add("Delete this section");
        new DpDialog(a).setTitle(mc.sectionName(key))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    MenuCustom m = MenuCustom.load(a);
                    if (o.startsWith("Items")) {
                        items(a, key);
                        return;
                    }
                    if (o.startsWith("Rename")) {
                        ask(a, "Rename section", m.sectionName(key), name -> {
                            MenuCustom m2 = MenuCustom.load(a);
                            m2.sectionNames.put(key, name);
                            m2.save(a);
                            section(a, key);
                        });
                        return;
                    }
                    if (o.equals("Move up")) m.moveSection(key, -1);
                    else if (o.equals("Move down")) m.moveSection(key, 1);
                    else if (o.startsWith("Use the original")) m.sectionNames.remove(key);
                    else if (o.startsWith("Delete")) {
                        m.deleteSection(key);
                        m.save(a);
                        show(a);
                        return;
                    }
                    m.save(a);
                    if (o.startsWith("Move")) show(a);
                    else section(a, key);
                })
                .setNegativeButton("Back", (d, w) -> show(a))
                .show();
    }

    private static void items(Activity a, String key) {
        MenuCustom mc = MenuCustom.load(a);
        List<String[]> mine = new ArrayList<>();
        for (String[] it : catalog(a)) {
            if (mc.sectionOf(it[0], QuickMenuActivity.defaultSection(it[0])).equals(key)) mine.add(it);
        }
        if (mine.isEmpty()) {
            new DpDialog(a).setTitle(mc.sectionName(key))
                    .setMessage("Nothing here yet. Open another section's items and move some here.")
                    .setPositiveButton("Back", (d, w) -> section(a, key)).show();
            return;
        }
        java.util.Set<String> hidden = new Prefs(a).menuHidden();
        String[] labels = new String[mine.size()];
        for (int i = 0; i < labels.length; i++) {
            String id = mine.get(i)[0];
            String t = mc.titleOf(id, mine.get(i)[1]);
            labels[i] = t + (t.equals(mine.get(i)[1]) ? "" : "  (" + mine.get(i)[1] + ")")
                    + (hidden.contains(id) ? "  · hidden" : "");
        }
        new DpDialog(a).setTitle(mc.sectionName(key) + ": items")
                .setItems(labels, (d, w) -> item(a, key, mine.get(w)))
                .setNegativeButton("Back", (d, w) -> section(a, key))
                .show();
    }

    private static void item(Activity a, String fromKey, String[] it) {
        MenuCustom mc = MenuCustom.load(a);
        String id = it[0];
        boolean group = id.equals("controllers");
        List<String> opts = new ArrayList<>();
        opts.add("Move to another section…");
        if (!group) opts.add("Rename…");
        if (!group && mc.itemTitle.containsKey(id)) opts.add("Use the original name (" + it[1] + ")");
        new DpDialog(a).setTitle(group ? "Controllers list" : mc.titleOf(id, it[1]))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    if (o.startsWith("Move")) {
                        MenuCustom m = MenuCustom.load(a);
                        List<String> keys = m.sections();
                        String[] names = new String[keys.size()];
                        for (int i = 0; i < names.length; i++) {
                            names[i] = m.sectionName(keys.get(i)) + (keys.get(i).equals(fromKey) ? "  (here now)" : "");
                        }
                        new DpDialog(a).setTitle("Move to")
                                .setItems(names, (d2, w2) -> {
                                    MenuCustom m2 = MenuCustom.load(a);
                                    String to = keys.get(w2);
                                    if (to.equals(QuickMenuActivity.defaultSection(id))) m2.itemSection.remove(id);
                                    else m2.itemSection.put(id, to);
                                    m2.save(a);
                                    items(a, fromKey);
                                })
                                .setNegativeButton("Back", (d2, w2) -> item(a, fromKey, it)).show();
                    } else if (o.startsWith("Rename")) {
                        ask(a, "Rename", mc.titleOf(id, it[1]).replaceAll(": (on|off)$", ""), name -> {
                            MenuCustom m = MenuCustom.load(a);
                            if (name.equals(it[1])) m.itemTitle.remove(id);
                            else m.itemTitle.put(id, name);
                            m.save(a);
                            items(a, fromKey);
                        });
                    } else {
                        MenuCustom m = MenuCustom.load(a);
                        m.itemTitle.remove(id);
                        m.save(a);
                        items(a, fromKey);
                    }
                })
                .setNegativeButton("Back", (d, w) -> items(a, fromKey))
                .show();
    }

    interface Named {
        void name(String name);
    }

    private static void ask(Activity a, String title, String current, Named done) {
        EditText e = new EditText(a);
        e.setText(current);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout box = new FrameLayout(a);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
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
}
