package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/** The quick menu's look: palette, colours and gradients, transparency, shapes, text, highlight, header. */
final class MenuStyleDialog {
    private final Activity a;
    private final MenuTheme theme;
    private final MenuStyle st;
    private LinearLayout box;

    private MenuStyleDialog(Activity a) {
        this.a = a;
        this.theme = MenuTheme.load(a);
        this.st = theme.style;
    }

    static void show(Activity a) {
        new MenuStyleDialog(a).open();
    }

    private void open() {
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        build();
        new DpDialog(a).setTitle("Quick menu look").setView(sv)
                .setPositiveButton("Save", (d, w) -> theme.save(a))
                .setNeutralButton("Save and preview", (d, w) -> {
                    theme.save(a);
                    a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** (Re)builds the controls from the current values. */
    private void build() {
        // remember which control was highlighted, to put it back after redrawing
        int focusIndex = -1;
        java.util.ArrayList<View> before = box.getFocusables(View.FOCUS_FORWARD);
        for (int i = 0; i < before.size(); i++) if (before.get(i).isFocused()) focusIndex = i;
        box.removeAllViews();

        label("Palette");
        small("A starting point; anything below can then be changed.");
        String[] palLabels = new String[MenuStyle.PALETTES.length + 1];
        int palSel = MenuStyle.PALETTES.length;
        for (int i = 0; i < MenuStyle.PALETTES.length; i++) {
            palLabels[i] = MenuStyle.PALETTES[i][1];
            if (MenuStyle.PALETTES[i][0].equals(st.palette)) palSel = i;
        }
        palLabels[palLabels.length - 1] = "Custom";
        radios(palLabels, palSel, i -> {
            if (i < MenuStyle.PALETTES.length) {
                st.applyPalette(MenuStyle.PALETTES[i][0]);
                build();
            }
        });

        label("Panel");
        radios(MenuStyle.GRADIENT_LABELS, indexOf(MenuStyle.GRADIENTS, st.gradient), i -> {
            st.gradient = MenuStyle.GRADIENTS[i];
            custom();
            build();
        });
        colorRow("Colour" + (st.gradient.equals("none") ? "" : " 1"), st.panel1, "#121519", c -> st.panel1 = c);
        if (!st.gradient.equals("none")) {
            colorRow("Colour 2", st.panel2, st.panel1.isEmpty() ? "#121519" : st.panel1, c -> st.panel2 = c);
            colorRow("Colour 3 (optional)", st.panel3, "", c -> st.panel3 = c);
            if (st.gradient.equals("linear")) slider("Direction", 0, 315, 45, st.angle, v -> st.angle = v, v -> v + "°");
        }
        slider("Panel opacity", 10, 100, 5, st.opacity, v -> st.opacity = v, v -> v + "%");
        slider("Darken the game behind", -5, 95, 5, st.dim, v -> st.dim = v < 0 ? -1 : v,
                v -> v < 0 ? "the style's own" : v + "%");

        label("Colours");
        colorRow("Accent (icons, highlight)", st.accent, "", c -> st.accent = c);
        colorRow("Text", st.text, "#FFFFFF", c -> st.text = c);
        colorRow("Tiles", st.tile, "#22FFFFFF", c -> st.tile = c);

        label("Shape");
        slider("Corner roundness", 0, 48, 2, st.corners, v -> st.corners = v, v -> v == 0 ? "square" : v + " dp");
        slider("Border", 0, 8, 1, st.border, v -> st.border = v, v -> v == 0 ? "none" : v + " dp");
        if (st.border > 0) colorRow("Border colour", st.borderColor, "", c -> st.borderColor = c);
        toggle("Glowing edge", st.glow, v -> st.glow = v);

        label("Text");
        slider("Size", 70, 160, 10, st.textSize, v -> st.textSize = v, v -> v + "%");
        radios(MenuStyle.FONT_LABELS, indexOf(MenuStyle.FONTS, st.font), i -> st.font = MenuStyle.FONTS[i]);

        label("Highlight");
        small("How the selected item stands out.");
        radios(MenuStyle.HIGHLIGHT_LABELS, indexOf(MenuStyle.HIGHLIGHTS, st.highlight),
                i -> st.highlight = MenuStyle.HIGHLIGHTS[i]);

        label("Header");
        toggle("Title (DockPad)", st.showTitle, v -> st.showTitle = v);
        toggle("Status (on/off, Cursor mode)", st.showStatus, v -> st.showStatus = v);
        toggle("Clock", st.showClock, v -> st.showClock = v);
        toggle("Handheld battery", st.showBattery, v -> st.showBattery = v);

        MaterialButton reset = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText("Reset the look");
        reset.setOnClickListener(v -> {
            copy(new MenuStyle(), st);
            build();
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(16);
        box.addView(reset, rl);
        if (focusIndex >= 0) {
            java.util.ArrayList<View> after = box.getFocusables(View.FOCUS_FORWARD);
            if (focusIndex < after.size()) {
                View again = after.get(focusIndex);
                again.post(again::requestFocus);
            }
        }
    }

    private static void copy(MenuStyle from, MenuStyle to) {
        MenuStyle p = MenuStyle.parse(from.toJson());
        to.palette = p.palette; to.panel1 = p.panel1; to.panel2 = p.panel2; to.panel3 = p.panel3;
        to.gradient = p.gradient; to.angle = p.angle; to.accent = p.accent; to.text = p.text; to.tile = p.tile;
        to.opacity = p.opacity; to.dim = p.dim; to.corners = p.corners; to.border = p.border;
        to.borderColor = p.borderColor; to.glow = p.glow; to.textSize = p.textSize; to.font = p.font;
        to.highlight = p.highlight; to.showTitle = p.showTitle; to.showStatus = p.showStatus;
        to.showClock = p.showClock; to.showBattery = p.showBattery;
    }

    private void custom() {
        st.palette = "custom";
    }

    // ------------------------------------------------------------------ controls

    interface IntSet {
        void set(int v);
    }

    interface IntLabel {
        String label(int v);
    }

    interface StrSet {
        void set(String v);
    }

    interface BoolSet {
        void set(boolean v);
    }

    private void label(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setPadding(0, dp(16), 0, dp(2));
        box.addView(t);
    }

    private void small(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        box.addView(t);
    }

    private void radios(String[] labels, int sel, IntSet on) {
        RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setForeground(a.getDrawable(R.drawable.focus_ring));
            g.addView(b);
            if (i == sel) b.setChecked(true);
        }
        g.setOnCheckedChangeListener((gr, id) -> {
            for (int i = 0; i < gr.getChildCount(); i++) {
                if (gr.getChildAt(i).getId() == id) {
                    on.set(i);
                    return;
                }
            }
        });
        box.addView(g);
    }

    private void slider(String name, int from, int to, int step, int value, IntSet on, IntLabel fmt) {
        TextView t = new TextView(a);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setText(name + ": " + fmt.label(value));
        t.setPadding(0, dp(6), 0, 0);
        box.addView(t);
        Slider s = new Slider(a);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(step);
        int v = Math.max(from, Math.min(to, from + Math.round((value - from) / (float) step) * step));
        s.setValue(v);
        s.addOnChangeListener((sl, val, user) -> {
            int iv = Math.round(val);
            t.setText(name + ": " + fmt.label(iv));
            on.set(iv);
            custom();
        });
        box.addView(s);
    }

    private void toggle(String name, boolean value, BoolSet on) {
        MaterialSwitch sw = new MaterialSwitch(a);
        sw.setText(name);
        sw.setChecked(value);
        sw.setForeground(a.getDrawable(R.drawable.focus_ring));
        sw.setPadding(dp(6), dp(8), dp(6), dp(8));
        sw.setOnCheckedChangeListener((b, c) -> on.set(c));
        box.addView(sw);
    }

    /** A row: name, a swatch showing the colour, select to pick another. */
    private void colorRow(String name, String value, String autoShown, StrSet on) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setForeground(a.getDrawable(R.drawable.focus_ring));
        View sw = new View(a);
        sw.setBackground(swatch(value.isEmpty() ? autoShown : value, value.isEmpty()));
        row.addView(sw, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView t = new TextView(a);
        t.setText(name + ":  " + (value.isEmpty() ? "automatic" : value));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> pickColor(name, value, c -> {
            on.set(c);
            custom();
            build();
        }));
        box.addView(row);
    }

    private GradientDrawable swatch(String color, boolean auto) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(MenuStyle.parse(color, 0x00000000));
        d.setStroke(dp(auto ? 2 : 1), auto ? 0x88FFFFFF : 0x44FFFFFF);
        return d;
    }

    static final String[] SWATCHES = {
            "#000000", "#121519", "#1E1E2E", "#2B2D42", "#3A3A3A", "#6B7280", "#D1D5DB", "#FFFFFF",
            "#7F1D1D", "#DC2626", "#F87171", "#9A3412", "#F97316", "#FDBA74", "#A16207", "#FACC15",
            "#365314", "#65A30D", "#A3E635", "#14532D", "#22C55E", "#86EFAC", "#134E4A", "#14B8A6",
            "#0C4A6E", "#0EA5E9", "#7DD3FC", "#1E3A8A", "#3B82F6", "#93C5FD", "#312E81", "#6366F1",
            "#4C1D95", "#8B5CF6", "#C4B5FD", "#701A75", "#D946EF", "#F0ABFC", "#831843", "#EC4899",
            "#FF2A6D", "#05D9E8", "#39FF14", "#FFB86C", "#D53369", "#2B1055", "#0F2027", "#2C5364"};

    private void pickColor(String name, String current, StrSet done) {
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(8);
        grid.setPadding(dp(16), dp(8), dp(16), 0);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        View first = null;
        TextView auto = new TextView(a);
        auto.setText("Auto");
        auto.setGravity(Gravity.CENTER);
        auto.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        first = cell(grid, auto, swatch("#00000000", true), () -> {
            dialog[0].dismiss();
            done.set("");
        });
        for (String c : SWATCHES) {
            View v = new View(a);
            View cellView = cell(grid, v, swatch(c, false), () -> {
                dialog[0].dismiss();
                done.set(c);
            });
            if (c.equalsIgnoreCase(current)) first = cellView;
        }
        TextView hex = new TextView(a);
        hex.setText("#…");
        hex.setGravity(Gravity.CENTER);
        hex.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        cell(grid, hex, swatch("#00000000", true), () -> {
            dialog[0].dismiss();
            askHex(current, done);
        });
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid);
        TextView tip = new TextView(a);
        tip.setText("“#…” types any colour, like #FF8800 (or #80FF8800 for see-through).");
        tip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        tip.setPadding(dp(20), dp(6), dp(20), 0);
        col.addView(tip);
        ScrollView sv = new ScrollView(a);
        sv.addView(col);
        dialog[0] = new DpDialog(a).setTitle(name).setView(sv).setNegativeButton("Cancel", null).show();
        View start = first;
        grid.post(start::requestFocus);
    }

    private View cell(GridLayout grid, View v, android.graphics.drawable.Drawable bg, Runnable pick) {
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setForeground(a.getDrawable(R.drawable.focus_ring));
        v.setOnClickListener(x -> pick.run());
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(40);
        lp.height = dp(40);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(v, lp);
        return v;
    }

    private void askHex(String current, StrSet done) {
        EditText e = new EditText(a);
        e.setText(current.isEmpty() ? "#" : current);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new DpDialog(a).setTitle("Colour code").setView(box)
                .setPositiveButton("Use", (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (!v.startsWith("#")) v = "#" + v;
                    String ok = MenuStyle.color(v);
                    if (ok.isEmpty()) android.widget.Toast.makeText(a, "Use #RRGGBB or #AARRGGBB",
                            android.widget.Toast.LENGTH_SHORT).show();
                    else done.set(ok);
                })
                .setNegativeButton("Cancel", null).show();
        e.requestFocus();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
