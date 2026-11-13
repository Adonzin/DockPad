package com.adonzin.dockpad;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates DockPad when its background service is off (so it can't install silently): downloads the
 * release and hands it to Android's installer, which asks once to confirm.
 */
final class AppUpdater {
    private AppUpdater() {
    }

    /** Downloads and installs. Call from a background thread. Returns null on success, else the problem. */
    static String downloadAndInstall(Context c, String url) {
        File f = new File(c.getCacheDir(), "update.apk");
        try {
            String err = download(url, f);
            if (err != null) return err;
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("dockpad.apk", 0, f.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                PendingIntent done = PendingIntent.getBroadcast(c, id,
                        new Intent(c, Result.class).setPackage(c.getPackageName()),
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(done.getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "Update failed: " + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String download(String url, File to) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "DockPad/" + BuildConfig.VERSION_CODE);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                url = con.getHeaderField("Location");
                con.disconnect();
                if (url == null) return "Download failed (bad redirect)";
                continue;
            }
            if (code != 200) {
                con.disconnect();
                return "Download failed (HTTP " + code + ")";
            }
            long total = 0;
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                con.disconnect();
            }
            if (total < 100_000) return "Download failed (file too small)";
            return null;
        }
        return "Download failed (too many redirects)";
    }

    /** Android's answer: show its confirmation, or say what went wrong. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        Toast.makeText(c, "Couldn't open Android's installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(c, "DockPad update didn't install" + (msg == null ? "" : ": " + msg),
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
