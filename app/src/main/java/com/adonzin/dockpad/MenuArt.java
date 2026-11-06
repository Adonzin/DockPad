package com.adonzin.dockpad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Pictures a theme uses for the menu's items (the "art" part of theme.json):
 * picture buttons with a separate highlighted picture, per-item buttons and icons, hidden labels,
 * and a pointer picture that follows the highlight.
 */
final class MenuArt {
    /** One item's own pictures (empty = use the theme-wide ones). */
    static final class ItemArt {
        String button = "", buttonOn = "", icon = "", iconOn = "";
        Boolean labels; // null = the theme-wide setting
    }

    String button = "", buttonOn = "";
    /** Percent of each edge of a button picture kept unstretched (nine-slice); 0 = stretch the whole picture. */
    int slice = 0;
    boolean labels = true;
    final Map<String, ItemArt> items = new HashMap<>();
    String cursor = "";
    String cursorSide = "left";   // left, right, top, bottom
    int cursorSize = 70;          // percent of the highlighted item's height
    String cursorMotion = "bob";  // none, bob, pulse, spin

    static final String[] SIDES = {"left", "right", "top", "bottom"};
    static final String[] CURSOR_MOTIONS = {"none", "bob", "pulse", "spin"};

    static MenuArt parse(JSONObject o) {
        MenuArt a = new MenuArt();
        if (o == null) return a;
        a.button = MenuMedia.safeName(o.optString("button", ""));
        a.buttonOn = MenuMedia.safeName(o.optString("buttonOn", ""));
        a.slice = Math.max(0, Math.min(45, o.optInt("slice", 0)));
        a.labels = o.optBoolean("labels", true);
        JSONObject its = o.optJSONObject("items");
        if (its != null) for (Iterator<String> k = its.keys(); k.hasNext(); ) {
            String id = k.next();
            JSONObject x = its.optJSONObject(id);
            if (x == null || id.length() > 120) continue;
            ItemArt ia = new ItemArt();
            ia.button = MenuMedia.safeName(x.optString("button", ""));
            ia.buttonOn = MenuMedia.safeName(x.optString("buttonOn", ""));
            ia.icon = MenuMedia.safeName(x.optString("icon", ""));
            ia.iconOn = MenuMedia.safeName(x.optString("iconOn", ""));
            if (x.has("labels")) ia.labels = x.optBoolean("labels", true);
            a.items.put(id, ia);
            if (a.items.size() >= 200) break;
        }
        JSONObject c = o.optJSONObject("cursor");
        if (c != null) {
            a.cursor = MenuMedia.safeName(c.optString("file", ""));
            a.cursorSide = oneOf(c.optString("side", a.cursorSide), SIDES, "left");
            a.cursorSize = Math.max(20, Math.min(200, c.optInt("size", a.cursorSize)));
            a.cursorMotion = oneOf(c.optString("motion", a.cursorMotion), CURSOR_MOTIONS, "bob");
        }
        return a;
    }

    private static String oneOf(String v, String[] options, String fallback) {
        for (String s : options) if (s.equals(v)) return v;
        return fallback;
    }

    boolean any() {
        return !button.isEmpty() || !items.isEmpty() || !cursor.isEmpty() || !labels;
    }

    /** An item's own pictures. Every controller entry shares "controllers"; every own command shares "commands". */
    ItemArt of(String id) {
        ItemArt ia = items.get(id);
        if (ia == null && id.startsWith("ctrl:")) ia = items.get("controllers");
        if (ia == null && id.startsWith("cmd:")) ia = items.get("commands");
        return ia;
    }

    boolean labelsFor(String id) {
        ItemArt ia = of(id);
        return ia != null && ia.labels != null ? ia.labels : labels;
    }

    // ------------------------------------------------------------------ theme files

    /** Media file names the "art" part refers to. */
    static void files(JSONObject art, Set<String> out) {
        if (art == null) return;
        add(out, art.optString("button"));
        add(out, art.optString("buttonOn"));
        JSONObject its = art.optJSONObject("items");
        if (its != null) for (Iterator<String> k = its.keys(); k.hasNext(); ) {
            JSONObject x = its.optJSONObject(k.next());
            if (x == null) continue;
            for (String f : new String[]{"button", "buttonOn", "icon", "iconOn"}) add(out, x.optString(f));
        }
        JSONObject c = art.optJSONObject("cursor");
        if (c != null) add(out, c.optString("file"));
    }

    private static void add(Set<String> out, String name) {
        if (name != null && !MenuMedia.safeName(name).isEmpty()) out.add("media/" + name);
    }

    /** Points the "art" part's files at new names. */
    static void rename(JSONObject art, Map<String, String> media) throws Exception {
        if (art == null) return;
        for (String f : new String[]{"button", "buttonOn"}) swap(art, f, media);
        JSONObject its = art.optJSONObject("items");
        if (its != null) for (Iterator<String> k = its.keys(); k.hasNext(); ) {
            JSONObject x = its.optJSONObject(k.next());
            if (x != null) for (String f : new String[]{"button", "buttonOn", "icon", "iconOn"}) swap(x, f, media);
        }
        JSONObject c = art.optJSONObject("cursor");
        if (c != null) swap(c, "file", media);
    }

    private static void swap(JSONObject o, String key, Map<String, String> media) throws Exception {
        String n = media.get(o.optString(key));
        if (n != null) o.put(key, n);
    }

    // ------------------------------------------------------------------ drawables

    /** A button picture, and its highlighted picture (or a brighter copy of the normal one). */
    static Drawable button(Context c, String normal, String on, int slice) {
        Drawable n = picture(c, normal, slice);
        if (n == null) return null;
        Drawable f = on.isEmpty() ? null : picture(c, on, slice);
        if (f == null) {
            f = picture(c, normal, slice);
            if (f != null) f.setColorFilter(new LightingColorFilter(0xFFFFFFFF, 0x00383838));
        }
        StateListDrawable s = new StateListDrawable();
        if (f != null) {
            s.addState(new int[]{android.R.attr.state_focused}, f);
            s.addState(new int[]{android.R.attr.state_pressed}, f);
        }
        s.addState(new int[]{}, n);
        return s;
    }

    /** A picture that fills its space: nine-sliced when {@code slice} > 0 (still pictures only). */
    static Drawable picture(Context c, String file, int slice) {
        if (file.isEmpty() || !MenuMedia.exists(c, file)) return null;
        Drawable d = MenuMedia.load(c, file, 768);
        if (d == null) return null;
        if (slice > 0 && d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return new Slice(((BitmapDrawable) d).getBitmap(), slice);
        }
        return d;
    }

    /** Nine-slice: the corners keep their shape, the edges and middle stretch. */
    static final class Slice extends Drawable {
        private final Bitmap bmp;
        private final int pct;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        Slice(Bitmap bmp, int pct) {
            this.bmp = bmp;
            this.pct = pct;
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (b.width() <= 0 || b.height() <= 0 || bw <= 0 || bh <= 0) return;
            int sx = bw * pct / 100, sy = bh * pct / 100;
            float k = Math.min(b.height() / (float) bh, b.width() / (float) bw);
            int dx = Math.min(Math.round(sx * k), b.width() / 2), dy = Math.min(Math.round(sy * k), b.height() / 2);
            int[] srcX = {0, sx, bw - sx, bw}, srcY = {0, sy, bh - sy, bh};
            int[] dstX = {b.left, b.left + dx, b.right - dx, b.right}, dstY = {b.top, b.top + dy, b.bottom - dy, b.bottom};
            Rect s = new Rect(), d = new Rect();
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                s.set(srcX[i], srcY[j], srcX[i + 1], srcY[j + 1]);
                d.set(dstX[i], dstY[j], dstX[i + 1], dstY[j + 1]);
                if (s.isEmpty() || d.isEmpty()) continue;
                cv.drawBitmap(bmp, s, d, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
