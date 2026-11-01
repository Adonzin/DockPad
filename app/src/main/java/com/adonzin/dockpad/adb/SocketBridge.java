package com.adonzin.dockpad.adb;

import android.os.IBinder;
import android.os.RemoteException;

import com.adonzin.dockpad.IBridge;
import com.adonzin.dockpad.service.ShellMain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Talks to DockPad's standalone background service (started without Shizuku). */
public final class SocketBridge implements IBridge {
    private final String token;

    public SocketBridge(String token) {
        this.token = token;
    }

    /** The running service's version, or -1 if it isn't running (or isn't ours). */
    public int version() {
        try {
            Object r = call("version", 2000);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public void exit() {
        try {
            call("exit", 2000);
        } catch (Exception ignored) {
        }
    }

    private Object call(String method, int timeoutMs, Object... args) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ShellMain.PORT), 1000);
            s.setSoTimeout(timeoutMs);
            JSONObject req = new JSONObject();
            req.put("t", token);
            req.put("m", method);
            JSONArray a = new JSONArray();
            for (Object o : args) a.put(o);
            req.put("a", a);
            OutputStream out = s.getOutputStream();
            out.write((req.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (line == null) throw new Exception("no reply");
            JSONObject res = new JSONObject(line);
            if (res.has("e")) throw new Exception(res.getString("e"));
            Object r = res.opt("r");
            return r == JSONObject.NULL ? null : r;
        }
    }

    private Object rc(String method, Object... args) throws RemoteException {
        return rcTimeout(method, 5000, args);
    }

    private Object rcTimeout(String method, int timeout, Object... args) throws RemoteException {
        try {
            return call(method, timeout, args);
        } catch (Exception e) {
            throw new RemoteException(e.toString());
        }
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public void destroy() throws RemoteException {
        exit();
    }

    @Override
    public String start(boolean includeBuiltIn) throws RemoteException {
        return String.valueOf(rc("start", includeBuiltIn));
    }

    @Override
    public void stop() throws RemoteException {
        rc("stop");
    }

    @Override
    public String status() throws RemoteException {
        return String.valueOf(rc("status"));
    }

    @Override
    public boolean isRunning() throws RemoteException {
        return Boolean.TRUE.equals(rc("isRunning"));
    }

    @Override
    public void setTouchpadAsButton(boolean on) throws RemoteException {
        rc("setTouchpadAsButton", on);
    }

    @Override
    public void setMultiplayer(boolean on) throws RemoteException {
        rc("setMultiplayer", on);
    }

    @Override
    public String statusJson() throws RemoteException {
        Object r = rc("statusJson");
        return r == null ? "{}" : r.toString();
    }

    @Override
    public void setShortcuts(String spec) throws RemoteException {
        rc("setShortcuts", spec);
    }

    @Override
    public String captureCombo(int timeoutMs, boolean raw) throws RemoteException {
        Object r = rcTimeout("captureCombo", timeoutMs + 5000, timeoutMs, raw);
        return r == null ? "" : r.toString();
    }

    @Override
    public void setLayouts(String spec) throws RemoteException {
        rc("setLayouts", spec);
    }

    @Override
    public void setBuiltIn(String spec) throws RemoteException {
        rc("setBuiltIn", spec);
    }

    @Override
    public String identifyController(int timeoutMs) throws RemoteException {
        Object r = rcTimeout("identifyController", timeoutMs + 5000, timeoutMs);
        return r == null ? "" : r.toString();
    }
}
