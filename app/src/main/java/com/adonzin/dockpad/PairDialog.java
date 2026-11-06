package com.adonzin.dockpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds controllers in pairing mode and pairs the one you pick, all with a controller.
 * Works from the app and from the quick menu.
 */
@SuppressLint("MissingPermission")
final class PairDialog {
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private PairDialog() {
    }

    static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static void show(Activity a) {
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(a, "Pair it in Android's Bluetooth settings on this Android version", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasPermission(a)) {
            // normally granted by DockPad's service when it starts; otherwise ask
            a.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 41);
            Toast.makeText(a, "Allow \"Nearby devices\", then try again", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothManager bm = a.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            Toast.makeText(a, "This device has no Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception e) {
                Toast.makeText(a, "Turn Bluetooth on first", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new PairDialog.Session(a, adapter).open();
    }

    private static final class Session {
        final Activity activity;
        final Context app;
        final BluetoothAdapter adapter;
        final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
        final Map<String, Boolean> isPad = new LinkedHashMap<>();
        LinearLayout list;
        TextView hint;
        AlertDialog dialog;
        BluetoothDevice pairing;
        boolean open = true, receiverOn;

        Session(Activity a, BluetoothAdapter adapter) {
            this.activity = a;
            this.app = a.getApplicationContext();
            this.adapter = adapter;
        }

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothDevice.ACTION_FOUND.equals(action) && d != null) {
                    onFound(d, i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS));
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    if (open && pairing == null) ui.postDelayed(Session.this::discover, 500); // keep looking
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && d != null
                        && pairing != null && d.getAddress().equals(pairing.getAddress())) {
                    int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                    int before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        Toast.makeText(app, label(d) + " paired. It's ready in a few seconds.", Toast.LENGTH_LONG).show();
                        finish();
                    } else if (state == BluetoothDevice.BOND_NONE && before == BluetoothDevice.BOND_BONDING) {
                        Toast.makeText(app, "Couldn't pair " + label(d) + ". Put it in pairing mode and try again.",
                                Toast.LENGTH_LONG).show();
                        pairing = null;
                        if (open) {
                            setHint(HINT);
                            discover();
                        } else {
                            finish();
                        }
                    }
                }
            }
        };

        static final String HINT = "Put the controller in pairing mode:\n"
                + "• PlayStation: hold Share + PS until the light flashes\n"
                + "• Xbox: hold the pair button on top until the logo flashes fast\n"
                + "• 8BitDo: hold its pair button (or Start + Y/X/B for its mode) for 3 seconds\n"
                + "• Switch Pro / Joy-Con: hold the small sync button\n"
                + "Then pick it below.";

        void open() {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), 0);
            hint = new TextView(activity);
            hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            box.addView(hint);
            setHint(HINT);
            LinearProgressIndicator progress = new LinearProgressIndicator(activity);
            progress.setIndeterminate(true);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(8);
            box.addView(progress, plp);
            list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            box.addView(list);
            showEmpty();
            android.widget.ScrollView sv = new android.widget.ScrollView(activity);
            sv.addView(box);

            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(receiver, f);
            receiverOn = true;

            dialog = new DpDialog(activity)
                    .setTitle("Pair a new controller")
                    .setView(sv)
                    .setNegativeButton("Close", null)
                    .create();
            dialog.setOnDismissListener(x -> {
                open = false;
                try {
                    adapter.cancelDiscovery();
                } catch (Exception ignored) {
                }
                // keep listening a little while if a pairing is under way, to report how it went
                if (pairing == null) finish();
                else ui.postDelayed(this::finish, 60000);
            });
            dialog.show();
            discover();
        }

        void discover() {
            if (!open || pairing != null) return;
            try {
                if (!adapter.isDiscovering()) adapter.startDiscovery();
            } catch (Exception e) {
                setHint("Couldn't search for controllers: " + e.getMessage());
            }
        }

        void onFound(BluetoothDevice d, BluetoothClass cls) {
            if (!open || d.getBondState() == BluetoothDevice.BOND_BONDED) return;
            if (cls == null) cls = d.getBluetoothClass();
            boolean pad = cls != null && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL;
            String name = d.getName();
            if ((name == null || name.isEmpty()) && !pad) return; // unnamed phones, TVs, etc.
            boolean isNew = !found.containsKey(d.getAddress());
            found.put(d.getAddress(), d);
            isPad.put(d.getAddress(), pad);
            if (isNew || name != null) render();
        }

        void render() {
            View focused = activity.getCurrentFocus();
            String focusedAddr = null;
            if (dialog != null && dialog.getCurrentFocus() != null) {
                Object tag = dialog.getCurrentFocus().getTag();
                if (tag instanceof String) focusedAddr = (String) tag;
            }
            list.removeAllViews();
            // controllers first
            for (int pass = 0; pass < 2; pass++) {
                for (Map.Entry<String, BluetoothDevice> e : found.entrySet()) {
                    boolean pad = Boolean.TRUE.equals(isPad.get(e.getKey()));
                    if ((pass == 0) != pad) continue;
                    BluetoothDevice d = e.getValue();
                    View r = activity.getLayoutInflater().inflate(R.layout.item_row, list, false);
                    ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(
                            pad ? R.drawable.ic_gamepad : R.drawable.ic_pending);
                    ((TextView) r.findViewById(R.id.row_title)).setText(label(d));
                    ((TextView) r.findViewById(R.id.row_subtitle)).setText(pad ? "Controller · select to pair"
                            : "Other device · select to pair");
                    r.setTag(d.getAddress());
                    r.setFocusable(true);
                    r.setClickable(true);
                    r.setOnClickListener(v -> pair(d));
                    list.addView(r);
                    if (d.getAddress().equals(focusedAddr)) r.requestFocus();
                }
            }
            if (found.isEmpty()) showEmpty();
        }

        void showEmpty() {
            TextView t = new TextView(activity);
            t.setText("Looking for controllers…");
            t.setPadding(0, dp(12), 0, dp(12));
            list.addView(t);
        }

        void pair(BluetoothDevice d) {
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }
            pairing = d;
            setHint("Pairing " + label(d) + "… If Android asks to confirm, choose Pair.");
            if (!d.createBond()) {
                Toast.makeText(app, "Couldn't start pairing " + label(d), Toast.LENGTH_SHORT).show();
                pairing = null;
                setHint(HINT);
                discover();
            }
        }

        void setHint(String s) {
            if (hint != null) hint.setText(s);
        }

        void finish() {
            if (receiverOn) {
                receiverOn = false;
                try {
                    app.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
            if (dialog != null && dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
            }
        }

        static String label(BluetoothDevice d) {
            String n = d.getName();
            return n == null || n.isEmpty() ? "Unnamed device (" + d.getAddress() + ")" : n;
        }

        int dp(int v) {
            return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
