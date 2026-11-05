package com.adonzin.dockpad;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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

        MaterialSwitch ab = new MaterialSwitch(activity);
        ab.setText("Swap A and B");
        ab.setChecked(layout.swapAB);
        box.addView(ab);
        MaterialSwitch xy = new MaterialSwitch(activity);
        xy.setText("Swap X and Y");
        xy.setChecked(layout.swapXY);
        box.addView(xy);

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
                .setTitle(device)
                .setView(sv)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, w) -> {
                    layout.swapAB = layout.swapXY = false;
                    layout.custom.clear();
                    save(layout);
                })
                .show();
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
