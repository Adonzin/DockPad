package com.adonzin.dockpad;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Quick menu themes: the built-in ones, themes saved or installed on this device, and theme files
 * (.dockpadtheme: a zip of theme.json plus the pictures, videos, sounds and fonts it uses).
 * Theme files hold settings and media only, never code.
 */
final class ThemeLibrary {
    static final String EXT = ".dockpadtheme";
    private static final long MAX_TOTAL = 300_000_000L;

    static final class Entry {
        String id, name, author = "", description = "";
        boolean builtIn;
        String json;
    }

    private ThemeLibrary() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "themes");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static String activeId(Context c) {
        return c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).getString("activeTheme", "builtin:default");
    }

    /** Built-in themes first, then the ones on this device (A to Z). */
    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>(BuiltInThemes.all());
        File[] dirs = dir(c).listFiles();
        List<Entry> mine = new ArrayList<>();
        if (dirs != null) for (File d : dirs) {
            File f = new File(d, "theme.json");
            if (!f.isFile()) continue;
            try {
                Entry e = fromJson(d.getName(), read(f), false);
                mine.add(e);
            } catch (Exception ignored) {
            }
        }
        mine.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        out.addAll(mine);
        return out;
    }

    static Entry fromJson(String id, String json, boolean builtIn) throws Exception {
        JSONObject o = new JSONObject(json);
        Entry e = new Entry();
        e.id = id;
        e.name = o.optString("name", "Untitled");
        e.author = o.optString("author", "");
        e.description = o.optString("description", "");
        e.builtIn = builtIn;
        e.json = json;
        return e;
    }

    /** Makes a theme the quick menu's look. */
    static void apply(Context c, Entry e) {
        MenuTheme t = MenuTheme.parse(e.json); // checked and cleaned on the way in
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit()
                .putString("menuTheme", t.toJson()).putString("activeTheme", e.id).apply();
    }

    /** Saves the current look as a theme on this device. */
    static Entry saveCurrent(Context c, String name, String author, String description) throws Exception {
        JSONObject o = new JSONObject(MenuTheme.load(c).toJson());
        o.put("name", name);
        o.put("author", author);
        o.put("description", description);
        String id = slug(name) + "-" + Long.toString(System.currentTimeMillis(), 36);
        File d = new File(dir(c), id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        write(new File(d, "theme.json"), o.toString(2));
        c.getSharedPreferences("dockpad", Context.MODE_PRIVATE).edit()
                .putString("menuTheme", o.toString()).putString("activeTheme", id).apply();
        return fromJson(id, o.toString(), false);
    }

    static void delete(Context c, Entry e) {
        if (e.builtIn) return;
        File d = new File(dir(c), e.id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    private static String slug(String s) {
        String x = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return x.isEmpty() ? "theme" : x.length() > 30 ? x.substring(0, 30) : x;
    }

    // ------------------------------------------------------------------ files a theme uses

    /** "media/<name>" and "fonts/<name>" paths of everything a theme refers to. */
    static Set<String> files(JSONObject o) {
        Set<String> out = new LinkedHashSet<>();
        JSONObject style = o.optJSONObject("style");
        if (style != null && MenuStyle.isFontFile(style.optString("font"))) out.add("fonts/" + style.optString("font").substring(5));
        JSONObject media = o.optJSONObject("media");
        if (media != null) {
            JSONObject bg = media.optJSONObject("background");
            if (bg != null && !bg.optString("file").isEmpty()) out.add("media/" + bg.optString("file"));
            JSONArray decs = media.optJSONArray("decorations");
            if (decs != null) for (int i = 0; i < decs.length(); i++) {
                JSONObject d = decs.optJSONObject(i);
                if (d != null && !d.optString("file").isEmpty()) out.add("media/" + d.optString("file"));
            }
        }
        JSONObject fx = o.optJSONObject("fx");
        if (fx != null) {
            if (!fx.optString("music").isEmpty()) out.add("media/" + fx.optString("music"));
            JSONObject snd = fx.optJSONObject("sounds");
            if (snd != null) for (java.util.Iterator<String> it = snd.keys(); it.hasNext(); ) {
                String v = snd.optString(it.next());
                if (v.startsWith("file:")) out.add("media/" + v.substring(5));
            }
        }
        MenuArt.files(o.optJSONObject("art"), out);
        return out;
    }

    /** Points a theme's file references at new names (after installing them under other names). */
    static void rename(JSONObject o, Map<String, String> media, Map<String, String> fonts) throws Exception {
        JSONObject style = o.optJSONObject("style");
        if (style != null && style.optString("font").startsWith("file:")) {
            String n = fonts.get(style.optString("font").substring(5));
            if (n != null) style.put("font", "file:" + n);
        }
        JSONObject m = o.optJSONObject("media");
        if (m != null) {
            JSONObject bg = m.optJSONObject("background");
            if (bg != null && media.containsKey(bg.optString("file"))) bg.put("file", media.get(bg.optString("file")));
            JSONArray decs = m.optJSONArray("decorations");
            if (decs != null) for (int i = 0; i < decs.length(); i++) {
                JSONObject d = decs.optJSONObject(i);
                if (d != null && media.containsKey(d.optString("file"))) d.put("file", media.get(d.optString("file")));
            }
        }
        JSONObject fx = o.optJSONObject("fx");
        if (fx != null) {
            if (media.containsKey(fx.optString("music"))) fx.put("music", media.get(fx.optString("music")));
            JSONObject snd = fx.optJSONObject("sounds");
            if (snd != null) for (java.util.Iterator<String> it = snd.keys(); it.hasNext(); ) {
                String k = it.next(), v = snd.optString(k);
                if (v.startsWith("file:") && media.containsKey(v.substring(5))) snd.put(k, "file:" + media.get(v.substring(5)));
            }
        }
        MenuArt.rename(o.optJSONObject("art"), media);
    }

    // ------------------------------------------------------------------ theme files

    /** Writes a theme (and its files) as a .dockpadtheme in Download/DockPad; returns its address. */
    static Uri export(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String name = slug(o.optString("name", "theme"));
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name + EXT);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DockPad");
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("couldn't create the file");
        try (OutputStream os = c.getContentResolver().openOutputStream(uri); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("theme.json"));
            zip.write(o.toString(2).getBytes("UTF-8"));
            zip.closeEntry();
            for (String path : files(o)) {
                File f = path.startsWith("fonts/") ? new File(MenuStyle.fontDir(c), path.substring(6))
                        : new File(MenuMedia.dir(c), path.substring(6));
                if (!f.isFile()) continue; // missing files are left out; the theme still works without them
                zip.putNextEntry(new ZipEntry(path));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
        return uri;
    }

    /** Installs a theme file. Only theme.json, media/ and fonts/ are read; everything is checked. */
    static Entry install(Context c, InputStream input) throws Exception {
        File tmp = new File(c.getCacheDir(), "theme-import");
        deleteTree(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        String json = null;
        long total = 0;
        List<String> got = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                boolean isJson = n.equals("theme.json");
                boolean isMedia = n.startsWith("media/") && !MenuMedia.safeName(n.substring(6)).isEmpty();
                boolean isFont = n.startsWith("fonts/") && !MenuMedia.safeName(n.substring(6)).isEmpty()
                        && n.toLowerCase().matches(".*\\.(ttf|otf)$");
                if (!isJson && !isMedia && !isFont) continue; // anything else is ignored
                File out = isJson ? new File(tmp, "theme.json") : new File(tmp, n.replace('/', '_'));
                long size = 0, limit = isJson ? 1_000_000 : 150_000_000;
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = zip.read(buf)) > 0) {
                        size += r;
                        total += r;
                        if (size > limit || total > MAX_TOTAL) throw new Exception("the theme file is too big");
                        fo.write(buf, 0, r);
                    }
                }
                if (isJson) json = read(out);
                else got.add(n);
            }
        }
        if (json == null) throw new Exception("that isn't a DockPad theme (no theme.json)");
        JSONObject o = new JSONObject(json);
        if (o.optInt("format", 1) > MenuTheme.FORMAT) throw new Exception("this theme needs a newer DockPad: update first");
        // move its files in, under new names if a different file already has the name
        Map<String, String> mediaNames = new HashMap<>(), fontNames = new HashMap<>();
        for (String n : got) {
            boolean font = n.startsWith("fonts/");
            String name = n.substring(6);
            File src = new File(tmp, n.replace('/', '_'));
            File destDir = font ? MenuStyle.fontDir(c) : MenuMedia.dir(c);
            File dest = new File(destDir, name);
            for (int i = 2; dest.exists() && !sameFile(dest, src); i++) {
                int dot = name.lastIndexOf('.');
                dest = new File(destDir, (dot > 0 ? name.substring(0, dot) : name) + "-" + i + (dot > 0 ? name.substring(dot) : ""));
            }
            if (!dest.exists() && !src.renameTo(dest)) copy(src, dest);
            if (font && MenuStyle.loadFont(c, dest.getName()) == null) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete(); // not a usable font
                continue;
            }
            (font ? fontNames : mediaNames).put(name, dest.getName());
        }
        rename(o, mediaNames, fontNames);
        // clean it through the theme reader, keep the description fields
        String name = o.optString("name", "Imported theme");
        JSONObject clean = new JSONObject(MenuTheme.parse(o.toString()).toJson());
        clean.put("name", name);
        clean.put("author", o.optString("author", ""));
        clean.put("description", o.optString("description", ""));
        String id = slug(name) + "-" + Long.toString(System.currentTimeMillis(), 36);
        File d = new File(dir(c), id);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        write(new File(d, "theme.json"), clean.toString(2));
        deleteTree(tmp);
        return fromJson(id, clean.toString(), false);
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean sameFile(File a, File b) {
        if (a.length() != b.length()) return false;
        try (FileInputStream x = new FileInputStream(a); FileInputStream y = new FileInputStream(b)) {
            byte[] p = new byte[65536], q = new byte[65536];
            int n;
            while ((n = x.read(p)) > 0) {
                int m = y.read(q, 0, n);
                if (m != n || !java.util.Arrays.equals(java.util.Arrays.copyOf(p, n), java.util.Arrays.copyOf(q, n))) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), "UTF-8");
        }
    }

    private static void write(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }
}
