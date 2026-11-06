package com.adonzin.dockpad;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;

import org.json.JSONObject;

/**
 * The look of the quick menu (part of a theme): colours and gradients, transparency, shapes, text,
 * highlight and header. Empty colours mean "follow the system".
 */
final class MenuStyle {
    String palette = "system";
    String panel1 = "", panel2 = "", panel3 = "";
    String gradient = "none"; // none, linear, radial, sweep
    int angle = 90;
    String accent = "", text = "", tile = "";
    int opacity = 90;   // panel, percent
    int dim = -1;       // game behind, percent; -1 = the style's own
    int corners = 24;   // dp
    int border = 0;     // dp
    String borderColor = "";
    boolean glow = false;
    int textSize = 100; // percent
    String font = "default";
    String highlight = "filled"; // filled, outline, glow, grow, underline
    boolean showTitle = true, showStatus = true, showClock = false, showBattery = false;

    static final String[] GRADIENTS = {"none", "linear", "radial", "sweep"};
    static final String[] GRADIENT_LABELS = {"Solid colour", "Linear gradient", "Radial gradient", "Sweep gradient"};
    static final String[] HIGHLIGHTS = {"filled", "outline", "glow", "grow", "underline"};
    static final String[] HIGHLIGHT_LABELS = {"Filled", "Outline", "Glow", "Grow", "Underline"};
    static final String[] FONTS = {"default", "condensed", "light", "medium", "serif", "mono", "casual", "cursive", "smallcaps"};
    static final String[] FONT_LABELS = {"Default", "Condensed", "Light", "Medium", "Serif", "Monospace", "Casual",
            "Handwriting", "Small caps"};

    /** name, label, panel1, panel2, gradient, angle, accent, text, tile */
    static final String[][] PALETTES = {
            {"system", "Follow the system", "", "", "none", "90", "", "", ""},
            {"midnight", "Midnight", "#0E1116", "", "none", "90", "#8AB4F8", "#FFFFFF", "#22FFFFFF"},
            {"ocean", "Ocean", "#0F2027", "#2C5364", "linear", "90", "#4FD1C5", "#FFFFFF", "#26FFFFFF"},
            {"sunset", "Sunset", "#2B1055", "#D53369", "linear", "135", "#FFB86C", "#FFFFFF", "#26FFFFFF"},
            {"forest", "Forest", "#0B1F14", "#1E3D2A", "linear", "90", "#7EE787", "#EFFFF2", "#22FFFFFF"},
            {"neon", "Neon", "#0D0221", "#261447", "radial", "90", "#FF2A6D", "#05D9E8", "#2205D9E8"},
            {"retro", "Retro terminal", "#050A05", "", "none", "90", "#39FF14", "#39FF14", "#1A39FF14"},
            {"candy", "Candy (light)", "#FFDEE9", "#B5FFFC", "linear", "45", "#D6336C", "#2B2B2B", "#55FFFFFF"},
            {"paper", "Paper (light)", "#F4F1EA", "", "none", "90", "#3B5BDB", "#1F1F1F", "#14000000"},
            {"mono", "Black & white", "#000000", "", "none", "90", "#FFFFFF", "#FFFFFF", "#26FFFFFF"},
            {"aurora", "Aurora", "#0B132B", "#1C7C54", "sweep", "90", "#73E2A7", "#FFFFFF", "#22FFFFFF"},
    };

    void applyPalette(String name) {
        for (String[] p : PALETTES) {
            if (!p[0].equals(name)) continue;
            palette = name;
            panel1 = p[2];
            panel2 = p[3];
            panel3 = "";
            gradient = p[4];
            angle = Integer.parseInt(p[5]);
            accent = p[6];
            text = p[7];
            tile = p[8];
            return;
        }
    }

    // ------------------------------------------------------------------ JSON

    static MenuStyle parse(JSONObject o) {
        MenuStyle s = new MenuStyle();
        if (o == null) return s;
        s.palette = o.optString("palette", s.palette);
        s.panel1 = color(o.optString("panel1", ""));
        s.panel2 = color(o.optString("panel2", ""));
        s.panel3 = color(o.optString("panel3", ""));
        s.gradient = oneOf(o.optString("gradient", s.gradient), GRADIENTS, "none");
        s.angle = clamp(o.optInt("angle", s.angle), 0, 359);
        s.accent = color(o.optString("accent", ""));
        s.text = color(o.optString("text", ""));
        s.tile = color(o.optString("tile", ""));
        s.opacity = clamp(o.optInt("opacity", s.opacity), 10, 100);
        s.dim = clamp(o.optInt("dim", s.dim), -1, 95);
        s.corners = clamp(o.optInt("corners", s.corners), 0, 48);
        s.border = clamp(o.optInt("border", s.border), 0, 8);
        s.borderColor = color(o.optString("borderColor", ""));
        s.glow = o.optBoolean("glow", s.glow);
        s.textSize = clamp(o.optInt("textSize", s.textSize), 70, 160);
        String f = o.optString("font", s.font);
        s.font = isFontFile(f) ? f : oneOf(f, FONTS, "default");
        s.highlight = oneOf(o.optString("highlight", s.highlight), HIGHLIGHTS, "filled");
        s.showTitle = o.optBoolean("showTitle", s.showTitle);
        s.showStatus = o.optBoolean("showStatus", s.showStatus);
        s.showClock = o.optBoolean("showClock", s.showClock);
        s.showBattery = o.optBoolean("showBattery", s.showBattery);
        return s;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("palette", palette);
            o.put("panel1", panel1);
            o.put("panel2", panel2);
            o.put("panel3", panel3);
            o.put("gradient", gradient);
            o.put("angle", angle);
            o.put("accent", accent);
            o.put("text", text);
            o.put("tile", tile);
            o.put("opacity", opacity);
            o.put("dim", dim);
            o.put("corners", corners);
            o.put("border", border);
            o.put("borderColor", borderColor);
            o.put("glow", glow);
            o.put("textSize", textSize);
            o.put("font", font);
            o.put("highlight", highlight);
            o.put("showTitle", showTitle);
            o.put("showStatus", showStatus);
            o.put("showClock", showClock);
            o.put("showBattery", showBattery);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String oneOf(String v, String[] options, String fallback) {
        for (String o : options) if (o.equals(v)) return v;
        return fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Keeps only valid "#RRGGBB" / "#AARRGGBB" colours. */
    static String color(String s) {
        if (s == null) return "";
        s = s.trim();
        if (!s.matches("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) return "";
        return s.toUpperCase();
    }

    // ------------------------------------------------------------------ resolved colours

    private static int attr(Context c, int a) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(a, tv, true);
        return tv.data;
    }

    static int parse(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    int accent(Context c) {
        return parse(accent, attr(c, androidx.appcompat.R.attr.colorPrimary));
    }

    /** The highlighted tile's fill. */
    int accentFill(Context c) {
        if (accent.isEmpty()) return attr(c, com.google.android.material.R.attr.colorPrimaryContainer);
        int a = accent(c);
        return Color.argb(110, Color.red(a), Color.green(a), Color.blue(a));
    }

    int text(Context c) {
        return parse(text, 0xFFFFFFFF);
    }

    int tile(Context c) {
        return parse(tile, 0x22FFFFFF);
    }

    private int withOpacity(int color) {
        int a = Math.round(Color.alpha(color) * opacity / 100f);
        if (Color.alpha(color) == 255) a = Math.round(255 * opacity / 100f);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    int[] panelColors() {
        int p1 = parse(panel1, 0xFF121519);
        if (gradient.equals("none")) return new int[]{withOpacity(p1)};
        int p2 = parse(panel2, p1);
        if (!panel3.isEmpty()) return new int[]{withOpacity(p1), withOpacity(p2), withOpacity(parse(panel3, p2))};
        return new int[]{withOpacity(p1), withOpacity(p2)};
    }

    // ------------------------------------------------------------------ drawables

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** The panel's background. {@code shape}: corner radii in dp at the theme's default 24 (scaled), or null for an oval. */
    GradientDrawable panel(Context c, float[] radiiAt24) {
        GradientDrawable d = new GradientDrawable();
        int[] cols = panelColors();
        if (cols.length == 1) d.setColor(cols[0]);
        else {
            d.setColors(cols);
            switch (gradient) {
                case "radial":
                    d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
                    d.setGradientRadius(dp(c, 520));
                    break;
                case "sweep":
                    d.setGradientType(GradientDrawable.SWEEP_GRADIENT);
                    break;
                default:
                    d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
                    d.setOrientation(orientation(angle));
            }
        }
        if (radiiAt24 == null) d.setShape(GradientDrawable.OVAL);
        else {
            float k = corners / 24f;
            float[] r = new float[8];
            for (int i = 0; i < 8; i++) r[i] = dp(c, radiiAt24[i] * k);
            d.setCornerRadii(r);
        }
        if (border > 0) d.setStroke(dp(c, border), parse(borderColor, accent(c)));
        else if (glow) {
            int a = accent(c);
            d.setStroke(dp(c, 3), Color.argb(150, Color.red(a), Color.green(a), Color.blue(a)));
        }
        return d;
    }

    static GradientDrawable.Orientation orientation(int angle) {
        int a = ((angle % 360) + 360) % 360;
        int step = Math.round(a / 45f) % 8;
        switch (step) {
            case 0: return GradientDrawable.Orientation.LEFT_RIGHT;
            case 1: return GradientDrawable.Orientation.BL_TR;
            case 2: return GradientDrawable.Orientation.BOTTOM_TOP;
            case 3: return GradientDrawable.Orientation.BR_TL;
            case 4: return GradientDrawable.Orientation.RIGHT_LEFT;
            case 5: return GradientDrawable.Orientation.TR_BL;
            case 6: return GradientDrawable.Orientation.TOP_BOTTOM;
            default: return GradientDrawable.Orientation.TL_BR;
        }
    }

    /** A tile's background, with the highlight style for when it's selected. */
    Drawable tile(Context c, boolean round, boolean rowLike) {
        int normalFill = rowLike ? 0x00000000 : tile(c);
        int radius = round ? 0 : dp(c, Math.max(4, corners * 0.66f));
        GradientDrawable normal = shape(round, radius, normalFill);
        Drawable focused;
        int a = accent(c);
        switch (highlight) {
            case "outline": {
                GradientDrawable f = shape(round, radius, normalFill == 0 ? 0x10FFFFFF : normalFill);
                f.setStroke(dp(c, 3), a);
                focused = f;
                break;
            }
            case "glow": {
                GradientDrawable halo = shape(round, radius, Color.argb(60, Color.red(a), Color.green(a), Color.blue(a)));
                halo.setStroke(dp(c, 6), Color.argb(90, Color.red(a), Color.green(a), Color.blue(a)));
                GradientDrawable core = shape(round, radius, accentFill(c));
                core.setStroke(dp(c, 2), a);
                LayerDrawable l = new LayerDrawable(new Drawable[]{halo, core});
                int in = dp(c, 3);
                l.setLayerInset(1, in, in, in, in);
                focused = l;
                break;
            }
            case "underline": {
                GradientDrawable base = shape(round, radius, normalFill == 0 ? 0x14FFFFFF : normalFill);
                GradientDrawable bar = new GradientDrawable();
                bar.setColor(a);
                bar.setCornerRadius(dp(c, 2));
                LayerDrawable l = new LayerDrawable(new Drawable[]{base, bar});
                l.setLayerGravity(1, android.view.Gravity.BOTTOM | android.view.Gravity.FILL_HORIZONTAL);
                l.setLayerHeight(1, dp(c, 4));
                l.setLayerInsetLeft(1, dp(c, 10));
                l.setLayerInsetRight(1, dp(c, 10));
                l.setLayerInsetBottom(1, dp(c, 3));
                focused = l;
                break;
            }
            case "grow": {
                GradientDrawable f = shape(round, radius, accentFill(c));
                focused = f;
                break;
            }
            default: { // filled
                GradientDrawable f = shape(round, radius, accentFill(c));
                if (round) f.setStroke(dp(c, 3), a);
                focused = f;
            }
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    private static GradientDrawable shape(boolean round, int radius, int fill) {
        GradientDrawable d = new GradientDrawable();
        if (round) d.setShape(GradientDrawable.OVAL);
        else d.setCornerRadius(radius);
        d.setColor(fill);
        return d;
    }

    /** Whether the white focus ring is drawn on top (the classic look). */
    boolean ring() {
        return highlight.equals("filled");
    }

    /** "Grow": selected tiles scale up a little. */
    StateListAnimator growAnimator() {
        if (!highlight.equals("grow")) return null;
        StateListAnimator s = new StateListAnimator();
        s.addState(new int[]{android.R.attr.state_focused}, scale(1.1f));
        s.addState(new int[]{}, scale(1f));
        return s;
    }

    private static AnimatorSet scale(float to) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(null, View.SCALE_X, to), ObjectAnimator.ofFloat(null, View.SCALE_Y, to));
        set.setDuration(120);
        return set;
    }

    // ------------------------------------------------------------------ fonts

    /** Imported fonts are stored as "file:<name>" (a file in DockPad's fonts folder). */
    static boolean isFontFile(String f) {
        return f != null && f.startsWith("file:") && f.substring(5).matches("[A-Za-z0-9._ -]{1,60}");
    }

    static java.io.File fontDir(Context c) {
        java.io.File d = new java.io.File(c.getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Imported font file names, A to Z. */
    static String[] importedFonts(Context c) {
        String[] names = fontDir(c).list();
        if (names == null) return new String[0];
        java.util.Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static final java.util.Map<String, Typeface> loaded = new java.util.HashMap<>();

    static Typeface loadFont(Context c, String fileName) {
        Typeface t = loaded.get(fileName);
        if (t != null) return t;
        try {
            java.io.File f = new java.io.File(fontDir(c), fileName);
            if (!f.isFile()) return null;
            t = Typeface.createFromFile(f);
            loaded.put(fileName, t);
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Copies a font the user picked into DockPad; returns its file name. */
    static String importFont(Context c, android.net.Uri uri) throws Exception {
        String name = "font.ttf";
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) name = cur.getString(0);
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.length() > 60) name = name.substring(name.length() - 60);
        String lower = name.toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) throw new Exception("pick a .ttf or .otf font file");
        java.io.File out = new java.io.File(fontDir(c), name);
        long total = 0;
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
            if (in == null) throw new Exception("couldn't open the file");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 15_000_000) throw new Exception("that font is too big (15 MB at most)");
                fo.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        loaded.remove(name);
        try {
            Typeface.createFromFile(out); // throws if it isn't a usable font
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception("Android can't read that font");
        }
        return name;
    }

    static void removeFont(Context c, String fileName) {
        loaded.remove(fileName);
        //noinspection ResultOfMethodCallIgnored
        new java.io.File(fontDir(c), fileName).delete();
    }

    Typeface typeface(Context c, boolean bold) {
        if (isFontFile(font)) {
            Typeface t = loadFont(c, font.substring(5));
            if (t != null) return bold ? Typeface.create(t, Typeface.BOLD) : t;
        }
        return typeface(bold);
    }

    Typeface typeface(boolean bold) {
        String fam;
        switch (font) {
            case "condensed": fam = "sans-serif-condensed"; break;
            case "light": fam = "sans-serif-light"; break;
            case "medium": fam = "sans-serif-medium"; break;
            case "serif": fam = "serif"; break;
            case "mono": fam = "monospace"; break;
            case "casual": fam = "casual"; break;
            case "cursive": fam = "cursive"; break;
            case "smallcaps": fam = "sans-serif-smallcaps"; break;
            default: fam = "sans-serif";
        }
        return Typeface.create(fam, bold ? Typeface.BOLD : Typeface.NORMAL);
    }
}
