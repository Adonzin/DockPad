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
final class VirtualPad implements TargetEncoder.Out {
    static final String NAME = "DockPad Controller";
    private String name = NAME;

    /** "DockPad Controller" for player 1, "DockPad Controller 2" for player 2, ... */
    static String nameFor(int player) {
        return player <= 1 ? NAME : NAME + " " + player;
    }

    static boolean isOurs(String deviceName) {
        return deviceName != null && deviceName.startsWith(NAME);
    }

    String name() {
        return name;
    }

    private Process process;
    private OutputStream stdin;
    private final List<String> toolOutput = Collections.synchronizedList(new ArrayList<>());
    private volatile String error = "";

    boolean create(EvdevDevice clone, String name) {
        this.name = name;
        try {
            process = new ProcessBuilder("uinput", "-").redirectErrorStream(true).start();
            stdin = process.getOutputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i("DockPad", "uinput: " + line);
                        toolOutput.add(line);
                        while (toolOutput.size() > 5) toolOutput.remove(0);
                    }
                } catch (IOException ignored) {
                }
            }, "DockPad-uinput-log");
            reader.setDaemon(true);
            reader.start();
            send(registerJson(clone, name));
            return true;
        } catch (Throwable t) {
            error = "could not start Android's uinput tool: " + t;
            Log.e("DockPad", error, t);
            close();
            return false;
        }
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    String describe() {
        if (isAlive()) return "active";
        String out = String.join(" | ", toolOutput);
        return "NOT running" + (error.isEmpty() ? "" : " — " + error) + (out.isEmpty() ? "" : " — " + out);
    }

    static String registerJson(EvdevDevice d, String name) {
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
                + "{\"type\":100,\"data\":[1,3]}"          // UI_SET_EVBIT: EV_KEY, EV_ABS
                + ",{\"type\":101,\"data\":[" + keys + "]}"  // UI_SET_KEYBIT
                + ",{\"type\":103,\"data\":[" + absBits + "]}" // UI_SET_ABSBIT
                + "],\"abs_info\":[" + absInfo + "]}";
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

    synchronized void close() {
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
