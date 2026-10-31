package com.adonzin.dockpad;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Shows exactly what apps receive from controllers: which device, which button, stick positions. */
public class TesterActivity extends AppCompatActivity {
    private static final int[] AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
    private static final String[] AXIS_LABELS = {
            "Left stick X", "Left stick Y", "Right stick X (Z)", "Right stick Y (RZ)",
            "Right stick X (RX)", "Right stick Y (RY)", "Left trigger", "Right trigger", "D-pad X", "D-pad Y"};

    private TextView buttonText, deviceText, axesDevice;
    private final LinearProgressIndicator[] bars = new LinearProgressIndicator[AXES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("Test controllers");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Press buttons and move sticks on any controller. With DockPad on, everything should come "
                + "from a \"DockPad Controller\". Buttons here won't control the screen. Use Back or the button "
                + "below to leave.");
        help.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        help.setPadding(0, dp(4), 0, dp(16));
        root.addView(help);

        buttonText = new TextView(this);
        buttonText.setText("Press a button…");
        buttonText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall);
        root.addView(buttonText);

        deviceText = new TextView(this);
        deviceText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        deviceText.setPadding(0, dp(4), 0, dp(20));
        root.addView(deviceText);

        axesDevice = new TextView(this);
        axesDevice.setText("Sticks and triggers");
        axesDevice.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        root.addView(axesDevice);

        for (int i = 0; i < AXES.length; i++) {
            TextView l = new TextView(this);
            l.setText(AXIS_LABELS[i]);
            l.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            l.setPadding(0, dp(10), 0, dp(2));
            root.addView(l);
            LinearProgressIndicator bar = new LinearProgressIndicator(this);
            bar.setMax(200);
            bar.setProgress(isTrigger(i) ? 0 : 100);
            bars[i] = bar;
            root.addView(bar);
        }

        MaterialButton done = new MaterialButton(this);
        done.setText("Done");
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(done, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private static boolean isTrigger(int i) {
        return AXES[i] == MotionEvent.AXIS_LTRIGGER || AXES[i] == MotionEvent.AXIS_RTRIGGER;
    }

    private static boolean fromController(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static String deviceName(InputDevice d, int id) {
        return d == null ? "an unknown device (id " + id + ")" : d.getName();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            return super.dispatchKeyEvent(e);
        }
        if (fromController(e.getSource())) {
            if (e.getRepeatCount() == 0) {
                String name = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "").replace("BUTTON_", "");
                buttonText.setText(e.getAction() == KeyEvent.ACTION_DOWN ? name : name + " (released)");
                deviceText.setText("from " + deviceName(e.getDevice(), e.getDeviceId()));
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            axesDevice.setText("Sticks and triggers from " + deviceName(e.getDevice(), e.getDeviceId()));
            for (int i = 0; i < AXES.length; i++) {
                float v = e.getAxisValue(AXES[i]);
                bars[i].setProgress(isTrigger(i) ? Math.round(v * 200) : Math.round((v + 1f) * 100));
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
