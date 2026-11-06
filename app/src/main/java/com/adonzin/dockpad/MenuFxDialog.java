package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

/** Animation, sounds, music and feedback for the quick menu. */
final class MenuFxDialog {
    /** Picks an audio file and imports it into DockPad's media; calls back with its name. */
    interface AudioImporter {
        void pick(java.util.function.Consumer<String> done);
    }

    private final Activity a;
    private final AudioImporter importer;
    private final MenuTheme theme;
    private final MenuFx fx;
    private LinearLayout box;
    private SoundPool previewPool;
    private MediaPlayer previewMusic;

    private MenuFxDialog(Activity a, AudioImporter importer) {
        this.a = a;
        this.importer = importer;
        this.theme = MenuTheme.load(a);
        this.fx = theme.fx;
    }

    static void show(Activity a, AudioImporter importer) {
        new MenuFxDialog(a, importer).open();
    }

    private void open() {
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        previewPool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();
        previewPool.setOnLoadCompleteListener((p, id, st) -> {
            if (st == 0) p.play(id, fx.volume / 100f, fx.volume / 100f, 1, 0, 1f);
        });
        build();
        androidx.appcompat.app.AlertDialog d = new DpDialog(a).setTitle("Quick menu animation & sound").setView(sv)
                .setPositiveButton("Save", (x, w) -> theme.save(a))
                .setNeutralButton("Save and preview", (x, w) -> {
                    theme.save(a);
                    a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(x -> {
            if (previewPool != null) previewPool.release();
            stopMusicPreview();
        });
        d.show();
    }

    private void build() {
        int focusIndex = -1;
        java.util.ArrayList<View> before = box.getFocusables(View.FOCUS_FORWARD);
        for (int i = 0; i < before.size(); i++) if (before.get(i).isFocused()) focusIndex = i;
        box.removeAllViews();

        label("Opening");
        radios(MenuFx.OPEN_LABELS, indexOf(MenuFx.OPENS, fx.open), i -> fx.open = MenuFx.OPENS[i]);
        label("Closing");
        radios(MenuFx.OPEN_LABELS, indexOf(MenuFx.OPENS, fx.close), i -> fx.close = MenuFx.OPENS[i]);
        label("Speed");
        radios(MenuFx.SPEED_LABELS, indexOf(MenuFx.SPEEDS, fx.speed), i -> fx.speed = MenuFx.SPEEDS[i]);
        label("Items appear");
        radios(MenuFx.ITEM_LABELS, indexOf(MenuFx.ITEMS, fx.items), i -> fx.items = MenuFx.ITEMS[i]);
        label("When the highlight moves");
        radios(MenuFx.HIGHLIGHT_LABELS, indexOf(MenuFx.HIGHLIGHTS, fx.highlight), i -> fx.highlight = MenuFx.HIGHLIGHTS[i]);
        slider("Animation strength", 10, 200, 10, fx.intensity, v -> fx.intensity = v,
                v -> v + "%" + (v < 60 ? " (subtle)" : v > 140 ? " (wild)" : ""));

        label("Sounds");
        small("Pick a pack, then change any single sound if you like. Selecting a sound plays it.");
        radios(MenuFx.PACK_LABELS, indexOf(MenuFx.PACKS, fx.pack), i -> {
            fx.pack = MenuFx.PACKS[i];
            fx.sounds.clear(); // a new pack replaces single changes
            if (!fx.pack.equals("off")) preview("pack:" + fx.pack, "select");
            build();
        });
        for (int i = 0; i < MenuFx.EVENTS.length; i++) {
            String ev = MenuFx.EVENTS[i];
            soundRow(MenuFx.EVENT_LABELS[i], ev);
        }
        slider("Sound volume", 0, 100, 5, fx.volume, v -> fx.volume = v, v -> v + "%");

        label("Music while the menu is open");
        TextView now = small(fx.music.isEmpty() ? "None." : "Now: " + fx.music);
        LinearLayout mb = new LinearLayout(a);
        mb.addView(button("Choose a music file…", () -> importer.pick(name -> {
            fx.music = name;
            build();
        })));
        if (MenuMedia.listAudio(a).length > 0) mb.addView(button("One I added before…", () -> chooseAudio("Music", name -> {
            fx.music = name;
            build();
        })));
        if (!fx.music.isEmpty()) {
            mb.addView(button("Listen", this::previewMusic));
            mb.addView(button("No music", () -> {
                fx.music = "";
                stopMusicPreview();
                build();
            }));
        }
        box.addView(mb);
        slider("Music volume", 0, 100, 5, fx.musicVolume, v -> {
            fx.musicVolume = v;
            if (previewMusic != null) previewMusic.setVolume(v / 100f, v / 100f);
        }, v -> v + "%");

        label("Handheld rumble");
        small("A tiny buzz of the handheld as you use the menu.");
        radios(MenuFx.HAPTIC_LABELS, indexOf(MenuFx.HAPTICS, fx.haptics), i -> fx.haptics = MenuFx.HAPTICS[i]);
        slider("Strength", 5, 100, 5, fx.hapticStrength, v -> fx.hapticStrength = v, v -> v + "%");

        if (focusIndex >= 0) {
            java.util.ArrayList<View> after = box.getFocusables(View.FOCUS_FORWARD);
            if (focusIndex < after.size()) {
                View again = after.get(focusIndex);
                again.post(again::requestFocus);
            }
        }
    }

    /** One event's sound: shows what plays, and lets you pick another. */
    private void soundRow(String name, String event) {
        String spec = fx.soundFor(event);
        String shown = spec.isEmpty() ? "silent" : spec.startsWith("pack:") ? packLabel(spec.substring(5))
                : spec.substring(5);
        LinearLayout row = new LinearLayout(a);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setForeground(a.getDrawable(R.drawable.focus_ring));
        TextView t = new TextView(a);
        t.setText(name + ":  " + shown + (fx.sounds.containsKey(event) ? "  (changed)" : ""));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        row.addView(t);
        row.setOnClickListener(v -> pickSound(name, event));
        box.addView(row);
    }

    private void pickSound(String name, String event) {
        java.util.List<String> specs = new java.util.ArrayList<>(), labels = new java.util.ArrayList<>();
        specs.add("");
        labels.add("Silent");
        for (int i = 1; i < MenuFx.PACKS.length; i++) {
            specs.add("pack:" + MenuFx.PACKS[i]);
            labels.add(MenuFx.PACK_LABELS[i] + " pack's sound");
        }
        for (String f : MenuMedia.listAudio(a)) {
            specs.add("file:" + f);
            labels.add(f);
        }
        specs.add("import");
        labels.add("Add a sound file…");
        int sel = specs.indexOf(fx.soundFor(event));
        android.app.Dialog[] d = new android.app.Dialog[1];
        d[0] = new DpDialog(a).setTitle(name + " sound")
                .setSingleChoiceItems(labels.toArray(new String[0]), sel, (x, w) -> {
                    String sp = specs.get(w);
                    if (sp.equals("import")) {
                        x.dismiss();
                        importer.pick(f -> {
                            fx.sounds.put(event, "file:" + f);
                            preview("file:" + f, event);
                            build();
                        });
                        return;
                    }
                    preview(sp, event); // hear it before choosing
                })
                .setPositiveButton("Use", (x, w) -> {
                    int pos = ((androidx.appcompat.app.AlertDialog) x).getListView().getCheckedItemPosition();
                    if (pos < 0 || pos >= specs.size() || specs.get(pos).equals("import")) return;
                    fx.sounds.put(event, specs.get(pos));
                    build();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseAudio(String title, java.util.function.Consumer<String> done) {
        String[] names = MenuMedia.listAudio(a);
        new DpDialog(a).setTitle(title).setItems(names, (x, w) -> done.accept(names[w]))
                .setNegativeButton("Cancel", null).show();
    }

    private void preview(String spec, String event) {
        if (previewPool == null || spec.isEmpty()) return;
        if (spec.startsWith("pack:")) {
            int res = MenuFx.packSound(spec.substring(5), event);
            if (res != 0) previewPool.load(a, res, 1);
        } else if (spec.startsWith("file:")) {
            java.io.File f = new java.io.File(MenuMedia.dir(a), spec.substring(5));
            if (f.isFile()) previewPool.load(f.getAbsolutePath(), 1);
        }
    }

    private void previewMusic() {
        stopMusicPreview();
        java.io.File f = new java.io.File(MenuMedia.dir(a), fx.music);
        if (!f.isFile()) return;
        try {
            previewMusic = new MediaPlayer();
            previewMusic.setDataSource(f.getAbsolutePath());
            previewMusic.setVolume(fx.musicVolume / 100f, fx.musicVolume / 100f);
            previewMusic.setOnPreparedListener(MediaPlayer::start);
            previewMusic.prepareAsync();
            // a short taste is enough
            box.postDelayed(this::stopMusicPreview, 8000);
        } catch (Exception ignored) {
        }
    }

    private void stopMusicPreview() {
        if (previewMusic != null) {
            try {
                previewMusic.release();
            } catch (Exception ignored) {
            }
            previewMusic = null;
        }
    }

    private static String packLabel(String pack) {
        for (int i = 0; i < MenuFx.PACKS.length; i++) if (MenuFx.PACKS[i].equals(pack)) return MenuFx.PACK_LABELS[i];
        return pack;
    }

    // ------------------------------------------------------------------ controls

    interface IntSet {
        void set(int v);
    }

    interface IntLabel {
        String label(int v);
    }

    private void label(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setPadding(0, dp(16), 0, dp(2));
        box.addView(t);
    }

    private TextView small(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        box.addView(t);
        return t;
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
            for (int i = 0; i < gr.getChildCount(); i++) if (gr.getChildAt(i).getId() == id) on.set(i);
        });
        box.addView(g);
    }

    private void slider(String name, int from, int to, int step, int value, IntSet on, IntLabel fmt) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(2));
        TextView t = new TextView(a);
        t.setText(name + ": " + fmt.label(value));
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
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

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
