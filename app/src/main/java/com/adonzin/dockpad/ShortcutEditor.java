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
        /** Last known on/off state (never asks the service from the UI thread). */
        boolean bridgeRunning();
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
        if (b == null || !host.bridgeRunning()) {
            new DpDialog(activity)
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
        AlertDialog waiting = new DpDialog(activity).ignoreControllerButtons()
                .setTitle("Press your button or combo")
                .setMessage("On the controller you want to use, press one button, or hold several "
                        + "buttons together. Let go to finish.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .show();
        host.runInBackground(() -> {
            String r;
            try {
                r = b.captureCombo(15000, false);
            } catch (Exception e) {
                r = "";
            }
            String result = r;
            ui.post(() -> {
                if (!waiting.isShowing()) return; // cancelled
                waiting.dismiss();
                if (result.isEmpty()) {
                    new DpDialog(activity)
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
        addNewWithAction(Codes.KEY_HOME);
    }

    /** Record buttons, with the action already filled in (e.g. "Toggle Cursor mode"). */
    void addNewWithAction(int action) {
        record((device, codes) -> {
            Shortcut s = new Shortcut();
            s.device = device;
            s.trigger = codes;
            s.steps.add(new int[]{action});
            edit(s, -1, device);
        });
    }

    // ------------------------------------------------------------ quick menu commands

    void addCommand() {
        Shortcut c = new Shortcut();
        c.device = "";
        c.trigger = new int[]{0};
        editCommand(c, -1);
    }

    void editCommand(int index) {
        List<Shortcut> all = host.prefs().menuCommands();
        if (index >= 0 && index < all.size()) editCommand(all.get(index).copy(), index);
    }

    private void editCommand(Shortcut c, int index) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        com.google.android.material.textfield.TextInputLayout nameLayout =
                new com.google.android.material.textfield.TextInputLayout(activity);
        nameLayout.setHint("Name in the quick menu");
        com.google.android.material.textfield.TextInputEditText name =
                new com.google.android.material.textfield.TextInputEditText(nameLayout.getContext());
        name.setSingleLine(true);
        name.setText(c.device);
        nameLayout.addView(name);
        box.addView(nameLayout);
        TextView action = new TextView(activity);
        action.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        action.setPadding(0, dp(16), 0, dp(8));
        box.addView(action);
        LinearLayout row = new LinearLayout(activity);
        MaterialButton choose = new MaterialButton(activity);
        choose.setText("Choose action");
        MaterialButton macro = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        macro.setText("Build macro");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        row.addView(choose, lp);
        row.addView(macro);
        box.addView(row);

        Runnable refresh = () -> action.setText(c.steps.isEmpty() ? "No action yet" : c.actionLabel());
        refresh.run();
        choose.setOnClickListener(x -> pickOutputs(c, false, codes -> {
            c.steps.clear();
            if (codes.length > 0) c.steps.add(codes);
            if (String.valueOf(name.getText()).trim().isEmpty() && codes.length > 0) name.setText(c.actionLabel());
            refresh.run();
        }));
        macro.setOnClickListener(x -> buildMacro(c, steps -> {
            c.steps.clear();
            c.steps.addAll(steps);
            refresh.run();
        }));

        DpDialog b = new DpDialog(activity);
        b.setTitle(index < 0 ? "New quick menu command" : "Edit command")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = String.valueOf(name.getText()).replace("\t", " ").trim();
                    if (c.steps.isEmpty()) return;
                    c.device = n.isEmpty() ? c.actionLabel() : n;
                    List<Shortcut> all = host.prefs().menuCommands();
                    if (index >= 0 && index < all.size()) all.set(index, c);
                    else all.add(c);
                    host.prefs().menuCommands(all);
                    host.shortcutsChanged();
                })
                .setNegativeButton("Cancel", null);
        if (index >= 0) b.setNeutralButton("Delete", (d, w) -> {
            List<Shortcut> all = host.prefs().menuCommands();
            if (index < all.size()) all.remove(index);
            host.prefs().menuCommands(all);
            host.shortcutsChanged();
        });
        b.show();
    }

    // ------------------------------------------------------------ choosing outputs

    interface Codes1 {
        void onCodes(int[] codes);
    }

    /**
     * Category first (controller button / keyboard key / system action), then the item.
     * With multi = true, several buttons or keys can be pressed together.
     */
    private void pickOutputs(Shortcut s, boolean multi, Codes1 then) {
        String[] cats = multi ? Codes.CATEGORIES
                : new String[]{Codes.CATEGORIES[0], Codes.CATEGORIES[1], Codes.CATEGORIES[2], "Do nothing (block)"};
        new DpDialog(activity)
                .setTitle("What should it do?")
                .setItems(cats, (d, cat) -> {
                    if (cat == 3) {
                        then.onCodes(new int[0]);
                        return;
                    }
                    String[] labels = Codes.CAT_LABELS[cat];
                    int[] codes = Codes.CAT_CODES[cat];
                    if (cat == 2 || !multi) {
                        new DpDialog(activity)
                                .setTitle(Codes.CATEGORIES[cat])
                                .setItems(labels, (d2, i) -> {
                                    if (codes[i] == Codes.ACTION_OPEN_APP) {
                                        pickApp(app -> {
                                            s.param = app;
                                            then.onCodes(new int[]{codes[i]});
                                        });
                                    } else {
                                        then.onCodes(new int[]{codes[i]});
                                    }
                                })
                                .setNegativeButton("Back", (d2, w) -> pickOutputs(s, multi, then))
                                .show();
                        return;
                    }
                    boolean[] checked = new boolean[labels.length];
                    new DpDialog(activity)
                            .setTitle("Press together (pick one or more)")
                            .setMultiChoiceItems(labels, checked, (d2, i, c) -> checked[i] = c)
                            .setPositiveButton("Add", (d2, w) -> {
                                List<Integer> sel = new ArrayList<>();
                                for (int i = 0; i < labels.length; i++) if (checked[i]) sel.add(codes[i]);
                                if (sel.isEmpty()) return;
                                int[] out = new int[sel.size()];
                                for (int i = 0; i < out.length; i++) out[i] = sel.get(i);
                                then.onCodes(out);
                            })
                            .setNegativeButton("Back", (d2, w) -> pickOutputs(s, multi, then))
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    interface AppResult {
        void onApp(String packageAndLabel);
    }

    private void pickApp(AppResult then) {
        android.content.pm.PackageManager pm = activity.getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        List<String[]> list = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName())) continue;
            boolean dup = false;
            for (String[] e : list) if (e[0].equals(pkg)) dup = true;
            if (!dup) list.add(new String[]{pkg, String.valueOf(r.loadLabel(pm))});
        }
        list.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i)[1];
        new DpDialog(activity)
                .setTitle("Open which app?")
                .setItems(names, (d, i) -> then.onApp(list.get(i)[0] + "|" + list.get(i)[1]))
                .setNegativeButton("Cancel", null)
                .show();
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
        TextView holdLabel = v.findViewById(R.id.hold_label);
        com.google.android.material.slider.Slider holdTime = v.findViewById(R.id.hold_time);
        holdTime.setValue(Math.max(0.3f, Math.min(10f, Math.round(s.holdMs / 100f) / 10f)));
        holdTime.addOnChangeListener((sl, value, fromUser) -> {
            s.holdMs = Math.round(value * 1000);
            holdLabel.setText("Hold for " + MainActivity.holdText(s.holdMs));
        });

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
            holdLabel.setVisibility(s.hold ? View.VISIBLE : View.GONE);
            holdTime.setVisibility(s.hold ? View.VISIBLE : View.GONE);
            holdLabel.setText("Hold for " + MainActivity.holdText(s.holdMs));
            StringBuilder h = new StringBuilder();
            if (s.trigger.length > 1) {
                h.append("In a combo, the earlier buttons still reach the game; the last one you press "
                        + "triggers the shortcut instead. ");
            }
            if (s.hold) h.append("A quick press still works as normal. You can give the same buttons more "
                    + "hold shortcuts with other hold times (e.g. 1 s for Back, 3 s for Home): letting go runs the "
                    + "last one reached. ");
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
        choose.setOnClickListener(x -> pickOutputs(s, false, codes -> {
            s.steps.clear();
            if (codes.length > 0) s.steps.add(codes);
            refresh.run();
        }));

        MaterialButton macro = v.findViewById(R.id.build_macro);
        macro.setOnClickListener(x -> buildMacro(s, steps -> {
            s.steps.clear();
            s.steps.addAll(steps);
            refresh.run();
        }));

        refresh.run();

        MaterialAlertDialogBuilder b = new DpDialog(activity)
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

    private void buildMacro(Shortcut s, StepsResult result) {
        List<int[]> steps = new ArrayList<>();
        for (int[] st : s.steps) steps.add(st.clone());

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
                    b.append(i + 1).append(". ").append(s.stepLabel(steps.get(i))).append('\n');
                }
                list.setText(b.toString().trim());
            }
            undo.setEnabled(!steps.isEmpty());
        };
        refresh.run();

        add.setOnClickListener(x -> pickOutputs(s, true, codes -> {
            if (codes.length == 0) return;
            steps.add(codes);
            refresh.run();
        }));
        undo.setOnClickListener(x -> {
            if (!steps.isEmpty()) steps.remove(steps.size() - 1);
            refresh.run();
        });

        new DpDialog(activity)
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
