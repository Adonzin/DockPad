package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/** Quick menu look & layout: layout type, position, size, sections, arrangement. */
final class MenuLookDialog {
    private MenuLookDialog() {
    }

    static void show(Activity a) {
        MenuTheme t = MenuTheme.load(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 24);
        box.setPadding(pad, dp(a, 8), pad, 0);

        box.addView(label(a, "Layout"));
        RadioGroup layouts = radios(a, MenuTheme.LAYOUT_LABELS, indexOf(MenuTheme.LAYOUTS, t.layout));
        box.addView(layouts);
        TextView layoutHelp = small(a, "");
        box.addView(layoutHelp);

        TextView posLabel = label(a, "Position");
        box.addView(posLabel);
        RadioGroup positions = radios(a, MenuTheme.POSITION_LABELS, indexOf(MenuTheme.POSITIONS, t.position));
        box.addView(positions);

        TextView spotLabel = label(a, "Where the wheel sits");
        box.addView(spotLabel);
        RadioGroup spots = radios(a, MenuTheme.SPOT_LABELS, indexOf(MenuTheme.SPOTS, t.spot));
        box.addView(spots);

        TextView bandLabel = label(a, "Where the strip sits");
        box.addView(bandLabel);
        RadioGroup bands = radios(a, MenuTheme.BAND_LABELS, indexOf(MenuTheme.BANDS, t.band));
        box.addView(bands);

        TextView sizeLabel = label(a, "");
        box.addView(sizeLabel);
        Slider size = new Slider(a);
        size.setValueFrom(40);
        size.setValueTo(100);
        size.setStepSize(5);
        size.setValue(Math.max(40, Math.min(100, Math.round(t.size / 5f) * 5)));
        box.addView(size);
        MenuStyleDialog.focusRow(a, size, size, sizeLabel);

        TextView widthLabel = label(a, "");
        box.addView(widthLabel);
        Slider width = new Slider(a);
        width.setValueFrom(240);
        width.setValueTo(900);
        width.setStepSize(20);
        width.setValue(Math.max(240, Math.min(900, Math.round(t.width / 20f) * 20)));
        box.addView(width);
        MenuStyleDialog.focusRow(a, width, width, widthLabel);

        TextView colsLabel = label(a, "");
        box.addView(colsLabel);
        Slider cols = new Slider(a);
        cols.setValueFrom(2);
        cols.setValueTo(6);
        cols.setStepSize(1);
        cols.setValue(Math.max(2, Math.min(6, t.columns)));
        box.addView(cols);
        MenuStyleDialog.focusRow(a, cols, cols, colsLabel);

        MaterialSwitch sections = new MaterialSwitch(a);
        sections.setText("Group items into sections");
        sections.setChecked(t.sections);
        sections.setForeground(a.getDrawable(R.drawable.focus_ring));
        sections.setPadding(dp(a, 6), dp(a, 10), dp(a, 6), dp(a, 10));
        box.addView(sections);

        box.addView(small(a, "To change the order, open the quick menu and press Y. In Freeform you can also move "
                + "and resize each item there (stick, D-pad or touch; L1/R1 resize)."));

        MaterialButton reset = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText("Reset the order and positions");
        box.addView(reset);

        Runnable refresh = () -> {
            String lay = MenuTheme.LAYOUTS[checked(layouts)];
            layoutHelp.setText(help(lay));
            boolean usesPosition = in(lay, "list", "compact", "grid", "freeform", "wheel", "tabs", "rail", "arc");
            boolean edgeOnly = in(lay, "rail", "arc");
            boolean usesBand = in(lay, "carousel", "dock");
            boolean usesSize = in(lay, "carousel", "dock", "radial", "arc", "xmb", "rail");
            boolean usesCols = in(lay, "grid", "freeform", "tabs");
            boolean usesSections = in(lay, "list", "compact", "grid", "wheel", "carousel", "radial");
            posLabel.setText(edgeOnly ? "Side (left or right)" : "Position");
            posLabel.setVisibility(usesPosition ? View.VISIBLE : View.GONE);
            positions.setVisibility(usesPosition ? View.VISIBLE : View.GONE);
            spotLabel.setVisibility(lay.equals("radial") ? View.VISIBLE : View.GONE);
            spots.setVisibility(lay.equals("radial") ? View.VISIBLE : View.GONE);
            bandLabel.setVisibility(usesBand ? View.VISIBLE : View.GONE);
            bands.setVisibility(usesBand ? View.VISIBLE : View.GONE);
            sizeLabel.setVisibility(usesSize ? View.VISIBLE : View.GONE);
            size.setVisibility(usesSize ? View.VISIBLE : View.GONE);
            sizeLabel.setText((lay.equals("radial") || lay.equals("arc") ? "Ring size: "
                    : lay.equals("carousel") ? "Card size: " : "Icon size: ") + Math.round(size.getValue()) + "%");
            boolean full = MenuTheme.POSITIONS[checked(positions)].equals("full");
            boolean usesWidth = usesPosition && !edgeOnly && !full;
            widthLabel.setVisibility(usesWidth ? View.VISIBLE : View.GONE);
            width.setVisibility(usesWidth ? View.VISIBLE : View.GONE);
            widthLabel.setText("Panel width: " + Math.round(width.getValue()) + " dp");
            colsLabel.setVisibility(usesCols ? View.VISIBLE : View.GONE);
            cols.setVisibility(usesCols ? View.VISIBLE : View.GONE);
            colsLabel.setText((lay.equals("freeform") ? "Starting columns (for items you haven't placed): "
                    : "Columns: ") + Math.round(cols.getValue()));
            sections.setVisibility(usesSections ? View.VISIBLE : View.GONE);
        };
        refresh.run();
        layouts.setOnCheckedChangeListener((g, id) -> refresh.run());
        positions.setOnCheckedChangeListener((g, id) -> refresh.run());
        width.addOnChangeListener((s, v, u) -> refresh.run());
        cols.addOnChangeListener((s, v, u) -> refresh.run());
        size.addOnChangeListener((s, v, u) -> refresh.run());
        bands.setOnCheckedChangeListener((g, id) -> refresh.run());

        Runnable save = () -> {
            t.layout = MenuTheme.LAYOUTS[checked(layouts)];
            t.position = MenuTheme.POSITIONS[checked(positions)];
            t.width = Math.round(width.getValue());
            t.columns = Math.round(cols.getValue());
            t.sections = sections.isChecked();
            t.band = MenuTheme.BANDS[checked(bands)];
            t.spot = MenuTheme.SPOTS[checked(spots)];
            t.size = Math.round(size.getValue());
            t.save(a);
        };
        reset.setOnClickListener(v -> {
            MenuTheme.resetArrangement(a);
            t.free.clear();
            Toast.makeText(a, "Order and positions reset", Toast.LENGTH_SHORT).show();
        });

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new DpDialog(a).setTitle("Quick menu look & layout").setView(sv)
                .setPositiveButton("Save", (d, w) -> save.run())
                .setNeutralButton("Save and preview", (d, w) -> {
                    save.run();
                    a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean in(String v, String... options) {
        for (String o : options) if (o.equals(v)) return true;
        return false;
    }

    private static String help(String layout) {
        switch (layout) {
            case "compact": return "Smaller rows without descriptions: more fits on screen.";
            case "grid": return "Large tiles; the D-pad moves in all four directions.";
            case "carousel": return "Big cards in a row; the highlighted one in the middle with its description below. "
                    + "Left/right browse, up/down jump between sections.";
            case "xmb": return "Sections across the top as icons, the chosen section's items running down below it, "
                    + "like a console home screen. Left/right: sections, up/down: items.";
            case "dock": return "A row of icons that grow as the highlight passes, with the name above. Small and out "
                    + "of the way.";
            case "wheel": return "Items on a vertical drum: the middle one is big, the others curve away. Up/down turn "
                    + "it, left/right jump between sections.";
            case "tabs": return "One tab per section (L1/R1 or the row's edge switch tabs), with that section's tiles below.";
            case "rail": return "A thin column of icons at the edge; the highlighted one shows its name beside it.";
            case "radial": return "A wheel in the middle: first one slice per section, then that section's items around it, "
                    + "with the name in the centre. Point the stick or D-pad at a slice; A opens a section, "
                    + "B goes back, L1/R1 jump between sections. Without sections, every item is on the wheel.";
            case "arc": return "A half-ring along the left or right edge, around your thumb. Aim with the stick or move "
                    + "along it with up/down; L1/R1 or left/right switch rings.";
            case "freeform": return "Every item sits where you put it, at the size you choose.";
            default: return "The classic list, with a description under each item.";
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private static RadioGroup radios(Activity a, String[] labels, int sel) {
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

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setPadding(0, dp(a, 14), 0, dp(a, 2));
        return t;
    }

    private static TextView small(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return t;
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
