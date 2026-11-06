package com.adonzin.dockpad;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pictures, GIFs and videos for the quick menu: the background, decorations placed around the screen,
 * and images used as icons. Files live in DockPad's media folder; themes refer to them by name.
 */
final class MenuMedia {
    // ------------------------------------------------------------------ settings (part of a theme)

    /** The background: a picture, GIF or video. */
    static final class Background {
        String file = "";          // "" = none
        String target = "screen";  // screen, panel
        String scale = "fill";     // fill, fit
        int blur = 0;              // 0-25
        int dim = 0;               // percent darker

        boolean isSet() {
            return !file.isEmpty();
        }
    }

    /** An image placed somewhere on screen. */
    static final class Decoration {
        String file = "";
        String anchor = "bottom-right"; // top-left ... bottom-right, center
        int x = 0, y = 0;               // nudge, percent of the screen
        int size = 25;                  // percent of the screen's height
        int opacity = 100;
        boolean front = false;          // in front of the menu (else behind it)
        String repeat = "none";         // none, across, down
        boolean mirror = false;
        int rotation = 0;               // degrees, -180..180
        boolean flip = false;           // upside down
        String motion = "none";         // none, float, sway, spin, pulse, blink, drift
        String motionSpeed = "normal";  // slow, normal, fast

        Decoration copy() {
            return decode(encode());
        }

        JSONObject encode() {
            JSONObject o = new JSONObject();
            try {
                o.put("file", file);
                o.put("anchor", anchor);
                o.put("x", x);
                o.put("y", y);
                o.put("size", size);
                o.put("opacity", opacity);
                o.put("front", front);
                o.put("repeat", repeat);
                o.put("mirror", mirror);
                o.put("rotation", rotation);
                o.put("flip", flip);
                o.put("motion", motion);
                o.put("motionSpeed", motionSpeed);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Decoration decode(JSONObject o) {
            Decoration d = new Decoration();
            if (o == null) return d;
            d.file = safeName(o.optString("file", ""));
            d.anchor = oneOf(o.optString("anchor", d.anchor), ANCHORS, "bottom-right");
            d.x = clamp(o.optInt("x", 0), -100, 100);
            d.y = clamp(o.optInt("y", 0), -100, 100);
            d.size = clamp(o.optInt("size", d.size), 2, 100);
            d.opacity = clamp(o.optInt("opacity", 100), 5, 100);
            d.front = o.optBoolean("front", false);
            d.repeat = oneOf(o.optString("repeat", "none"), REPEATS, "none");
            d.mirror = o.optBoolean("mirror", false);
            d.rotation = clamp(o.optInt("rotation", 0), -180, 180);
            d.flip = o.optBoolean("flip", false);
            d.motion = oneOf(o.optString("motion", "none"), MOTIONS, "none");
            d.motionSpeed = oneOf(o.optString("motionSpeed", "normal"), SPEEDS, "normal");
            return d;
        }
    }

    static final String[] ANCHORS = {"top-left", "top", "top-right", "left", "center", "right",
            "bottom-left", "bottom", "bottom-right"};
    static final String[] ANCHOR_LABELS = {"Top left", "Top", "Top right", "Left", "Centre", "Right",
            "Bottom left", "Bottom", "Bottom right"};
    static final String[] MOTIONS = {"none", "float", "sway", "spin", "pulse", "blink", "drift"};
    static final String[] MOTION_LABELS = {"Still", "Float up and down", "Sway", "Spin", "Pulse", "Blink",
            "Drift across the screen"};
    static final String[] SPEEDS = {"slow", "normal", "fast"};
    static final String[] SPEED_LABELS = {"Slow", "Normal", "Fast"};
    static final String[] REPEATS = {"none", "across", "down"};
    static final String[] REPEAT_LABELS = {"Once", "Repeat across the screen", "Repeat down the screen"};

    static Background parseBackground(JSONObject o) {
        Background b = new Background();
        if (o == null) return b;
        b.file = safeName(o.optString("file", ""));
        b.target = oneOf(o.optString("target", b.target), new String[]{"screen", "panel"}, "screen");
        b.scale = oneOf(o.optString("scale", b.scale), new String[]{"fill", "fit"}, "fill");
        b.blur = clamp(o.optInt("blur", 0), 0, 25);
        b.dim = clamp(o.optInt("dim", 0), 0, 90);
        return b;
    }

    static JSONObject encode(Background b) {
        JSONObject o = new JSONObject();
        try {
            o.put("file", b.file);
            o.put("target", b.target);
            o.put("scale", b.scale);
            o.put("blur", b.blur);
            o.put("dim", b.dim);
        } catch (Exception ignored) {
        }
        return o;
    }

    static List<Decoration> parseDecorations(JSONArray a) {
        List<Decoration> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length() && i < 30; i++) {
            Decoration d = Decoration.decode(a.optJSONObject(i));
            if (!d.file.isEmpty()) out.add(d);
        }
        return out;
    }

    static JSONArray encode(List<Decoration> list) {
        JSONArray a = new JSONArray();
        for (Decoration d : list) a.put(d.encode());
        return a;
    }

    private static String oneOf(String v, String[] options, String fallback) {
        for (String o : options) if (o.equals(v)) return v;
        return fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ files

    static String safeName(String n) {
        if (n == null) return "";
        return n.matches("[A-Za-z0-9._ -]{1,80}") ? n : "";
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "media");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static boolean isVideo(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") || n.endsWith(".3gp");
    }

    static boolean isAudio(String name) {
        return name.toLowerCase().matches(".*\\.(mp3|ogg|oga|opus|wav|m4a|aac|flac)$");
    }

    static boolean exists(Context c, String name) {
        return !name.isEmpty() && new File(dir(c), name).isFile();
    }

    /** Copies a picture, GIF or video the user picked into DockPad; returns its file name. */
    static String importFile(Context c, Uri uri) throws Exception {
        String name = "image.png";
        String mime = c.getContentResolver().getType(uri);
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) name = cur.getString(0);
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!name.contains(".") && mime != null) {
            if (mime.endsWith("gif")) name += ".gif";
            else if (mime.startsWith("video")) name += ".mp4";
            else if (mime.startsWith("audio")) name += ".mp3";
            else name += ".png";
        }
        if (name.length() > 70) name = name.substring(name.length() - 70);
        String lower = name.toLowerCase();
        boolean video = isVideo(lower), audio = isAudio(lower);
        boolean image = lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|heic)$");
        if (!video && !image && !audio) throw new Exception("pick a picture (PNG, JPG, GIF, WebP), a video (MP4, WebM) or a sound (MP3, OGG, WAV, M4A)");
        long max = video ? 150_000_000L : audio ? 40_000_000L : 25_000_000L;
        // never overwrite a different file with the same name
        File out = new File(dir(c), name);
        for (int i = 2; out.exists(); i++) {
            int dot = name.lastIndexOf('.');
            out = new File(dir(c), name.substring(0, dot) + "-" + i + name.substring(dot));
        }
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream fo = new FileOutputStream(out)) {
            if (in == null) throw new Exception("couldn't open the file");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new Exception("that file is too big (" + (max / 1_000_000) + " MB at most)");
                fo.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        if (image && load(c, out.getName(), 256) == null) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception("Android can't read that picture");
        }
        return out.getName();
    }

    static String[] list(Context c, boolean videosToo) {
        String[] all = dir(c).list();
        if (all == null) return new String[0];
        List<String> out = new ArrayList<>();
        for (String n : all) if (!isAudio(n) && (videosToo || !isVideo(n))) out.add(n);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.toArray(new String[0]);
    }

    static String[] listAudio(Context c) {
        String[] all = dir(c).list();
        if (all == null) return new String[0];
        List<String> out = new ArrayList<>();
        for (String n : all) if (isAudio(n)) out.add(n);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.toArray(new String[0]);
    }

    static void delete(Context c, String name) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), safeName(name)).delete();
    }

    /**
     * Loads a picture or GIF (animated, already started), scaled down so its longer side is at most
     * {@code maxPx}. Null if it can't be read.
     */
    static Drawable load(Context c, String name, int maxPx) {
        if (safeName(name).isEmpty() || isVideo(name) || isAudio(name)) return null;
        File f = new File(dir(c), name);
        if (!f.isFile()) return null;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(f);
            Drawable d = ImageDecoder.decodeDrawable(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float k = Math.min(1f, maxPx / (float) Math.max(1, Math.max(w, h)));
                if (k < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            if (d instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                ((AnimatedImageDrawable) d).start();
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A soft-focus copy of a still picture (cheap blur: shrink, then stretch back). */
    static Drawable blurred(Context c, Drawable d, int blur) {
        if (blur <= 0 || !(d instanceof BitmapDrawable)) return d;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        int k = 2 + blur; // stronger blur = smaller copy
        Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() / k), Math.max(1, b.getHeight() / k), true);
        Bitmap back = Bitmap.createScaledBitmap(small, b.getWidth(), b.getHeight(), true);
        return new BitmapDrawable(c.getResources(), back);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws another drawable to fill (crop) or fit its bounds, optionally inside rounded corners and
     * darkened. Keeps animated GIFs moving.
     */
    static final class Cover extends Drawable implements Drawable.Callback {
        final Drawable inner;
        final boolean fit;
        final float[] radii; // px, 8 values, or null
        final boolean oval;
        final int dimAlpha;
        private final Path clip = new Path();

        Cover(Drawable inner, boolean fit, float[] radii, boolean oval, int dimPercent) {
            this.inner = inner;
            this.fit = fit;
            this.radii = radii;
            this.oval = oval;
            this.dimAlpha = Math.round(dimPercent * 2.55f);
            inner.setCallback(this);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            clip.reset();
            if (oval) clip.addOval(new RectF(b), Path.Direction.CW);
            else if (radii != null) clip.addRoundRect(new RectF(b), radii, Path.Direction.CW);
            int iw = Math.max(1, inner.getIntrinsicWidth()), ih = Math.max(1, inner.getIntrinsicHeight());
            float k = fit ? Math.min(b.width() / (float) iw, b.height() / (float) ih)
                    : Math.max(b.width() / (float) iw, b.height() / (float) ih);
            int w = Math.round(iw * k), h = Math.round(ih * k);
            int x = b.left + (b.width() - w) / 2, y = b.top + (b.height() - h) / 2;
            inner.setBounds(x, y, x + w, y + h);
        }

        @Override
        public void draw(Canvas canvas) {
            int save = canvas.save();
            if (oval || radii != null) canvas.clipPath(clip);
            else canvas.clipRect(getBounds());
            inner.draw(canvas);
            if (dimAlpha > 0) canvas.drawColor(dimAlpha << 24);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            inner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }
}
