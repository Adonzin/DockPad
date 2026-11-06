package com.adonzin.dockpad.service;

import android.util.Log;

import com.adonzin.dockpad.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a DockPad release and installs it with the shell's package installer (no prompts). */
final class Updater {
    static final String FILE = "/data/local/tmp/dockpad_update.apk";

    private Updater() {
    }

    /** Returns null on success, else what went wrong. */
    static String downloadAndInstall(String url) {
        File f = new File(FILE);
        try {
            String err = download(url, f);
            if (err != null) return err;
            String out = Actions.exec("pm", "install", "-r", FILE);
            Log.i("DockPad", "Update install: " + out.trim());
            if (!out.contains("Success")) return "Install failed: " + out.trim();
            return null;
        } catch (Throwable t) {
            return "Update failed: " + t;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String download(String url, File to) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "DockPad/" + BuildConfig.VERSION_CODE);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = c.getHeaderField("Location");
                c.disconnect();
                if (url == null) return "Download failed (bad redirect)";
                continue;
            }
            if (code != 200) {
                c.disconnect();
                return "Download failed (HTTP " + code + ")";
            }
            long total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                c.disconnect();
            }
            if (total < 100_000) return "Download failed (file too small)";
            //noinspection ResultOfMethodCallIgnored
            to.setReadable(true, false);
            return null;
        }
        return "Download failed (too many redirects)";
    }
}
