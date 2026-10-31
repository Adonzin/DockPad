package com.adonzin.dockpad;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.adonzin.dockpad.service.BridgeService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ = 42;
    private static final int[] TEST_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private TextView shizukuText, statusText, testerText, testerAxesText;
    private Button testerButton, builtInButton;
    private volatile IBridge bridge;
    private boolean bound, startWhenConnected, testerOn;

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, BridgeService.class.getName()))
            .daemon(true)
            .processNameSuffix("bridge")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) return;
            bridge = IBridge.Stub.asInterface(binder);
            if (startWhenConnected) {
                startWhenConnected = false;
                bg.execute(() -> {
                    try {
                        bridge.start(includeBuiltIn());
                    } catch (RemoteException ignored) {
                    }
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            bound = false;
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> ui.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> ui.post(() -> {
        bridge = null;
        bound = false;
        refreshShizuku();
    });
    private final Shizuku.OnRequestPermissionResultListener permResult = (code, result) -> ui.post(this::refreshShizuku);

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            IBridge b = bridge;
            if (b != null) {
                bg.execute(() -> {
                    String s;
                    try {
                        s = b.status();
                    } catch (Throwable t) {
                        s = "Lost connection to the bridge service.";
                    }
                    String fs = s;
                    ui.post(() -> statusText.setText(fs));
                });
            } else {
                statusText.setText("Bridge service not connected.");
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
        if (bound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, false); // keep the daemon running
            } catch (Throwable ignored) {
            }
        }
        bg.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Shizuku

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshShizuku() {
        boolean running;
        try {
            running = Shizuku.pingBinder();
        } catch (Throwable t) {
            running = false;
        }
        if (!running) {
            shizukuText.setText("Shizuku is not running. Open the Shizuku app and start it (Start via Wireless debugging), then come back.");
            return;
        }
        if (Shizuku.isPreV11()) {
            shizukuText.setText("Your Shizuku version is too old. Please update Shizuku.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            shizukuText.setText("DockPad needs Shizuku permission. Tap \"Start bridge\" and allow it.");
            return;
        }
        shizukuText.setText("Shizuku: ready ✓");
        bindService(); // connect to an already-running bridge, if any
    }

    private void bindService() {
        if (bound) return;
        try {
            Shizuku.bindUserService(serviceArgs, connection);
            bound = true;
        } catch (Throwable t) {
            shizukuText.setText("Could not start the bridge service: " + t);
        }
    }

    private void onStartClicked() {
        try {
            if (!Shizuku.pingBinder()) {
                refreshShizuku();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ);
                return;
            }
        } catch (Throwable t) {
            refreshShizuku();
            return;
        }
        IBridge b = bridge;
        if (b != null) {
            bg.execute(() -> {
                try {
                    b.start(includeBuiltIn());
                } catch (RemoteException ignored) {
                }
            });
        } else {
            startWhenConnected = true;
            bindService();
        }
    }

    private void onStopClicked() {
        IBridge b = bridge;
        bg.execute(() -> {
            try {
                if (b != null) b.stop();
            } catch (RemoteException ignored) {
            }
            ui.post(() -> {
                if (shizukuReady()) {
                    try {
                        Shizuku.unbindUserService(serviceArgs, connection, true); // end the process
                    } catch (Throwable ignored) {
                    }
                }
                bridge = null;
                bound = false;
            });
        });
    }

    // ------------------------------------------------------------------ input tester

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static String deviceLabel(InputDevice d, int id) {
        return d == null ? "unknown device (id " + id + ")" : d.getName() + " (id " + id + ")";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (testerOn && isGamepad(e.getSource())) {
            if (e.getRepeatCount() == 0) {
                testerText.setText(KeyEvent.keyCodeToString(e.getKeyCode())
                        + (e.getAction() == KeyEvent.ACTION_DOWN ? "  pressed" : "  released")
                        + "\nfrom: " + deviceLabel(e.getDevice(), e.getDeviceId()));
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (testerOn && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            StringBuilder b = new StringBuilder("Sticks/triggers from: ")
                    .append(deviceLabel(e.getDevice(), e.getDeviceId())).append('\n');
            for (int axis : TEST_AXES) {
                float v = e.getAxisValue(axis);
                if (Math.abs(v) > 0.02f) {
                    b.append(MotionEvent.axisToString(axis).replace("AXIS_", ""))
                            .append(String.format(" %.2f  ", v));
                }
            }
            testerAxesText.setText(b.toString());
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DockPad");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Use any external controller as your built-in controls.");
        sub.setPadding(0, 0, 0, pad);
        root.addView(sub);

        shizukuText = new TextView(this);
        shizukuText.setPadding(0, 0, 0, pad);
        root.addView(shizukuText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText("Start bridge");
        start.setOnClickListener(v -> onStartClicked());
        Button stop = new Button(this);
        stop.setText("Stop bridge");
        stop.setOnClickListener(v -> onStopClicked());
        row.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        builtInButton = new Button(this);
        builtInButton.setOnClickListener(v -> toggleIncludeBuiltIn());
        updateBuiltInButton();
        root.addView(builtInButton);

        TextView builtInHint = new TextView(this);
        builtInHint.setText("ON: the built-in controls also go through the DockPad Controller, so emulators "
                + "mapped to it work the same handheld or docked. OFF: only external controllers do.");
        builtInHint.setTextSize(12);
        root.addView(builtInHint);

        statusText = new TextView(this);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setTextSize(12);
        statusText.setPadding(0, pad, 0, pad);
        statusText.setTextIsSelectable(true);
        root.addView(statusText);

        testerButton = new Button(this);
        testerButton.setText("Input tester: OFF");
        testerButton.setOnClickListener(v -> setTester(!testerOn));
        root.addView(testerButton);

        TextView hint = new TextView(this);
        hint.setText("While the tester is on, controller presses are shown here instead of moving around the screen. "
                + "Tap the button on the touchscreen to turn it off.");
        hint.setTextSize(12);
        root.addView(hint);

        testerText = new TextView(this);
        testerText.setTextSize(18);
        testerText.setPadding(0, pad, 0, 0);
        root.addView(testerText);

        testerAxesText = new TextView(this);
        testerAxesText.setTypeface(Typeface.MONOSPACE);
        testerAxesText.setPadding(0, pad / 2, 0, 0);
        root.addView(testerAxesText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void setTester(boolean on) {
        testerOn = on;
        testerButton.setText(on ? "Input tester: ON" : "Input tester: OFF");
        if (!on) {
            testerText.setText("");
            testerAxesText.setText("");
        } else {
            testerText.setText("Press buttons on any controller…");
        }
    }

    private boolean includeBuiltIn() {
        return getSharedPreferences("dockpad", MODE_PRIVATE).getBoolean("includeBuiltIn", true);
    }

    private void toggleIncludeBuiltIn() {
        boolean v = !includeBuiltIn();
        getSharedPreferences("dockpad", MODE_PRIVATE).edit().putBoolean("includeBuiltIn", v).apply();
        updateBuiltInButton();
        IBridge b = bridge;
        if (b != null) bg.execute(() -> {
            try {
                if (b.isRunning()) b.start(v);
            } catch (RemoteException ignored) {
            }
        });
    }

    private void updateBuiltInButton() {
        builtInButton.setText(includeBuiltIn()
                ? "Built-in controls → DockPad Controller: ON"
                : "Built-in controls → DockPad Controller: OFF");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
