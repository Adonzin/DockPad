package com.adonzin.dockpad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Investigation for rumble pass-through: which controllers Android can rumble, and the handheld's motor.
 * Select a row to make it rumble.
 */
@SuppressLint("MissingPermission")
final class RumbleTest {
    private RumbleTest() {
    }

    static void show(Activity a, IBridge bridge, java.util.concurrent.Executor bg) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 24), dp(a, 8), dp(a, 24), 0);
        TextView intro = new TextView(a);
        intro.setText("Select each one: does it rumble? Tell me which do and which don't, and I'll know how "
                + "rumble can be passed to your controllers.");
        intro.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        box.addView(intro);

        // the handheld's own motor (what games usually use for player 1)
        Vibrator own = a.getSystemService(Vibrator.class);
        boolean hasOwn = own != null && own.hasVibrator();
        row(a, box, R.drawable.ic_handheld, "This handheld's motor",
                hasOwn ? "Android: can vibrate" : "Android: no motor found", v -> {
                    if (own != null) own.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE));
                });

        StringBuilder summary = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (!pad) continue;
            int motors = 0;
            VibratorManager vm = null;
            if (Build.VERSION.SDK_INT >= 31) {
                vm = d.getVibratorManager();
                motors = vm.getVibratorIds().length;
            } else if (d.getVibrator().hasVibrator()) {
                motors = 1;
            }
            String sub = String.format("%04x:%04x · ", d.getVendorId(), d.getProductId())
                    + (motors == 0 ? "Android: can't rumble it" : "Android: " + motors + (motors == 1 ? " motor" : " motors"));
            summary.append(d.getName()).append(": ").append(motors).append(" motors; ");
            VibratorManager manager = vm;
            int count = motors;
            row(a, box, R.drawable.ic_gamepad, d.getName(), sub, v -> {
                if (count == 0) {
                    Toast.makeText(a, "Android has no rumble for this one", Toast.LENGTH_SHORT).show();
                    return;
                }
                VibrationEffect e = VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE);
                if (Build.VERSION.SDK_INT >= 31 && manager != null) manager.vibrate(CombinedVibration.createParallel(e));
                else d.getVibrator().vibrate(e);
            });
        }

        TextView kernel = new TextView(a);
        kernel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        kernel.setPadding(0, dp(a, 12), 0, 0);
        kernel.setTextIsSelectable(true);
        kernel.setText("Checking what the system reports…");
        box.addView(kernel);
        if (bridge != null) {
            bg.execute(() -> {
                String text;
                try {
                    StringBuilder b = new StringBuilder();
                    for (String line : bridge.status().split("\n")) {
                        if (line.startsWith("Rumble") || line.startsWith("Handheld vibrator")) b.append(line).append('\n');
                    }
                    text = b.length() == 0 ? "No system report." : b.toString().trim();
                } catch (Exception e) {
                    text = "Couldn't ask DockPad: " + e.getMessage();
                }
                String t = text;
                a.runOnUiThread(() -> kernel.setText(t));
            });
        } else {
            kernel.setText("Turn DockPad on to also see what the system reports.");
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new DpDialog(a).setTitle("Rumble test").setView(sv).setPositiveButton("Done", null).show();
    }

    private static void row(Activity a, LinearLayout box, int icon, String title, String sub, View.OnClickListener l) {
        View r = a.getLayoutInflater().inflate(R.layout.item_row, box, false);
        ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) r.findViewById(R.id.row_title)).setText(title);
        ((TextView) r.findViewById(R.id.row_subtitle)).setText(sub + " · select to test");
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnClickListener(l);
        box.addView(r);
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
