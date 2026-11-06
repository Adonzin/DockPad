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

        TextView widthLabel = label(a, "");
        box.addView(widthLabel);
        Slider width = new Slider(a);
        width.setValueFrom(240);
        width.setValueTo(900);
        width.setStepSize(20);
        width.setValue(Math.max(240, Math.min(900, Math.round(t.width / 20f) * 20)));
        box.addView(width);

        TextView colsLabel = label(a, "");
        box.addView(colsLabel);
        Slider cols = new Slider(a);
        cols.setValueFrom(2);
        cols.setValueTo(6);
        cols.setStepSize(1);
        cols.setValue(Math.max(2, Math.min(6, t.columns)));
        box.addView(cols);

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
            boolean radial = lay.equals("radial"), carousel = lay.equals("carousel");
            switch (lay) {
                case "compact": layoutHelp.setText("Smaller rows without descriptions: more fits on screen."); break;
                case "grid": layoutHelp.setText("Large tiles; the D-pad moves in all four directions."); break;
                case "radial": layoutHelp.setText("Items on a ring in the middle. Aim with the stick, or go around with "
                        + "the D-pad. Many items make several rings (D-pad up/down between them)."); break;
                case "freeform": layoutHelp.setText("Every item sits where you put it, at the size you choose."); break;
                case "carousel": layoutHelp.setText("Big cards in a row; the highlighted one in the middle with its "
                        + "description below. Left/right browse, up/down jump between sections."); break;
                default: layoutHelp.setText("The classic list, with a description under each item.");
            }
            boolean side = !radial && !carousel;
            posLabel.setVisibility(side ? View.VISIBLE : View.GONE);
            positions.setVisibility(side ? View.VISIBLE : View.GONE);
            bandLabel.setVisibility(carousel ? View.VISIBLE : View.GONE);
            bands.setVisibility(carousel ? View.VISIBLE : View.GONE);
            sizeLabel.setVisibility(side ? View.GONE : View.VISIBLE);
            size.setVisibility(side ? View.GONE : View.VISIBLE);
            sizeLabel.setText((radial ? "Ring size: " : "Card size: ") + Math.round(size.getValue()) + "% of the screen");
            boolean full = MenuTheme.POSITIONS[checked(positions)].equals("full");
            widthLabel.setVisibility(side && !full ? View.VISIBLE : View.GONE);
            width.setVisibility(side && !full ? View.VISIBLE : View.GONE);
            widthLabel.setText("Panel width: " + Math.round(width.getValue()) + " dp");
            boolean usesCols = lay.equals("grid") || lay.equals("freeform");
            colsLabel.setVisibility(usesCols ? View.VISIBLE : View.GONE);
            cols.setVisibility(usesCols ? View.VISIBLE : View.GONE);
            colsLabel.setText((lay.equals("freeform") ? "Starting columns (for items you haven't placed): "
                    : "Columns: ") + Math.round(cols.getValue()));
            sections.setVisibility(radial || carousel || lay.equals("freeform") ? View.GONE : View.VISIBLE);
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
