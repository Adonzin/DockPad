package com.adonzin.dockpad.service;

import android.util.Log;

import com.adonzin.dockpad.core.EvdevDevice;
import com.adonzin.dockpad.core.TargetEncoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A virtual controller created through Android's built-in `uinput` shell tool, which the
 * system treats as real hardware. It copies the capabilities and hardware IDs of the
 * built-in controls, under its own name.
 */
final class VirtualPad implements PadOut {
    static final String NAME = "DockPad Controller";
    private String name = NAME;

    /** "DockPad Controller" for player 1, "DockPad Controller 2" for player 2, ... */
    static String nameFor(int player) {
        return player <= 1 ? NAME : NAME + " " + player;
    }

    static boolean isOurs(String deviceName) {
        return deviceName != null && deviceName.startsWith("DockPad ");
    }

    @Override
    public String name() {
        return name;
    }

    /** Called when a game starts/stops rumble on this controller. */
    interface RumbleListener {
        void rumble(boolean on);
    }

    private volatile RumbleListener rumbleListener;

    /** Makes the controller report rumble support; set before create(). */
    void onRumble(RumbleListener l) {
        rumbleListener = l;
    }

    private Process process;
    private OutputStream stdin;
    private final List<String> toolOutput = Collections.synchronizedList(new ArrayList<>());
    private volatile String error = "";

    boolean create(EvdevDevice clone, String name) {
        return createWithJson(name, registerJson(clone, name, rumbleListener != null));
    }

    /** Creates any kind of virtual device from a uinput "register" command. */
    boolean createWithJson(String name, String registerJson) {
        this.name = name;
        try {
            process = new ProcessBuilder("uinput", "-").redirectErrorStream(true).start();
            stdin = process.getOutputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("\"vibrating\"")) {
                            RumbleListener l = rumbleListener;
                            if (l != null) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"status\"\\s*:\\s*(-?\\d+)").matcher(line);
                                if (m.find()) l.rumble(Integer.parseInt(m.group(1)) != 0);
                            }
                            continue;
                        }
                        Log.i("DockPad", "uinput: " + line);
                        toolOutput.add(line);
                        while (toolOutput.size() > 5) toolOutput.remove(0);
                    }
                } catch (IOException ignored) {
                }
            }, "DockPad-uinput-log");
            reader.setDaemon(true);
            reader.start();
            send(registerJson);
            return true;
        } catch (Throwable t) {
            error = "could not start Android's uinput tool: " + t;
            Log.e("DockPad", error, t);
            close();
            return false;
        }
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public String describe() {
        if (isAlive()) return "active";
        String out = String.join(" | ", toolOutput);
        return "NOT running" + (error.isEmpty() ? "" : " — " + error) + (out.isEmpty() ? "" : " — " + out);
    }

    static String registerJson(EvdevDevice d, String name, boolean rumble) {
        StringBuilder keys = new StringBuilder();
        for (int k : sorted(d.keys)) keys.append(keys.length() == 0 ? "" : ",").append(k);
        StringBuilder absBits = new StringBuilder();
        StringBuilder absInfo = new StringBuilder();
        for (int a : sorted(d.abs.keySet())) {
            EvdevDevice.AbsInfo i = d.abs.get(a);
            absBits.append(absBits.length() == 0 ? "" : ",").append(a);
            absInfo.append(absInfo.length() == 0 ? "" : ",")
                    .append("{\"code\":").append(a).append(",\"info\":{\"value\":0")
                    .append(",\"minimum\":").append(i.min).append(",\"maximum\":").append(i.max)
                    .append(",\"fuzz\":").append(i.fuzz).append(",\"flat\":").append(i.flat)
                    .append(",\"resolution\":0}}");
        }
        return "{\"id\":1,\"command\":\"register\",\"name\":\"" + name + "\""
                + ",\"vid\":" + d.vendor + ",\"pid\":" + d.product + ",\"bus\":\"usb\""
                + ",\"configuration\":["
                + "{\"type\":100,\"data\":[1,3" + (rumble ? ",21" : "") + "]}" // UI_SET_EVBIT: EV_KEY, EV_ABS (+EV_FF)
                + ",{\"type\":101,\"data\":[" + keys + "]}"  // UI_SET_KEYBIT
                + ",{\"type\":103,\"data\":[" + absBits + "]}" // UI_SET_ABSBIT
                + (rumble ? ",{\"type\":107,\"data\":[80]}" : "") // UI_SET_FFBIT: FF_RUMBLE
                + "],\"abs_info\":[" + absInfo + "]"
                + (rumble ? ",\"ff_effects_max\":16" : "") + "}";
    }

    static String json(String name, int vid, int pid, int[] evBits, int[] keyBits, int[] relBits, int[] absBits,
                       String absInfo, int[] propBits) {
        StringBuilder c = new StringBuilder();
        c.append("{\"type\":100,\"data\":").append(arr(evBits)).append('}');
        if (keyBits.length > 0) c.append(",{\"type\":101,\"data\":").append(arr(keyBits)).append('}');
        if (relBits.length > 0) c.append(",{\"type\":102,\"data\":").append(arr(relBits)).append('}');
        if (absBits.length > 0) c.append(",{\"type\":103,\"data\":").append(arr(absBits)).append('}');
        if (propBits.length > 0) c.append(",{\"type\":110,\"data\":").append(arr(propBits)).append('}');
        return "{\"id\":1,\"command\":\"register\",\"name\":\"" + name + "\",\"vid\":" + vid + ",\"pid\":" + pid
                + ",\"bus\":\"usb\",\"configuration\":[" + c + "]"
                + (absInfo.isEmpty() ? "" : ",\"abs_info\":[" + absInfo + "]") + "}";
    }

    private static String arr(int[] a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : ",").append(a[i]);
        return b.append(']').toString();
    }

    private static List<Integer> sorted(java.util.Collection<Integer> c) {
        List<Integer> l = new ArrayList<>(c);
        Collections.sort(l);
        return l;
    }

    @Override
    public synchronized void write(int[] events) {
        if (stdin == null) return;
        StringBuilder b = new StringBuilder("{\"id\":1,\"command\":\"inject\",\"events\":[");
        for (int i = 0; i < events.length; i++) b.append(i == 0 ? "" : ",").append(events[i]);
        b.append("]}");
        try {
            send(b.toString());
        } catch (IOException e) {
            error = "write failed: " + e;
        }
    }

    private synchronized void send(String json) throws IOException {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Override
    public synchronized void close() {
        try {
            if (stdin != null) stdin.close(); // EOF makes the tool remove the device
        } catch (IOException ignored) {
        }
        stdin = null;
        if (process != null) {
            final Process p = process;
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                p.destroy();
            }).start();
        }
        process = null;
    }
}
