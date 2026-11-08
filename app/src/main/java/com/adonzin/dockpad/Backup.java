package com.adonzin.dockpad;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Backup and sharing of DockPad's setup as one small JSON file. Pairing keys, the service token and
 * anything tied to this particular handheld are never included.
 */
final class Backup {
    static final String[] SECTION_IDS = {"shortcuts", "quickmenu", "controllers", "settings"};
    static final String[] SECTION_LABELS = {"Shortcuts & macros", "Quick menu (commands, items, look and layout)",
            "Controllers (names, button fixes, rapid fire, fine-tuning)", "Settings"};
    private static final String[][] SECTION_KEYS = {
            {"shortcuts"},
            {"menuCommands", "menuHidden", "menuTheme", "menuOrder", "menuCustom"},
            {"layouts", "controllerNames"},
            {"multiplayer", "joinByPress", "touchpadAsButton", "rumble", "includeBuiltIn", "autoStart", "turnOffWD", "stayReadyDocked",
                    "pcSpeed", "pcPointerLeft", "autoUpdateCheck", "closeMenuWithoutControllers"},
    };
    private static final String FLOATS = "pcSpeed";

    private Backup() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dockpad", Context.MODE_PRIVATE);
    }

    static String toJson(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        JSONObject sections = new JSONObject();
        for (int i = 0; i < SECTION_IDS.length; i++) {
            JSONObject s = new JSONObject();
            for (String k : SECTION_KEYS[i]) {
                Object v = p.getAll().get(k);
                if (v != null) s.put(k, v);
            }
            if (s.length() > 0) sections.put(SECTION_IDS[i], s);
        }
        JSONObject o = new JSONObject();
        o.put("app", "DockPad");
        o.put("format", 1);
        o.put("build", BuildConfig.VERSION_CODE);
        o.put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
        o.put("sections", sections);
        return o.toString(2);
    }

    /** Saves a backup to Download/DockPad and returns its address (for sharing). */
    static Uri save(Context c) throws Exception {
        String name = "DockPad-backup-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date()) + ".json";
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DockPad");
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("couldn't create the file");
        try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("couldn't write the file");
            out.write(toJson(c).getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    static String fileName(Context c, Uri uri) {
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return "the backup";
    }

    /** A backup file that has been read and checked. */
    static final class Contents {
        JSONObject sections;
        String created;
        int build;
        final List<Integer> present = new ArrayList<>(); // indexes into SECTION_IDS

        String describe(int section) {
            JSONObject s = sections.optJSONObject(SECTION_IDS[section]);
            if (s == null) return "";
            switch (SECTION_IDS[section]) {
                case "shortcuts": return count(s.optString("shortcuts"), "shortcut");
                case "quickmenu": return count(s.optString("menuCommands"), "command");
                case "controllers": return count(s.optString("layouts"), "controller");
                default: return s.length() + " settings";
            }
        }

        private static String count(String lines, String what) {
            int n = 0;
            for (String l : lines.split("\n")) if (!l.trim().isEmpty()) n++;
            return n + " " + what + (n == 1 ? "" : "s");
        }
    }

    static Contents read(Context c, Uri uri) throws Exception {
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("couldn't open the file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2_000_000) throw new Exception("that file is too big to be a DockPad backup");
            }
            data = out.toByteArray();
        }
        JSONObject o;
        try {
            o = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new Exception("that isn't a DockPad backup");
        }
        if (!"DockPad".equals(o.optString("app")) || o.optJSONObject("sections") == null) {
            throw new Exception("that isn't a DockPad backup");
        }
        if (o.optInt("format", 1) > 1) throw new Exception("this backup is from a newer DockPad: update first");
        Contents r = new Contents();
        r.sections = o.getJSONObject("sections");
        r.created = o.optString("created");
        r.build = o.optInt("build");
        for (int i = 0; i < SECTION_IDS.length; i++) if (r.sections.has(SECTION_IDS[i])) r.present.add(i);
        if (r.present.isEmpty()) throw new Exception("the backup is empty");
        return r;
    }

    /** Replaces the chosen sections with the backup's. */
    static void restore(Context c, Contents b, List<Integer> chosen) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i : chosen) {
            JSONObject s = b.sections.optJSONObject(SECTION_IDS[i]);
            if (s == null) continue;
            for (String k : SECTION_KEYS[i]) e.remove(k); // replace, don't mix
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!allowed(i, k)) continue; // only known keys: a file can't set anything else
                Object v = s.opt(k);
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Number && FLOATS.contains(k)) e.putFloat(k, ((Number) v).floatValue());
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Number) e.putLong(k, ((Number) v).longValue());
                else if (v instanceof String) e.putString(k, (String) v);
            }
        }
        e.apply();
    }

    private static boolean allowed(int section, String key) {
        for (String k : SECTION_KEYS[section]) if (k.equals(key)) return true;
        return false;
    }
}
