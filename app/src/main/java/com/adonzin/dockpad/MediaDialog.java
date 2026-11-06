package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/** Background (picture, GIF, video) and decorations for the quick menu. */
final class MediaDialog {
    /** Opens a file picker and imports what was picked into DockPad's media; calls back with its name. */
    interface Importer {
        void pick(boolean videoToo, java.util.function.Consumer<String> done);
    }

    private final Activity a;
    private final Importer importer;

    private MediaDialog(Activity a, Importer importer) {
        this.a = a;
        this.importer = importer;
    }

    static void background(Activity a, Importer importer) {
        new MediaDialog(a, importer).editBackground();
    }

    static void decorations(Activity a, Importer importer) {
        new MediaDialog(a, importer).listDecorations();
    }

    // ------------------------------------------------------------------ background

    private void editBackground() {
        MenuTheme t = MenuTheme.load(a);
        MenuMedia.Background bg = t.background;
        LinearLayout box = box();
        TextView current = text(bg.isSet() ? "Now: " + bg.file : "No background yet.", false);
        box.addView(current);
        if (bg.isSet()) box.addView(thumb(bg.file, 96));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        buttons.addView(button("Choose a file…", () -> importer.pick(true, name -> {
            MenuTheme t2 = MenuTheme.load(a);
            t2.background.file = name;
            if (MenuMedia.isVideo(name)) t2.background.target = "screen";
            t2.save(a);
            dlg[0].dismiss();
            editBackground();
        })));
        if (MenuMedia.list(a, true).length > 0) buttons.addView(button("Use one I added before…", () ->
                chooseExisting(true, name -> {
                    MenuTheme t2 = MenuTheme.load(a);
                    t2.background.file = name;
                    if (MenuMedia.isVideo(name)) t2.background.target = "screen";
                    t2.save(a);
                    dlg[0].dismiss();
                    editBackground();
                })));
        if (bg.isSet()) buttons.addView(button("No background", () -> {
            MenuTheme t2 = MenuTheme.load(a);
            t2.background.file = "";
            t2.save(a);
            dlg[0].dismiss();
            editBackground();
        }));
        box.addView(buttons);

        boolean video = bg.isSet() && MenuMedia.isVideo(bg.file);
        box.addView(label("Where"));
        RadioGroup where = radios(new String[]{"The whole screen, behind the menu",
                video ? "Inside the menu's panel (pictures and GIFs only)" : "Inside the menu's panel"},
                bg.target.equals("panel") ? 1 : 0);
        if (video) where.getChildAt(1).setEnabled(false);
        box.addView(where);
        box.addView(label("Fit"));
        RadioGroup scale = radios(new String[]{"Fill (crop the edges)", "Fit (show it all)"}, bg.scale.equals("fit") ? 1 : 0);
        box.addView(scale);
        int[] blur = {bg.blur}, dim = {bg.dim};
        slider(box, "Blur", 0, 25, 1, bg.blur, v -> blur[0] = v, v -> v == 0 ? "none" : String.valueOf(v));
        slider(box, "Darken", 0, 90, 5, bg.dim, v -> dim[0] = v, v -> v + "%");
        box.addView(text("Pictures: PNG, JPG, WebP. GIFs animate. Videos (MP4, WebM) loop without sound.", true));

        Runnable save = () -> {
            MenuTheme t2 = MenuTheme.load(a);
            t2.background.target = checked(where) == 1 && !video ? "panel" : "screen";
            t2.background.scale = checked(scale) == 1 ? "fit" : "fill";
            t2.background.blur = blur[0];
            t2.background.dim = dim[0];
            t2.save(a);
        };
        dlg[0] = new DpDialog(a).setTitle("Quick menu background").setView(scroll(box))
                .setPositiveButton("Save", (d, w) -> save.run())
                .setNeutralButton("Save and preview", (d, w) -> {
                    save.run();
                    preview();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ decorations

    private void listDecorations() {
        MenuTheme t = MenuTheme.load(a);
        LinearLayout box = box();
        box.addView(text("Pictures and GIFs placed anywhere: in a corner, along an edge (repeated, like train "
                + "tracks), behind or in front of the menu.", true));
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        for (int i = 0; i < t.decorations.size(); i++) {
            MenuMedia.Decoration d = t.decorations.get(i);
            int index = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            row.setFocusable(true);
            row.setClickable(true);
            row.setForeground(a.getDrawable(R.drawable.focus_ring));
            row.addView(thumb(d.file, 48));
            TextView tx = text(d.file + "\n" + label(MenuMedia.ANCHOR_LABELS, MenuMedia.ANCHORS, d.anchor) + " · "
                    + d.size + "% · " + (d.front ? "in front" : "behind")
                    + (d.repeat.equals("none") ? "" : " · repeated"), false);
            tx.setPadding(dp(12), 0, 0, 0);
            row.addView(tx);
            row.setOnClickListener(v -> {
                dlg[0].dismiss();
                editDecoration(index, d.copy());
            });
            box.addView(row);
        }
        if (t.decorations.isEmpty()) box.addView(text("None yet.", false));
        LinearLayout buttons = new LinearLayout(a);
        buttons.addView(button("Add from a file…", () -> importer.pick(false, name -> {
            dlg[0].dismiss();
            MenuMedia.Decoration d = new MenuMedia.Decoration();
            d.file = name;
            editDecoration(-1, d);
        })));
        if (MenuMedia.list(a, false).length > 0) buttons.addView(button("Add one I added before…", () ->
                chooseExisting(false, name -> {
                    dlg[0].dismiss();
                    MenuMedia.Decoration d = new MenuMedia.Decoration();
                    d.file = name;
                    editDecoration(-1, d);
                })));
        box.addView(buttons);
        dlg[0] = new DpDialog(a).setTitle("Decorations").setView(scroll(box))
                .setPositiveButton("Done", null)
                .setNeutralButton("Preview", (dd, w) -> preview())
                .show();
    }

    private void editDecoration(int index, MenuMedia.Decoration d) {
        LinearLayout box = box();
        box.addView(thumb(d.file, 72));
        box.addView(label("Where"));
        RadioGroup anchor = radios(MenuMedia.ANCHOR_LABELS, indexOf(MenuMedia.ANCHORS, d.anchor));
        box.addView(anchor);
        slider(box, "Nudge left / right", -50, 50, 1, d.x, v -> d.x = v, v -> v + "%");
        slider(box, "Nudge up / down", -50, 50, 1, d.y, v -> d.y = v, v -> v + "%");
        slider(box, "Size", 2, 100, 1, d.size, v -> d.size = v, v -> v + "% of the screen's height");
        slider(box, "Opacity", 5, 100, 5, d.opacity, v -> d.opacity = v, v -> v + "%");
        box.addView(label("Repeat"));
        RadioGroup repeat = radios(MenuMedia.REPEAT_LABELS, indexOf(MenuMedia.REPEATS, d.repeat));
        box.addView(repeat);
        MaterialSwitch front = toggle("In front of the menu (otherwise behind it)", d.front);
        box.addView(front);
        MaterialSwitch mirror = toggle("Mirror it (flip left-right)", d.mirror);
        box.addView(mirror);

        Runnable save = () -> {
            d.anchor = MenuMedia.ANCHORS[checked(anchor)];
            d.repeat = MenuMedia.REPEATS[checked(repeat)];
            d.front = front.isChecked();
            d.mirror = mirror.isChecked();
            MenuTheme t = MenuTheme.load(a);
            if (index >= 0 && index < t.decorations.size()) t.decorations.set(index, d);
            else t.decorations.add(d);
            t.save(a);
        };
        DpDialog b = new DpDialog(a);
        b.setTitle(index < 0 ? "New decoration" : "Decoration").setView(scroll(box))
                .setPositiveButton("Save", (dd, w) -> {
                    save.run();
                    listDecorations();
                })
                .setNeutralButton("Save and preview", (dd, w) -> {
                    save.run();
                    preview();
                });
        if (index >= 0) b.setNegativeButton("Remove", (dd, w) -> {
            MenuTheme t = MenuTheme.load(a);
            if (index < t.decorations.size()) t.decorations.remove(index);
            t.save(a);
            listDecorations();
        });
        else b.setNegativeButton("Cancel", (dd, w) -> listDecorations());
        b.show();
    }

    // ------------------------------------------------------------------ shared

    /** Pick from files added before (with a thumbnail each). */
    private void chooseExisting(boolean videoToo, java.util.function.Consumer<String> done) {
        String[] names = MenuMedia.list(a, videoToo);
        LinearLayout box = box();
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        for (String n : names) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(4), dp(6), dp(4));
            row.setFocusable(true);
            row.setClickable(true);
            row.setForeground(a.getDrawable(R.drawable.focus_ring));
            row.addView(thumb(n, 44));
            TextView tx = text(n, false);
            tx.setPadding(dp(12), 0, 0, 0);
            row.addView(tx);
            row.setOnClickListener(v -> {
                dlg[0].dismiss();
                done.accept(n);
            });
            box.addView(row);
        }
        dlg[0] = new DpDialog(a).setTitle("Added before").setView(scroll(box))
                .setNegativeButton("Cancel", null).show();
    }

    private void preview() {
        a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private View thumb(String file, int size) {
        if (MenuMedia.isVideo(file)) {
            TextView t = text("▶ video", false);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            return t;
        }
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(MenuMedia.load(a, file, dp(size) * 2));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return iv;
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        return box;
    }

    private ScrollView scroll(View v) {
        ScrollView sv = new ScrollView(a);
        sv.addView(v);
        return sv;
    }

    private TextView text(String s, boolean small) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(small ? com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private static String label(String[] labels, String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return labels[i];
        return id;
    }

    private MaterialButton button(String s, Runnable r) {
        MaterialButton b = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(s);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private MaterialSwitch toggle(String s, boolean v) {
        MaterialSwitch sw = new MaterialSwitch(a);
        sw.setText(s);
        sw.setChecked(v);
        sw.setForeground(a.getDrawable(R.drawable.focus_ring));
        sw.setPadding(dp(6), dp(8), dp(6), dp(8));
        return sw;
    }

    private RadioGroup radios(String[] labels, int sel) {
        RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setForeground(a.getDrawable(R.drawable.focus_ring));
            g.addView(b);
            if (i == sel) b.setChecked(true);
        }
        return g;
    }

    private static int checked(RadioGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (((RadioButton) g.getChildAt(i)).isChecked()) return i;
        return 0;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    interface IntSet {
        void set(int v);
    }

    interface IntLabel {
        String label(int v);
    }

    private void slider(LinearLayout box, String name, int from, int to, int step, int value, IntSet on, IntLabel fmt) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(2));
        TextView t = text(name + ": " + fmt.label(value), false);
        row.addView(t);
        Slider s = new Slider(a);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(step);
        s.setValue(Math.max(from, Math.min(to, from + Math.round((value - from) / (float) step) * step)));
        s.addOnChangeListener((sl, v, u) -> {
            int iv = Math.round(v);
            t.setText(name + ": " + fmt.label(iv));
            on.set(iv);
        });
        row.addView(s);
        MenuStyleDialog.focusRow(a, row, s, t);
        box.addView(row);
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
