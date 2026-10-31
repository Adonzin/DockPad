package com.adonzin.dockpad;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.adonzin.dockpad.core.Codes;
import com.adonzin.dockpad.core.Shortcut;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

/** The "record a combo, then choose what it does" flow. */
final class ShortcutEditor {

    interface Host {
        IBridge bridge();
        void runInBackground(Runnable r);
        Prefs prefs();
        void shortcutsChanged();
    }

    private final Activity activity;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    ShortcutEditor(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    interface Recorded {
        void onRecorded(String device, int[] codes);
    }

    // ------------------------------------------------------------ recording

    void record(Recorded then) {
        IBridge b = host.bridge();
        boolean running = false;
        try {
            running = b != null && b.isRunning();
        } catch (Exception ignored) {
        }
        if (!running) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("Turn DockPad on first")
                    .setMessage("Shortcuts are recorded from your controllers, so DockPad needs to be running.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        LinearProgressIndicator progress = new LinearProgressIndicator(activity);
        progress.setIndeterminate(true);
        LinearLayout wrap = new LinearLayout(activity);
        int pad = dp(24);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog waiting = new MaterialAlertDialogBuilder(activity)
                .setTitle("Press your button or combo")
                .setMessage("On the controller you want to use, press one button, or hold several "
                        + "buttons together. Let go to finish.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .show();
        host.runInBackground(() -> {
            String r;
            try {
                r = b.captureCombo(15000);
            } catch (Exception e) {
                r = "";
            }
            String result = r;
            ui.post(() -> {
                if (!waiting.isShowing()) return; // cancelled
                waiting.dismiss();
                if (result.isEmpty()) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle("Nothing detected")
                            .setMessage("No controller sent a button press. If you did press a button, "
                                    + "that button never reaches Android (the controller or the system "
                                    + "keeps it for itself), so it can't be used. Try another button or combo.")
                            .setPositiveButton("Try again", (d, w) -> record(then))
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }
                String[] f = result.split("\t");
                String[] parts = f[1].split(",");
                int[] codes = new int[parts.length];
                for (int i = 0; i < parts.length; i++) codes[i] = Integer.parseInt(parts[i]);
                then.onRecorded(f[0], codes);
            });
        });
    }

    // ------------------------------------------------------------ editing

    void addNew() {
        record((device, codes) -> {
            Shortcut s = new Shortcut();
            s.device = device;
            s.trigger = codes;
            s.steps.add(new int[]{Codes.KEY_HOME});
            edit(s, -1, device);
        });
    }

    void editExisting(int index) {
        List<Shortcut> all = host.prefs().shortcuts();
        if (index < 0 || index >= all.size()) return;
        Shortcut s = all.get(index);
        edit(s.copy(), index, Shortcut.ANY.equals(s.device) ? null : s.device);
    }

    private void edit(Shortcut s, int index, String recordedDevice) {
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_shortcut, null);
        TextView triggerText = v.findViewById(R.id.trigger_text);
        TextView actionText = v.findViewById(R.id.action_text);
        TextView hint = v.findViewById(R.id.hint_text);
        RadioButton scopeDevice = v.findViewById(R.id.scope_device);
        RadioButton scopeAny = v.findViewById(R.id.scope_any);
        RadioButton whenPress = v.findViewById(R.id.when_press);
        RadioButton whenHold = v.findViewById(R.id.when_hold);
        RadioGroup whenGroup = v.findViewById(R.id.when_group);
        RadioGroup scopeGroup = v.findViewById(R.id.scope_group);

        final String[] device = {recordedDevice};
        Runnable refresh = () -> {
            triggerText.setText(s.triggerLabel());
            if (device[0] != null) {
                scopeDevice.setText("Only " + device[0]);
                scopeDevice.setEnabled(true);
            } else {
                scopeDevice.setText("Only the controller it was recorded on");
                scopeDevice.setEnabled(false);
            }
            actionText.setText(s.actionLabel());
            StringBuilder h = new StringBuilder();
            if (s.trigger.length > 1) {
                h.append("In a combo, the earlier buttons still reach the game; the last one you press "
                        + "triggers the shortcut instead. ");
            }
            if (s.hold) h.append("A quick press still works as normal. ");
            else if (s.steps.size() == 1) h.append("The action stays pressed for as long as you hold the button. ");
            if (s.steps.size() > 1) h.append("Macro steps are pressed one after another. ");
            hint.setText(h.toString().trim());
        };

        if (Shortcut.ANY.equals(s.device) || device[0] == null) scopeAny.setChecked(true);
        else scopeDevice.setChecked(true);
        if (s.hold) whenHold.setChecked(true);
        else whenPress.setChecked(true);
        whenGroup.setOnCheckedChangeListener((g, id) -> {
            s.hold = id == R.id.when_hold;
            refresh.run();
        });
        scopeGroup.setOnCheckedChangeListener((g, id) -> refresh.run());

        MaterialButton rerecord = v.findViewById(R.id.rerecord);
        rerecord.setOnClickListener(x -> record((dev, codes) -> {
            s.trigger = codes;
            device[0] = dev;
            if (!scopeAny.isChecked()) scopeDevice.setChecked(true);
            refresh.run();
        }));

        MaterialButton choose = v.findViewById(R.id.choose_action);
        choose.setOnClickListener(x -> new MaterialAlertDialogBuilder(activity)
                .setTitle("What should it do?")
                .setItems(Codes.OUTPUT_LABELS, (d, which) -> {
                    s.steps.clear();
                    int code = Codes.OUTPUT_CODES[which];
                    if (code >= 0) s.steps.add(new int[]{code});
                    refresh.run();
                })
                .show());

        MaterialButton macro = v.findViewById(R.id.build_macro);
        macro.setOnClickListener(x -> buildMacro(s.steps, steps -> {
            s.steps.clear();
            s.steps.addAll(steps);
            refresh.run();
        }));

        refresh.run();

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
                .setTitle(index < 0 ? "New shortcut" : "Edit shortcut")
                .setView(v)
                .setPositiveButton("Save", (d, w) -> {
                    s.device = scopeAny.isChecked() || device[0] == null ? Shortcut.ANY : device[0];
                    save(s, index);
                })
                .setNegativeButton("Cancel", null);
        if (index >= 0) b.setNeutralButton("Delete", (d, w) -> delete(index));
        b.show();
    }

    private void save(Shortcut s, int index) {
        List<Shortcut> all = host.prefs().shortcuts();
        if (index >= 0 && index < all.size()) all.remove(index);
        // one shortcut per trigger: a new one replaces an older one with the same buttons
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).sameTrigger(s)) all.remove(i);
        all.add(index >= 0 && index <= all.size() ? index : all.size(), s);
        host.prefs().shortcuts(all);
        host.shortcutsChanged();
    }

    private void delete(int index) {
        List<Shortcut> all = host.prefs().shortcuts();
        if (index >= 0 && index < all.size()) all.remove(index);
        host.prefs().shortcuts(all);
        host.shortcutsChanged();
    }

    // ------------------------------------------------------------ macro builder

    interface StepsResult {
        void onSteps(List<int[]> steps);
    }

    private void buildMacro(List<int[]> initial, StepsResult result) {
        List<int[]> steps = new ArrayList<>();
        for (int[] st : initial) steps.add(st.clone());

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        TextView list = new TextView(activity);
        list.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        box.addView(list);
        TextView explain = new TextView(activity);
        explain.setText("Each step presses one or more buttons together, then lets go. "
                + "Steps run one after another, about a tenth of a second apart.");
        explain.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        explain.setPadding(0, dp(8), 0, dp(8));
        box.addView(explain);
        LinearLayout row = new LinearLayout(activity);
        MaterialButton add = new MaterialButton(activity);
        add.setText("Add step");
        MaterialButton undo = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        undo.setText("Remove last");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        row.addView(add, lp);
        row.addView(undo);
        box.addView(row);

        Runnable refresh = () -> {
            if (steps.isEmpty()) {
                list.setText("No steps yet.");
            } else {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < steps.size(); i++) {
                    b.append(i + 1).append(". ").append(Shortcut.stepLabel(steps.get(i))).append('\n');
                }
                list.setText(b.toString().trim());
            }
            undo.setEnabled(!steps.isEmpty());
        };
        refresh.run();

        String[] labels = new String[Codes.OUTPUT_LABELS.length - 1]; // without "Do nothing"
        System.arraycopy(Codes.OUTPUT_LABELS, 0, labels, 0, labels.length);
        add.setOnClickListener(x -> {
            boolean[] checked = new boolean[labels.length];
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("Buttons to press together")
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Add", (d, w) -> {
                        List<Integer> sel = new ArrayList<>();
                        for (int i = 0; i < labels.length; i++) if (checked[i]) sel.add(Codes.OUTPUT_CODES[i]);
                        if (sel.isEmpty()) return;
                        int[] st = new int[sel.size()];
                        for (int i = 0; i < st.length; i++) st[i] = sel.get(i);
                        steps.add(st);
                        refresh.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        undo.setOnClickListener(x -> {
            if (!steps.isEmpty()) steps.remove(steps.size() - 1);
            refresh.run();
        });

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Macro")
                .setView(box)
                .setPositiveButton("Done", (d, w) -> result.onSteps(steps))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
