package com.adonzin.dockpad;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks for a newer DockPad build on GitHub. Network calls: run off the UI thread. */
final class Updates {
    static final String REPO = "Adonzin/DockPad";
    private static final String API = "https://api.github.com/repos/" + REPO;

    static final class Release {
        int build;
        String apkUrl, pageUrl, sha;
        final List<String> changes = new ArrayList<>();

        boolean isNewer() {
            return build > BuildConfig.VERSION_CODE;
        }
    }

    private Updates() {
    }

    /** The latest release, with what changed since this build. */
    static Release latest() throws Exception {
        JSONObject r = new JSONObject(get(API + "/releases/latest")); // newest kept release (build-N)
        Release rel = new Release();
        Matcher m = Pattern.compile("#(\\d+)").matcher(r.optString("name"));
        rel.build = m.find() ? Integer.parseInt(m.group(1)) : 0;
        rel.pageUrl = r.optString("html_url", "https://github.com/" + REPO + "/releases/latest");
        rel.sha = r.optString("target_commitish");
        JSONArray assets = r.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) rel.apkUrl = a.optString("browser_download_url");
        }
        if (rel.isNewer() && !BuildConfig.GIT_SHA.isEmpty() && !rel.sha.isEmpty()) {
            try {
                JSONObject cmp = new JSONObject(get(API + "/compare/" + BuildConfig.GIT_SHA + "..." + rel.sha));
                JSONArray commits = cmp.optJSONArray("commits");
                if (commits != null) for (int i = commits.length() - 1; i >= 0 && rel.changes.size() < 15; i--) {
                    String msg = commits.getJSONObject(i).getJSONObject("commit").optString("message");
                    String first = msg.split("\n")[0].trim();
                    if (first.isEmpty() || first.startsWith("CI") || first.startsWith("Fix variable")) continue;
                    rel.changes.add(first);
                }
            } catch (Exception ignored) {
                // the change list is a nice extra
            }
        }
        return rel;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "DockPad/" + BuildConfig.VERSION_CODE);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("GitHub answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
