package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Choosing, saving, importing and sharing quick menu themes. */
final class ThemesDialog {
    /** Opens a file picker for a theme file; calls back with what was picked. */
    interface FilePicker {
        void pick(java.util.function.Consumer<Uri> done);
    }

    private static final Executor bg = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private ThemesDialog() {
    }

    /** The themes list. {@code afterApply}: called when a theme is put in use (e.g. to redraw). */
    static void choose(Activity a, Runnable afterApply) {
        List<ThemeLibrary.Entry> all = ThemeLibrary.all(a);
        String active = ThemeLibrary.activeId(a);
        String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++) {
            ThemeLibrary.Entry e = all.get(i);
            labels[i] = e.name + (e.id.equals(active) ? "   ✓ in use" : "")
                    + (e.builtIn ? "" : "   · " + (e.author.isEmpty() ? "yours" : "by " + e.author))
                    + (e.description.isEmpty() ? "" : "\n" + e.description);
        }
        new DpDialog(a).setTitle("Themes")
                .setItems(labels, (d, w) -> entry(a, all.get(w), afterApply))
                .setNegativeButton("Close", null)
                .show();
    }

    private static void entry(Activity a, ThemeLibrary.Entry e, Runnable afterApply) {
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("Use it");
        opts.add("Use it and preview");
        opts.add("Share it…");
        if (!e.builtIn) {
            opts.add("Replace it with my current look…");
            opts.add("Delete it…");
        }
        new DpDialog(a).setTitle(e.name)
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    if (o.startsWith("Use it")) {
                        ThemeLibrary.apply(a, e);
                        Toast.makeText(a, e.name + " is in use", Toast.LENGTH_SHORT).show();
                        if (afterApply != null) afterApply.run();
                        if (o.endsWith("preview")) preview(a);
                    } else if (o.startsWith("Share")) {
                        share(a, e.json);
                    } else if (o.startsWith("Replace")) {
                        confirmOverwrite(a, e, () -> {
                            if (afterApply != null) afterApply.run();
                            choose(a, afterApply);
                        });
                    } else {
                        new DpDialog(a).setTitle("Delete " + e.name + "?")
                                .setMessage("Its pictures and sounds stay, in case other themes use them.")
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    ThemeLibrary.delete(a, e);
                                    choose(a, afterApply);
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                })
                .setNegativeButton("Back", (d, w) -> choose(a, afterApply))
                .show();
    }

    /** Asks, then saves the current look over a saved theme (its name and author stay). */
    private static void confirmOverwrite(Activity a, ThemeLibrary.Entry e, Runnable after) {
        new DpDialog(a).setTitle("Replace " + e.name + "?")
                .setMessage("“" + e.name + "” becomes your current look: layout, colours, pictures, sounds and font. "
                        + "Its name and author stay. This can't be undone.")
                .setPositiveButton("Replace", (d, w) -> {
                    try {
                        ThemeLibrary.overwrite(a, e);
                        Toast.makeText(a, "Updated “" + e.name + "”", Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    } catch (Exception x) {
                        Toast.makeText(a, "Couldn't save: " + x.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static void saveCurrent(Activity a, Runnable after) {
        MenuTheme cur = MenuTheme.load(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 24);
        box.setPadding(p, dp(a, 8), p, 0);
        EditText name = field(a, box, "Name", cur.name.equals("Default") ? "" : cur.name);
        String lastAuthor = a.getSharedPreferences("dockpad", Activity.MODE_PRIVATE).getString("themeAuthor", "");
        EditText author = field(a, box, "Made by", cur.author.isEmpty() || cur.author.equals("DockPad") ? lastAuthor : cur.author);
        EditText desc = field(a, box, "A line about it", cur.description);
        ThemeLibrary.Entry inUse = ThemeLibrary.savedInUse(a);
        DpDialog dlg = new DpDialog(a);
        dlg.setTitle("Save my look as a theme")
                .setMessage("Saves the layout, colours, pictures, decorations, sounds and font as a theme you can "
                        + "switch back to or share.")
                .setView(box)
                .setPositiveButton("Save as new", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = "My theme";
                    String au = author.getText().toString().trim();
                    String de = desc.getText().toString().trim();
                    a.getSharedPreferences("dockpad", Activity.MODE_PRIVATE).edit().putString("themeAuthor", au).apply();
                    ThemeLibrary.Entry same = ThemeLibrary.savedNamed(a, n);
                    String nn = n;
                    if (same != null) { // the name is taken: replace that one, or keep both
                        new DpDialog(a).setTitle("“" + same.name + "” already exists")
                                .setMessage("Replace it with your current look, or save a new copy next to it?")
                                .setPositiveButton("Replace it", (d2, w2) -> saveOver(a, same, nn, au, de, after))
                                .setNeutralButton("Save a copy", (d2, w2) -> saveNew(a, nn, au, de, after))
                                .setNegativeButton("Cancel", null).show();
                        return;
                    }
                    saveNew(a, n, au, de, after);
                })
                .setNegativeButton("Cancel", null);
        if (inUse != null) {
            // the look came from one of your themes: offer to update that one
            dlg.setNeutralButton("Update “" + limit(inUse.name, 20) + "”", (d, w) -> {
                String au = author.getText().toString().trim();
                saveOver(a, inUse, name.getText().toString().trim(), au, desc.getText().toString().trim(), after);
            });
        }
        dlg.show();
        name.requestFocus();
    }

    private static void saveNew(Activity a, String n, String au, String de, Runnable after) {
        try {
            ThemeLibrary.saveCurrent(a, limit(n, 40), limit(au, 40), limit(de, 160));
            Toast.makeText(a, "Saved “" + n + "”", Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Saves the current look over {@code e}, with the name/author/description typed in the dialog. */
    private static void saveOver(Activity a, ThemeLibrary.Entry e, String n, String au, String de, Runnable after) {
        try {
            ThemeLibrary.Entry renamed = ThemeLibrary.fromJson(e.id, e.json, false);
            renamed.name = limit(n.isEmpty() ? e.name : n, 40);
            renamed.author = limit(au, 40);
            renamed.description = limit(de, 160);
            ThemeLibrary.overwrite(a, renamed);
            Toast.makeText(a, "Updated “" + renamed.name + "”", Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
        } catch (Exception x) {
            Toast.makeText(a, "Couldn't save: " + x.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void importFile(Activity a, FilePicker picker, Runnable after) {
        picker.pick(uri -> {
            Toast.makeText(a, "Installing…", Toast.LENGTH_SHORT).show();
            bg.execute(() -> {
                try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new Exception("couldn't open the file");
                    ThemeLibrary.Entry e = ThemeLibrary.install(a, in);
                    ui.post(() -> new DpDialog(a).setTitle("Installed “" + e.name + "”")
                            .setMessage((e.author.isEmpty() ? "" : "By " + e.author + ". ") + e.description)
                            .setPositiveButton("Use it now", (d, w) -> {
                                ThemeLibrary.apply(a, e);
                                if (after != null) after.run();
                                preview(a);
                            })
                            .setNegativeButton("Later", (d, w) -> {
                                if (after != null) after.run();
                            }).show());
                } catch (Exception ex) {
                    ui.post(() -> new DpDialog(a).setTitle("Couldn't install it")
                            .setMessage(String.valueOf(ex.getMessage())).setPositiveButton("OK", null).show());
                }
            });
        });
    }

    /** Writes a theme file to Download/DockPad and offers to share it. */
    static void share(Activity a, String json) {
        bg.execute(() -> {
            try {
                Uri uri = ThemeLibrary.export(a, json);
                ui.post(() -> new DpDialog(a).setTitle("Theme file ready")
                        .setMessage("Saved in Download/DockPad, with its pictures, sounds and font. Anyone can install "
                                + "it with “Import a theme file”.")
                        .setPositiveButton("Share", (d, w) -> a.startActivity(Intent.createChooser(
                                new Intent(Intent.ACTION_SEND).setType("application/zip")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share theme")))
                        .setNegativeButton("Done", null).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(a, "Couldn't make the file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void preview(Activity a) {
        a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static EditText field(Activity a, LinearLayout box, String label, String value) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        t.setPadding(0, dp(a, 10), 0, 0);
        box.addView(t);
        EditText e = new EditText(a);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(e);
        return e;
    }

    private static String limit(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
