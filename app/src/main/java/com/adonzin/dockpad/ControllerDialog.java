package com.adonzin.dockpad;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.ControllerLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Map;

/** Fix a controller's button layout: swap A/B, swap X/Y, change individual buttons. */
final class ControllerDialog {
    interface Host {
        IBridge bridge();
        void runInBackground(Runnable r);
        Prefs prefs();
        void layoutsChanged();
    }

    private final Activity activity;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    ControllerDialog(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void show(String device) {
        ControllerLayout layout = host.prefs().layout(device);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView intro = text("If buttons come out in the wrong place (for example a PlayStation or Nintendo "
                + "controller acting like the wrong layout), fix it here. Changes apply right away.", true);
        box.addView(intro);

        MaterialButton rename = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        rename.setText(host.prefs().isRenamed(device) ? "Name: " + host.prefs().controllerName(device) + " (change…)"
                : "Give it a name…");
        rename.setOnClickListener(v -> {
            android.widget.EditText e = new android.widget.EditText(activity);
            e.setText(host.prefs().controllerName(device));
            e.setSelectAllOnFocus(true);
            e.setSingleLine(true);
            android.widget.FrameLayout fb = new android.widget.FrameLayout(activity);
            fb.setPadding(dp(24), dp(8), dp(24), 0);
            fb.addView(e);
            new DpDialog(activity).setTitle("Name for " + device)
                    .setMessage("Shown in DockPad and the quick menu, e.g. “Red PS4”. Leave empty for its own name. "
                            + "(Controllers of the same model share a name.)")
                    .setView(fb)
                    .setPositiveButton("Save", (d, w) -> {
                        String n = e.getText().toString().trim();
                        if (n.length() > 40) n = n.substring(0, 40);
                        host.prefs().controllerName(device, n);
                        rename.setText(host.prefs().isRenamed(device) ? "Name: " + host.prefs().controllerName(device)
                                + " (change…)" : "Give it a name…");
                        host.layoutsChanged();
                    })
                    .setNegativeButton("Cancel", null).show();
            e.requestFocus();
        });
        box.addView(rename);

        MaterialSwitch ab = new MaterialSwitch(activity);
        ab.setForeground(activity.getDrawable(R.drawable.focus_ring));
        ab.setPadding(dp(6), dp(10), dp(6), dp(10));
        ab.setText("Swap A and B");
        ab.setChecked(layout.swapAB);
        box.addView(ab);
        MaterialSwitch xy = new MaterialSwitch(activity);
        xy.setForeground(activity.getDrawable(R.drawable.focus_ring));
        xy.setPadding(dp(6), dp(10), dp(6), dp(10));
        xy.setText("Swap X and Y");
        xy.setChecked(layout.swapXY);
        box.addView(xy);

        MaterialButton auto = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        auto.setText("Auto-map: match the button labels");
        box.addView(auto);
        TextView autoHint = text(ControllerLayout.hasNintendoLabels(device)
                ? "This looks like a Nintendo-style controller (B at the bottom, A on the right). Auto-map swaps "
                + "A/B and X/Y so the button labelled A acts as A. The default matches buttons by position instead."
                : "This looks like an Xbox or PlayStation-style controller: the standard layout already matches.", true);
        box.addView(autoHint);
        auto.setOnClickListener(v -> {
            layout.autoMapByLabels();
            ab.setChecked(layout.swapAB);
            xy.setChecked(layout.swapXY);
            save(layout);
            Toast.makeText(activity, layout.swapAB ? "A/B and X/Y swapped to match the labels"
                    : "Standard layout (already matches)", Toast.LENGTH_SHORT).show();
        });

        TextView turboTitle = text("Rapid fire", false);
        turboTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        turboTitle.setPadding(0, dp(16), 0, dp(4));
        box.addView(turboTitle);
        box.addView(text("While held, these buttons press repeatedly. Turn all rapid fire on or off from the "
                + "quick menu.", true));
        MaterialButton turboPick = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        box.addView(turboPick);
        TextView rateLabel = text("", false);
        box.addView(rateLabel);
        com.google.android.material.slider.Slider rate = new com.google.android.material.slider.Slider(activity);
        rate.setValueFrom(4);
        rate.setValueTo(20);
        rate.setStepSize(1);
        rate.setValue(Math.max(4, Math.min(20, layout.turboRate)));
        box.addView(rate);
        Runnable turboRefresh = () -> {
            StringBuilder t = new StringBuilder();
            for (int c : layout.turbo) t.append(t.length() == 0 ? "" : ", ").append(ControllerLayout.targetLabel(c));
            turboPick.setText(layout.turbo.isEmpty() ? "Choose rapid-fire buttons" : "Rapid fire: " + t);
            rateLabel.setText("Speed: " + layout.turboRate + " presses a second");
        };
        turboRefresh.run();
        rate.addOnChangeListener((sl, value, fromUser) -> {
            if (!fromUser) return;
            layout.turboRate = Math.round(value);
            save(layout);
            turboRefresh.run();
        });
        turboPick.setOnClickListener(v -> {
            String[] labels = ControllerLayout.TURBO_LABELS;
            boolean[] checked = new boolean[labels.length];
            for (int i = 0; i < labels.length; i++) checked[i] = layout.turbo.contains(ControllerLayout.TURBO_CODES[i]);
            new DpDialog(activity)
                    .setTitle("Rapid-fire buttons")
                    .setMultiChoiceItems(labels, checked, (d, i, c) -> checked[i] = c)
                    .setPositiveButton("Save", (d, w) -> {
                        layout.turbo.clear();
                        for (int i = 0; i < labels.length; i++) if (checked[i]) layout.turbo.add(ControllerLayout.TURBO_CODES[i]);
                        save(layout);
                        turboRefresh.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        TextView tuneTitle = text("Sticks and triggers", false);
        tuneTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        tuneTitle.setPadding(0, dp(16), 0, dp(4));
        box.addView(tuneTitle);
        TextView tuneSummary = text("", true);
        box.addView(tuneSummary);
        MaterialButton tune = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        tune.setText("Fine-tune…");
        box.addView(tune);
        Runnable tuneRefresh = () -> tuneSummary.setText(layout.tuning.isDefault()
                ? "Standard: dead zone, stick response and trigger press point as the controller sends them."
                : layout.tuning.summary().substring(0, 1).toUpperCase() + layout.tuning.summary().substring(1) + ".");
        tuneRefresh.run();
        tune.setOnClickListener(v -> showTuning(layout, tuneRefresh));

        TextView customTitle = text("Changed buttons", false);
        customTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        customTitle.setPadding(0, dp(16), 0, dp(4));
        box.addView(customTitle);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        MaterialButton change = new MaterialButton(activity);
        change.setText("Change a button");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.addView(change, lp);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            if (layout.custom.isEmpty()) {
                list.addView(text("None. Use \"Change a button\" to pick one.", true));
            }
            for (Map.Entry<Integer, Integer> e : layout.custom.entrySet()) {
                int from = e.getKey();
                MaterialButton row = new MaterialButton(activity, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
                row.setAllCaps(false);
                row.setText(Codes.keyName(from) + "  →  " + ControllerLayout.targetLabel(e.getValue()) + "   (tap to remove)");
                row.setOnClickListener(v -> {
                    layout.custom.remove(from);
                    save(layout);
                    refresh[0].run();
                });
                list.addView(row);
            }
        };
        refresh[0].run();

        ab.setOnCheckedChangeListener((b, c) -> {
            layout.swapAB = c;
            save(layout);
        });
        xy.setOnCheckedChangeListener((b, c) -> {
            layout.swapXY = c;
            save(layout);
        });
        change.setOnClickListener(v -> recordButton(device, code ->
                new DpDialog(activity)
                        .setTitle(Codes.keyName(code) + " should act as…")
                        .setItems(ControllerLayout.TARGET_LABELS, (d, which) -> {
                            int to = ControllerLayout.TARGET_CODES[which];
                            if (to == code) layout.custom.remove(code);
                            else layout.custom.put(code, to);
                            save(layout);
                            refresh[0].run();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()));

        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(box);
        new DpDialog(activity)
                .setTitle(host.prefs().controllerName(device))
                .setView(sv)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset to default", (d, w) -> {
                    layout.reset();
                    save(layout);
                    Toast.makeText(activity, "Back to the standard layout", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ------------------------------------------------------------------ fine-tuning

    private final Runnable[] pendingSave = new Runnable[1];

    /** Saves after the slider settles, so dragging doesn't flood DockPad with updates. */
    private void saveSoon(ControllerLayout l) {
        if (pendingSave[0] != null) ui.removeCallbacks(pendingSave[0]);
        pendingSave[0] = () -> save(l);
        ui.postDelayed(pendingSave[0], 250);
    }

    private void showTuning(ControllerLayout layout, Runnable onChange) {
        com.adonzin.dockpad.core.Tuning t = layout.tuning;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(text("Changes apply right away, so you can try them in a game or in Test controllers.", true));

        TextView dzLabel = text("", false);
        box.addView(dzLabel);
        com.google.android.material.slider.Slider dz = slider(0, 40, 1, t.deadZone);
        box.addView(dz);
        box.addView(text("Ignores small stick movements. Raise it if a character or camera drifts on its own.", true));

        TextView respLabel = text("", false);
        box.addView(respLabel);
        int ri = 0;
        for (int i = 0; i < com.adonzin.dockpad.core.Tuning.RESPONSES.length; i++) {
            if (com.adonzin.dockpad.core.Tuning.RESPONSES[i] == t.response) ri = i;
        }
        com.google.android.material.slider.Slider resp = slider(0, com.adonzin.dockpad.core.Tuning.RESPONSES.length - 1, 1, ri);
        box.addView(resp);
        box.addView(text("Quick: small tilts already move a lot. Precise: more control for small, careful movements "
                + "(aiming, camera).", true));

        TextView tpLabel = text("", false);
        box.addView(tpLabel);
        com.google.android.material.slider.Slider tp = slider(5, 95, 5, Math.round(t.triggerPoint / 5f) * 5);
        box.addView(tp);
        box.addView(text("How far L2/R2 must be pulled to count as a press. Lower is a hair trigger. Only for "
                + "controllers with analog triggers; the analog pull itself is unchanged.", true));

        Runnable labels = () -> {
            dzLabel.setText("Stick dead zone: " + (t.deadZone == 0 ? "controller's own" : t.deadZone + "%"));
            respLabel.setText("Stick response: " + com.adonzin.dockpad.core.Tuning.responseLabel(t.response));
            tpLabel.setText("Trigger press point: " + t.triggerPoint + "%" + (t.triggerPoint == 50 ? " (standard)" : ""));
        };
        labels.run();
        Runnable changed = () -> {
            labels.run();
            onChange.run();
            saveSoon(layout);
        };
        dz.addOnChangeListener((sl, v, user) -> {
            if (!user) return;
            t.deadZone = Math.round(v);
            changed.run();
        });
        resp.addOnChangeListener((sl, v, user) -> {
            if (!user) return;
            t.response = com.adonzin.dockpad.core.Tuning.RESPONSES[Math.round(v)];
            changed.run();
        });
        tp.addOnChangeListener((sl, v, user) -> {
            if (!user) return;
            t.triggerPoint = Math.round(v);
            changed.run();
        });

        MaterialSwitch swap = toggle(box, "Swap left and right sticks", t.swapSticks);
        MaterialSwitch invL = toggle(box, "Invert left stick up/down", t.invertLeftY);
        MaterialSwitch invR = toggle(box, "Invert right stick up/down", t.invertRightY);
        MaterialSwitch dpad = toggle(box, "Left stick works as the D-pad", t.leftStickDpad);
        box.addView(text("Handy for retro games and menus that only read the D-pad. The left stick stops acting as a "
                + "stick while this is on.", true));
        swap.setOnCheckedChangeListener((b, c) -> { t.swapSticks = c; changed.run(); });
        invL.setOnCheckedChangeListener((b, c) -> { t.invertLeftY = c; changed.run(); });
        invR.setOnCheckedChangeListener((b, c) -> { t.invertRightY = c; changed.run(); });
        dpad.setOnCheckedChangeListener((b, c) -> { t.leftStickDpad = c; changed.run(); });

        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(box);
        new DpDialog(activity)
                .setTitle("Fine-tune " + layout.device)
                .setView(sv)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset sticks and triggers", (d, w) -> {
                    t.reset();
                    onChange.run();
                    save(layout);
                })
                .show();
    }

    private com.google.android.material.slider.Slider slider(float from, float to, float step, float value) {
        com.google.android.material.slider.Slider s = new com.google.android.material.slider.Slider(activity);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(step);
        s.setValue(Math.max(from, Math.min(to, value)));
        s.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        return s;
    }

    private MaterialSwitch toggle(LinearLayout box, String label, boolean value) {
        MaterialSwitch sw = new MaterialSwitch(activity);
        sw.setForeground(activity.getDrawable(R.drawable.focus_ring));
        sw.setPadding(dp(6), dp(10), dp(6), dp(10));
        sw.setText(label);
        sw.setChecked(value);
        box.addView(sw);
        return sw;
    }

    private void save(ControllerLayout l) {
        host.prefs().saveLayout(l);
        host.layoutsChanged();
    }

    interface CodeResult {
        void onCode(int code);
    }

    private void recordButton(String device, CodeResult then) {
        IBridge b = host.bridge();
        if (b == null) return;
        LinearProgressIndicator progress = new LinearProgressIndicator(activity);
        progress.setIndeterminate(true);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog waiting = new DpDialog(activity).ignoreControllerButtons()
                .setTitle("Press the button to change")
                .setMessage("Press it on " + device + ".")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .show();
        host.runInBackground(() -> {
            String r;
            try {
                r = b.captureCombo(15000, true);
            } catch (Exception e) {
                r = "";
            }
            String result = r;
            ui.post(() -> {
                if (!waiting.isShowing()) return;
                waiting.dismiss();
                String[] f = result.split("\t");
                if (f.length != 2 || !f[0].equals(device)) {
                    new DpDialog(activity)
                            .setMessage(result.isEmpty() ? "No button press detected." :
                                    "That press came from a different controller (" + f[0] + ").")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                then.onCode(Integer.parseInt(f[1].split(",")[0]));
            });
        });
    }

    private TextView text(String s, boolean small) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextAppearance(small ? com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
