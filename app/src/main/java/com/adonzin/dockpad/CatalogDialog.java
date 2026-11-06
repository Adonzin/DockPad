package com.adonzin.dockpad;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The theme catalog: themes people have shared, with previews, installed with one press. The catalog
 * lives in DockPad's GitHub repo (catalog/index.json); themes are added there after a review.
 */
final class CatalogDialog {
    static final String BASE = "https://raw.githubusercontent.com/" + Updates.REPO + "/main/";
    private static final ExecutorService bg = Executors.newFixedThreadPool(3);
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private final Activity a;
    private final Runnable afterInstall;

    private CatalogDialog(Activity a, Runnable afterInstall) {
        this.a = a;
        this.afterInstall = afterInstall;
    }

    static void show(Activity a, Runnable afterInstall) {
        new CatalogDialog(a, afterInstall).open();
    }

    private void open() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        ProgressBar spin = new ProgressBar(a);
        box.addView(spin);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        dlg[0] = new DpDialog(a).setTitle("Get themes").setView(sv)
                .setNegativeButton("Close", null)
                .setNeutralButton("How to share yours", (d, w) -> a.startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/" + Updates.REPO + "/blob/main/docs/THEMES.md#sharing-in-the-catalog"))))
                .show();
        bg.execute(() -> {
            try {
                JSONObject index = new JSONObject(new String(get(BASE + "catalog/index.json"), "UTF-8"));
                JSONArray themes = index.optJSONArray("themes");
                ui.post(() -> {
                    box.removeView(spin);
                    if (themes == null || themes.length() == 0) {
                        box.addView(text("No themes in the catalog yet.", false));
                        return;
                    }
                    box.addView(text("Shared themes, checked before they're added. Select one to see it bigger and "
                            + "install it.", true));
                    View first = null;
                    for (int i = 0; i < themes.length(); i++) {
                        JSONObject t = themes.optJSONObject(i);
                        if (t == null) continue;
                        View row = row(t, dlg);
                        box.addView(row);
                        if (first == null) first = row;
                    }
                    if (first != null) first.post(first::requestFocus);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    box.removeView(spin);
                    box.addView(text("Couldn't load the catalog: " + e.getMessage() + ". Check the internet "
                            + "connection and try again.", false));
                });
            }
        });
    }

    private View row(JSONObject t, android.app.Dialog[] dlg) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setForeground(a.getDrawable(R.drawable.focus_ring));
        ImageView preview = new ImageView(a);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0x22FFFFFF);
        row.addView(preview, new LinearLayout.LayoutParams(dp(160), dp(90)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView name = text(t.optString("name"), false);
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        col.addView(name);
        col.addView(text((t.optString("author").isEmpty() ? "" : "by " + t.optString("author") + " · ")
                + t.optString("layout") + " · " + Math.max(1, t.optLong("size") / 1024) + " KB", true));
        col.addView(text(t.optString("description"), true));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        loadPreview(t.optString("preview"), preview, dp(320));
        row.setOnClickListener(v -> details(t));
        return row;
    }

    private void details(JSONObject t) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        ImageView big = new ImageView(a);
        big.setAdjustViewBounds(true);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(big, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));
        loadPreview(t.optString("preview"), big, dp(900));
        box.addView(text(t.optString("description"), false));
        box.addView(text((t.optString("author").isEmpty() ? "" : "Made by " + t.optString("author") + ". ")
                + "Installing adds it to your themes; your items and sections stay as they are.", true));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new DpDialog(a).setTitle(t.optString("name")).setView(sv)
                .setPositiveButton("Install", (d, w) -> install(t))
                .setNegativeButton("Back", null)
                .show();
    }

    private void install(JSONObject t) {
        String file = t.optString("file");
        if (!file.startsWith("catalog/") || file.contains("..")) return;
        android.widget.Toast.makeText(a, "Downloading " + t.optString("name") + "…", android.widget.Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            try {
                byte[] data = get(BASE + file);
                ThemeLibrary.Entry e = ThemeLibrary.install(a, new java.io.ByteArrayInputStream(data));
                ui.post(() -> new DpDialog(a).setTitle("Installed “" + e.name + "”")
                        .setPositiveButton("Use it now", (d, w) -> {
                            ThemeLibrary.apply(a, e);
                            if (afterInstall != null) afterInstall.run();
                            a.startActivity(new Intent(a, QuickMenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        })
                        .setNegativeButton("Later", (d, w) -> {
                            if (afterInstall != null) afterInstall.run();
                        }).show());
            } catch (Exception ex) {
                ui.post(() -> new DpDialog(a).setTitle("Couldn't install it")
                        .setMessage(String.valueOf(ex.getMessage())).setPositiveButton("OK", null).show());
            }
        });
    }

    /** Previews are kept in the cache, so browsing again is quick. */
    private void loadPreview(String path, ImageView into, int maxPx) {
        if (path.isEmpty() || !path.startsWith("catalog/") || path.contains("..")) return;
        bg.execute(() -> {
            try {
                File cache = new File(a.getCacheDir(), "catalog-" + path.replaceAll("[^A-Za-z0-9.]", "_"));
                byte[] data;
                if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(cache)) {
                        data = in.readAllBytes();
                    }
                } else {
                    data = get(BASE + path);
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(data);
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > maxPx * 2) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                ui.post(() -> into.setImageBitmap(b));
            } catch (Exception ignored) {
            }
        });
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "DockPad/" + BuildConfig.VERSION_CODE);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("the catalog answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 300_000_000L) throw new Exception("too big");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private TextView text(String s, boolean small) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextAppearance(small ? com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}
