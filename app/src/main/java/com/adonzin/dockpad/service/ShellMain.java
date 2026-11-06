package com.adonzin.dockpad.service;

import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Entry point when DockPad starts its own background service through its built-in ADB
 * connection (no Shizuku):
 *   app_process /system/bin com.adonzin.dockpad.service.ShellMain <token> <version>
 * It runs with ADB ("shell") privileges and talks to the app over a local socket
 * protected by a secret token.
 */
public final class ShellMain {
    public static final int PORT = 47321;

    public static void main(String[] args) {
        String token = args.length > 0 ? args[0] : "";
        int version = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }
        ServerSocket server;
        try {
            server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            System.err.println("DockPad service already running or port busy: " + e);
            System.exit(1);
            return;
        }
        BridgeService svc = new BridgeService();
        Log.i("DockPad", "Standalone service started, version " + version);
        while (true) {
            try {
                Socket c = server.accept();
                Thread t = new Thread(() -> handle(c, svc, token, version), "DockPad-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                Log.e("DockPad", "accept", e);
            }
        }
    }

    private static void handle(Socket c, BridgeService svc, String token, int version) {
        try (Socket s = c) {
            s.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            JSONObject req = new JSONObject(line);
            JSONObject res = new JSONObject();
            if (!token.equals(req.optString("t"))) {
                res.put("e", "denied");
            } else {
                String m = req.optString("m");
                JSONArray a = req.optJSONArray("a");
                if (a == null) a = new JSONArray();
                Object r = JSONObject.NULL;
                switch (m) {
                    case "version": r = version; break;
                    case "start": r = svc.start(a.optBoolean(0, true)); break;
                    case "stop": svc.stop(); break;
                    case "status": r = svc.status(); break;
                    case "isRunning": r = svc.isRunning(); break;
                    case "statusJson": r = svc.statusJson(); break;
                    case "setTouchpadAsButton": svc.setTouchpadAsButton(a.optBoolean(0)); break;
                    case "setMultiplayer": svc.setMultiplayer(a.optBoolean(0)); break;
                    case "setShortcuts": svc.setShortcuts(a.optString(0)); break;
                    case "setLayouts": svc.setLayouts(a.optString(0)); break;
                    case "setBuiltIn": svc.setBuiltIn(a.optString(0)); break;
                    case "setPcMode": svc.setPcMode(a.optBoolean(0)); break;
                    case "setPcSettings": svc.setPcSettings(a.optString(0)); break;
                    case "runCommand": svc.runCommand(a.optString(0), a.optInt(1, 300)); break;
                    case "setKeyboardOpen": svc.setKeyboardOpen(a.optBoolean(0)); break;
                    case "pollKeyboard": r = svc.pollKeyboard(); break;
                    case "typeKey": svc.typeKey(a.optInt(0), a.optBoolean(1)); break;
                    case "typeCombo": svc.typeCombo(a.optString(0)); break;
                    case "setJoinByPress": svc.setJoinByPress(a.optBoolean(0)); break;
                    case "disconnectController": r = svc.disconnectController(a.optString(0)); break;
                    case "recordAllInput": r = svc.recordAllInput(a.optInt(0, 10000)); break;
                    case "identifyController": r = svc.identifyController(a.optInt(0, 15000)); break;
                    case "captureCombo": r = svc.captureCombo(a.optInt(0, 15000), a.optBoolean(1)); break;
                    case "exit":
                        res.put("r", JSONObject.NULL);
                        reply(s, res);
                        svc.stop();
                        System.exit(0);
                        return;
                    default: res.put("e", "unknown method " + m);
                }
                if (!res.has("e")) res.put("r", r);
            }
            reply(s, res);
        } catch (Exception e) {
            Log.e("DockPad", "client", e);
        }
    }

    private static void reply(Socket s, JSONObject res) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write((res.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
