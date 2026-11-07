package com.adonzin.dockpad;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Draws the quick menu's items in each layout, and moves the highlight between them. */
final class MenuRender {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    /** Icons chosen for sections (shown name -> drawable), for styles that show section icons. */
    static final java.util.Map<String, Integer> sectionIcons = new java.util.HashMap<>();

    private MenuRender() {
    }

    /** One menu entry. {@code action == null}: shown, but not selectable (a note). */
    static final class Item {
        final String id;      // unique (e.g. "home", "ctrl:Wireless Controller")
        final String group;   // what moves together when arranging ("controllers" for all controllers)
        final String section;
        final int icon;
        final String title, subtitle, badge;
        final Runnable action;
        /** An image of the user's (media file) instead of {@link #icon}. */
        String iconFile;

        Item(String id, String group, String section, int icon, String title, String subtitle, String badge,
             Runnable action) {
            this.id = id;
            this.group = group;
            this.section = section;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.action = action;
        }
    }

    /** Shows an item's icon: the user's own image if it has one, else the built-in icon (tinted). */
    static void setIcon(ImageView v, Item it) {
        if (it.iconFile != null) {
            android.graphics.drawable.Drawable d = MenuMedia.load(v.getContext(), it.iconFile, 320);
            if (d != null) {
                v.setImageTintList(null); // keep the picture's own colours
                v.setImageDrawable(d);
                return;
            }
        }
        if (it.icon != 0) v.setImageResource(it.icon);
    }

    /** A drawn menu: its view, and the selectable tiles in order. */
    abstract static class Renderer {
        final Context c;
        final MenuTheme theme;
        View view;
        final List<View> tiles = new ArrayList<>();
        final List<Item> selectable = new ArrayList<>();

        Renderer(Context c, MenuTheme theme) {
            this.c = c;
            this.theme = theme;
        }

        abstract void build(List<Item> items);

        /** Where the highlight goes from {@code cur} in a direction; -1 = stay. {@code fresh}: not a held repeat. */
        int move(int cur, int dir, boolean fresh) {
            return spatial(cur, dir, fresh);
        }

        /** L1/R1 (delta -1/+1): rings, tabs...; -1 = nothing. */
        int shoulder(int cur, int delta) {
            return -1;
        }

        /** Radial: the item the stick points at, or -1. */
        int aim(float x, float y, int cur) {
            return -1;
        }

        /** Arranging: how many places an item moves for a direction (0 = not this way). */
        int arrangeStep(int dir) {
            return dir == UP || dir == LEFT ? -1 : 1;
        }

        void onSelected(int index) {
        }

        /** B inside a style's own level (radial sections): handle it and return true, or false to close. */
        boolean back(int cur) {
            return false;
        }

        /** Set by the menu: highlights an item (for styles that move the highlight themselves). */
        java.util.function.IntConsumer go;

        int dp(float v) {
            return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
        }

        /** Picks the nearest tile in a direction by screen position (with wrap-around on a fresh press). */
        int spatial(int cur, int dir, boolean fresh) {
            if (tiles.isEmpty()) return -1;
            if (cur < 0 || cur >= tiles.size()) return 0;
            float[] from = center(tiles.get(cur));
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < tiles.size(); i++) {
                if (i == cur || !tiles.get(i).isShown()) continue;
                float[] p = center(tiles.get(i));
                float dx = p[0] - from[0], dy = p[1] - from[1];
                float primary = dir == UP ? -dy : dir == DOWN ? dy : dir == LEFT ? -dx : dx;
                float secondary = Math.abs(dir == UP || dir == DOWN ? dx : dy);
                if (primary <= dp(4)) continue;
                double score = primary + secondary * 2.5;
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0 || !fresh) return best;
            // wrap: the farthest tile the other way, closest in line
            double far = -Double.MAX_VALUE;
            for (int i = 0; i < tiles.size(); i++) {
                if (i == cur || !tiles.get(i).isShown()) continue;
                float[] p = center(tiles.get(i));
                float dx = p[0] - from[0], dy = p[1] - from[1];
                float back = dir == UP ? dy : dir == DOWN ? -dy : dir == LEFT ? dx : -dx;
                float secondary = Math.abs(dir == UP || dir == DOWN ? dx : dy);
                double score = back - secondary * 2.5;
                if (back > dp(4) && score > far) {
                    far = score;
                    best = i;
                }
            }
            return best;
        }

        static float[] center(View v) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return new float[]{loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f};
        }

        int color(int attr) {
            TypedValue tv = new TypedValue();
            c.getTheme().resolveAttribute(attr, tv, true);
            return tv.data;
        }

        TextView sectionLabel(String text) {
            TextView t = new TextView(c);
            t.setText(text.toUpperCase());
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(6), dp(14), 0, dp(2));
            return t;
        }

        TextView note(Item it) {
            TextView t = new TextView(c);
            t.setText(it.title);
            t.setPadding(dp(6), dp(6), 0, dp(6));
            return t;
        }

        void selectable(View v, Item it) {
            v.setFocusable(true);
            v.setClickable(true);
            v.setDefaultFocusHighlightEnabled(false); // drawn by our own outline
            v.setOnClickListener(x -> it.action.run());
            tiles.add(v);
            selectable.add(it);
        }

        /** A tile: icon over the title, or (when small) the title over a faded icon. */
        View tile(Item it, boolean big) {
            return new SmartTile(c, it, false, color(androidx.appcompat.R.attr.colorPrimary), tileBackground(false));
        }

        /** Rows of equal tiles (grid, tabs). */
        LinearLayout gridOf(List<Item> items, int cols, int tileH) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = null;
            int inRow = 0;
            for (Item it : items) {
                if (it.action == null) continue;
                if (row == null || inRow == cols) {
                    padRow(row, inRow, cols, tileH);
                    row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    list.addView(row);
                    inRow = 0;
                }
                View t = tile(it, true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, tileH, 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(t, lp);
                selectable(t, it);
                inRow++;
            }
            padRow(row, inRow, cols, tileH);
            return list;
        }

        void padRow(LinearLayout row, int inRow, int cols, int tileH) {
            if (row == null) return;
            for (int i = inRow; i < cols; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, tileH, 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(new View(c), lp);
            }
        }

        /** An icon-only square tile (dock, rail). */
        View iconTile(Item it, int size) {
            android.widget.FrameLayout t = new android.widget.FrameLayout(c);
            t.setBackground(tileBackground(false));
            t.setForeground(c.getDrawable(R.drawable.focus_ring));
            ImageView icon = new ImageView(c);
            icon.setImageTintList(ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
            setIcon(icon, it);
            int s = (int) (size * 0.55f);
            t.addView(icon, new android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            if (it.badge != null) {
                TextView b = new TextView(c);
                b.setTag("badge");
                b.setText(it.badge);
                b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
                b.setBackgroundResource(R.drawable.badge_bg);
                b.setPadding(dp(5), 0, dp(5), 0);
                t.addView(b, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
            }
            return t;
        }

        android.graphics.drawable.Drawable tileBackground(boolean round) {
            return theme.style.tile(c, round, false);
        }
    }

    // ------------------------------------------------------------------ list / compact

    static final class ListRenderer extends Renderer {
        final boolean compact;

        ListRenderer(Context c, MenuTheme t, boolean compact) {
            super(c, t);
            this.compact = compact;
        }

        @Override
        void build(List<Item> items) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            String lastSection = null;
            LayoutInflater inf = LayoutInflater.from(c);
            for (Item it : items) {
                if (theme.sections && !it.section.equals(lastSection)) list.addView(sectionLabel(it.section));
                lastSection = it.section;
                if (it.action == null) {
                    list.addView(note(it));
                    continue;
                }
                View r = inf.inflate(R.layout.item_row, list, false);
                setIcon(r.findViewById(R.id.row_icon), it);
                ((TextView) r.findViewById(R.id.row_title)).setText(it.title);
                TextView sub = r.findViewById(R.id.row_subtitle);
                if (it.subtitle == null || compact) sub.setVisibility(View.GONE);
                else sub.setText(it.subtitle);
                if (it.badge != null) {
                    TextView b = r.findViewById(R.id.row_badge);
                    b.setVisibility(View.VISIBLE);
                    b.setText(it.badge);
                }
                r.setBackground(theme.style.tile(c, false, true));
                if (compact) {
                    r.setMinimumHeight(dp(40));
                    r.setPadding(dp(6), dp(3), dp(6), dp(3));
                    ImageView icon = r.findViewById(R.id.row_icon);
                    ViewGroup.LayoutParams lp = icon.getLayoutParams();
                    lp.width = lp.height = dp(20);
                    ((TextView) r.findViewById(R.id.row_title)).setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                }
                selectable(r, it);
                list.addView(r);
            }
            view = list;
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            if (tiles.isEmpty()) return -1;
            if (dir == LEFT || dir == RIGHT) return -1;
            int n = tiles.size();
            int next = cur + (dir == UP ? -1 : 1);
            if (next < 0) return fresh ? n - 1 : -1;
            if (next >= n) return fresh ? 0 : -1;
            return next;
        }

        @Override
        int arrangeStep(int dir) {
            return dir == UP ? -1 : dir == DOWN ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ grid

    static final class GridRenderer extends Renderer {
        GridRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        @Override
        void build(List<Item> items) {
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            int cols = theme.position.equals("full") ? Math.max(theme.columns, 4) : theme.columns;
            String lastSection = null;
            LinearLayout row = null;
            int inRow = 0;
            for (Item it : items) {
                boolean newSection = !it.section.equals(lastSection);
                if (theme.sections && newSection) {
                    finishRow(row, inRow, cols);
                    row = null;
                    list.addView(sectionLabel(it.section));
                }
                lastSection = it.section;
                if (it.action == null) {
                    finishRow(row, inRow, cols);
                    row = null;
                    list.addView(note(it));
                    continue;
                }
                if (row == null || inRow == cols) {
                    finishRow(row, inRow, cols);
                    row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    list.addView(row);
                    inRow = 0;
                }
                View t = tile(it, true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(t, lp);
                selectable(t, it);
                inRow++;
            }
            finishRow(row, inRow, cols);
            view = list;
        }

        /** Pads a short last row so its tiles keep the same size. */
        private void finishRow(LinearLayout row, int inRow, int cols) {
            if (row == null) return;
            for (int i = inRow; i < cols; i++) {
                View pad = new View(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(pad, lp);
            }
        }

        @Override
        int arrangeStep(int dir) {
            int cols = theme.position.equals("full") ? Math.max(theme.columns, 4) : theme.columns;
            return dir == LEFT ? -1 : dir == RIGHT ? 1 : dir == UP ? -cols : cols;
        }
    }

    // ------------------------------------------------------------------ freeform

    /** Lays children out at fractions of its own size. */
    static final class FreeCanvas extends ViewGroup {
        FreeCanvas(Context c) {
            super(c);
        }

        static float[] rect(View v) {
            return (float[]) v.getTag(R.id.row_title);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int W = MeasureSpec.getSize(w), H = MeasureSpec.getSize(h);
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                float[] r = rect(ch);
                if (r == null) continue;
                ch.measure(MeasureSpec.makeMeasureSpec(Math.max(1, (int) (r[2] * W)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(1, (int) (r[3] * H)), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(W, H);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int W = r - l, H = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                float[] f = rect(ch);
                if (f == null) continue;
                int x = (int) (f[0] * W), y = (int) (f[1] * H);
                ch.layout(x, y, x + ch.getMeasuredWidth(), y + ch.getMeasuredHeight());
            }
        }
    }

    static final class FreeRenderer extends Renderer {
        FreeCanvas canvas;
        final List<float[]> rects = new ArrayList<>();

        FreeRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        @Override
        void build(List<Item> items) {
            canvas = new FreeCanvas(c);
            int cols = Math.max(2, theme.columns);
            float w = 1f / cols, h = 0.13f;
            int auto = 0;
            String lastGroup = null;
            float[] groupStart = null;
            int inGroup = 0;
            for (Item it : items) {
                if (it.action == null) continue;
                float[] r = theme.free.get(it.id);
                if (r == null && it.group.equals("controllers")) {
                    // controllers come and go: stack them below the block's spot
                    if (!it.group.equals(lastGroup)) {
                        groupStart = theme.free.get("controllers");
                        inGroup = 0;
                    }
                    if (groupStart != null) {
                        r = new float[]{groupStart[0], groupStart[1] + inGroup * groupStart[3], groupStart[2], groupStart[3]};
                    }
                    inGroup++;
                }
                lastGroup = it.group;
                if (r == null) {
                    r = new float[]{(auto % cols) * w + 0.01f, (auto / cols) * h + 0.01f, w - 0.02f, h - 0.02f};
                    auto++;
                } else {
                    r = r.clone();
                }
                View t = tile(it, false);
                t.setTag(R.id.row_title, r);
                canvas.addView(t);
                rects.add(r);
                selectable(t, it);
            }
            view = canvas;
        }

        /** Moves/resizes a tile while arranging; returns the new rect. */
        float[] nudge(int index, float dx, float dy, float grow) {
            float[] r = rects.get(index);
            r[2] = Math.max(0.06f, Math.min(1f, r[2] * grow));
            r[3] = Math.max(0.05f, Math.min(1f, r[3] * grow));
            r[0] = Math.max(0, Math.min(1 - r[2], r[0] + dx));
            r[1] = Math.max(0, Math.min(1 - r[3], r[1] + dy));
            canvas.requestLayout();
            return r;
        }

        @Override
        int arrangeStep(int dir) {
            return 0; // freeform arranges by position, not order
        }
    }

    // ------------------------------------------------------------------ radial

    /**
     * A segmented wheel: one wedge per section; choosing a section turns the wheel into that section's
     * items. The name of what's highlighted shows in the centre.
     */
    static final class RadialWheel extends ViewGroup {
        final List<View> shown = new ArrayList<>(); // the wedges' tiles, clockwise from the top
        View center;
        float hl = -1;          // highlighted wedge (fractional while it slides)
        String highlight = "filled";
        int accent = 0xFF4FC3F7, line = 0x22FFFFFF, disc = 0x22FFFFFF, fill = 0x18FFFFFF;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF ro = new android.graphics.RectF(), ri = new android.graphics.RectF();
        private android.animation.ValueAnimator slide;

        RadialWheel(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
        }

        float dpf(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        float outer() {
            return Math.min(getWidth(), getHeight()) / 2f - dpf(3);
        }

        float inner() {
            return outer() * 0.42f;
        }

        /** Slides the highlight to wedge {@code to} (the short way round). */
        void highlightTo(int to, boolean animate) {
            int n = shown.size();
            if (slide != null) slide.cancel();
            if (!animate || hl < 0 || n < 2) {
                hl = to;
                invalidate();
                return;
            }
            float from = hl, d = to - from;
            while (d > n / 2f) d -= n;
            while (d < -n / 2f) d += n;
            float target = from + d;
            slide = android.animation.ValueAnimator.ofFloat(from, target);
            slide.setDuration(130);
            slide.addUpdateListener(a -> {
                hl = (float) a.getAnimatedValue();
                invalidate();
            });
            slide.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    hl = ((to % n) + n) % n;
                    invalidate();
                }
            });
            slide.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            float R = size / 2f - dpf(3), r0 = R * 0.42f;
            int n = Math.max(1, shown.size());
            float mid = (R + r0) / 2f;
            int tile = (int) Math.min((R - r0) * 0.72f, 2 * Math.PI * mid / n * 0.62f);
            for (View v : shown) {
                v.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY));
            }
            if (center != null) {
                int cs = (int) (r0 * 1.55f);
                center.measure(MeasureSpec.makeMeasureSpec(cs, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cs, MeasureSpec.AT_MOST));
            }
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float cx = (r - l) / 2f, cy = (b - t) / 2f, R = Math.min(cx, cy) - dpf(3), r0 = R * 0.42f;
            int n = shown.size();
            float mid = (R + r0) / 2f;
            for (int k = 0; k < n; k++) {
                View v = shown.get(k);
                double a = Math.toRadians(-90 + 360.0 * k / n);
                float x = cx + (float) Math.cos(a) * mid, y = cy + (float) Math.sin(a) * mid;
                int hw = v.getMeasuredWidth() / 2, hh = v.getMeasuredHeight() / 2;
                v.layout((int) x - hw, (int) y - hh, (int) x + hw, (int) y + hh);
            }
            if (center != null) {
                int w = center.getMeasuredWidth(), h = center.getMeasuredHeight();
                center.layout((int) (cx - w / 2f), (int) (cy - h / 2f), (int) (cx + w / 2f), (int) (cy + h / 2f));
            }
        }

        private void sector(float cx, float cy, float rIn, float rOut, float start, float sweep) {
            path.reset();
            ro.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut);
            ri.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);
            path.arcTo(ro, start, sweep, true);
            path.arcTo(ri, start + sweep, -sweep, false);
            path.close();
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, R = outer(), r0 = inner();
            int n = shown.size();
            paint.setShadowLayer(0, 0, 0, 0);
            // the highlighted wedge
            if (n > 0 && hl >= 0) {
                float span = 360f / n, start = -90 + span * hl - span / 2;
                float grow = highlight.equals("grow") ? dpf(6) : 0;
                if (!highlight.equals("underline")) {
                    sector(cx, cy, r0, R + grow, start, span);
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    paint.setColor(highlight.equals("outline") ? 0x0AFFFFFF : fill);
                    cv.drawPath(path, paint);
                    if (highlight.equals("outline")) {
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        paint.setStrokeWidth(dpf(2));
                        paint.setColor(accent);
                        cv.drawPath(path, paint);
                    }
                }
                // the accent arc along the outside
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeCap(android.graphics.Paint.Cap.BUTT);
                paint.setStrokeWidth(dpf(highlight.equals("underline") ? 5 : 3.5f));
                paint.setColor(accent);
                if (highlight.equals("glow")) paint.setShadowLayer(dpf(8), 0, 0, accent);
                float ar = R + grow - dpf(1);
                ro.set(cx - ar, cy - ar, cx + ar, cy + ar);
                cv.drawArc(ro, start, span, false, paint);
                paint.setShadowLayer(0, 0, 0, 0);
            }
            // lines between the wedges
            if (n > 1) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(1));
                paint.setColor(line);
                for (int k = 0; k < n; k++) {
                    double a = Math.toRadians(-90 + 360.0 * k / n - 180.0 / n);
                    float c0 = (float) Math.cos(a), s0 = (float) Math.sin(a);
                    cv.drawLine(cx + c0 * r0, cy + s0 * r0, cx + c0 * R, cy + s0 * R, paint);
                }
            }
            // the centre disc
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(disc);
            paint.setShadowLayer(dpf(10), 0, dpf(2), 0x55000000);
            cv.drawCircle(cx, cy, r0 - dpf(2), paint);
            paint.setShadowLayer(0, 0, 0, 0);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dpf(1));
            paint.setColor(line);
            cv.drawCircle(cx, cy, r0 - dpf(2), paint);
        }
    }

    static final class RadialRenderer extends Renderer {
        RadialWheel wheel;
        TextView centerSection, centerTitle, centerSub;
        /** Sections in order, and for each the tile indexes of its items. -1 level = the sections. */
        final List<String> sections = new ArrayList<>();
        final List<List<Integer>> members = new ArrayList<>();
        int sectionCount; // the first tiles are the sections' wedges (0 when not grouped)
        int level = -2;
        int text = 0xFFFFFFFF, accentColor;

        RadialRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        @Override
        void build(List<Item> items) {
            wheel = new RadialWheel(c);
            text = theme.style.text(c);
            accentColor = theme.style.accent(c);
            wheel.accent = accentColor;
            wheel.line = (text & 0x00FFFFFF) | 0x1F000000;
            int tile = theme.style.tile(c);
            wheel.disc = android.graphics.Color.alpha(tile) < 0x14 ? (text & 0x00FFFFFF) | 0x1A000000 : tile;
            wheel.fill = (text & 0x00FFFFFF) | 0x16000000;
            wheel.highlight = theme.style.highlight;
            List<Item> sel = new ArrayList<>();
            for (Item it : items) if (it.action != null) sel.add(it);
            // group by section (keeping the order of first appearance)
            List<List<Item>> groups = new ArrayList<>();
            for (Item it : sel) {
                int k = sections.indexOf(it.section);
                if (k < 0) {
                    sections.add(it.section);
                    groups.add(new ArrayList<>());
                    k = sections.size() - 1;
                }
                groups.get(k).add(it);
            }
            boolean grouped = theme.sections && sections.size() > 1;
            sectionCount = grouped ? sections.size() : 0;
            if (grouped) {
                for (int k = 0; k < sections.size(); k++) {
                    int key = k;
                    Item first = groups.get(k).get(0);
                    Item s = new Item("section:" + sections.get(k), "section:" + sections.get(k), sections.get(k),
                            first.icon, sections.get(k), groups.get(k).size() + (groups.get(k).size() == 1 ? " item" : " items"),
                            null, () -> enter(key));
                    Integer own = sectionIcons.get(sections.get(k));
                    Item shownAs = own != null ? new Item(s.id, s.group, s.section, own, s.title, s.subtitle, null, s.action) : s;
                    if (own == null) shownAs.iconFile = first.iconFile;
                    View t = wedge(shownAs);
                    wheel.addView(t);
                    selectable(t, shownAs);
                }
            }
            for (int k = 0; k < groups.size(); k++) {
                List<Integer> m = new ArrayList<>();
                for (Item it : groups.get(k)) {
                    View t = wedge(it);
                    t.setVisibility(View.GONE);
                    wheel.addView(t);
                    m.add(tiles.size());
                    selectable(t, it);
                }
                members.add(m);
            }
            if (!grouped) { // one ring of everything
                List<Integer> all = new ArrayList<>();
                for (List<Integer> m : members) all.addAll(m);
                members.clear();
                members.add(all);
            }
            LinearLayout center = new LinearLayout(c);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            centerSection = new TextView(c);
            centerSection.setGravity(Gravity.CENTER);
            centerSection.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            centerSection.setLetterSpacing(0.12f);
            centerSection.setAllCaps(true);
            centerSection.setTag("badge"); // keeps its own colour (the accent)
            centerSection.setTextColor(accentColor);
            centerTitle = new TextView(c);
            centerTitle.setGravity(Gravity.CENTER);
            centerTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            centerTitle.setAllCaps(true);
            centerTitle.setLetterSpacing(0.06f);
            centerTitle.setMaxLines(2);
            centerTitle.setEllipsize(TextUtils.TruncateAt.END);
            centerSub = new TextView(c);
            centerSub.setGravity(Gravity.CENTER);
            centerSub.setMaxLines(3);
            centerSub.setEllipsize(TextUtils.TruncateAt.END);
            centerSub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            center.addView(centerSection);
            center.addView(centerTitle);
            center.addView(centerSub);
            wheel.center = center;
            wheel.addView(center);
            view = wheel;
            showLevel(sectionCount > 0 ? -1 : 0, false);
        }

        /** A wedge's content: just the icon (the wheel draws the wedge and its highlight). */
        private View wedge(Item it) {
            android.widget.FrameLayout f = new android.widget.FrameLayout(c);
            f.setBackground(null);
            ImageView icon = new ImageView(c);
            icon.setId(R.id.row_icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageTintList(ColorStateList.valueOf(dimmed()));
            setIcon(icon, it);
            f.addView(icon, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            int pad = dp(10);
            f.setPadding(pad, pad, pad, pad);
            if (it.badge != null && !it.badge.isEmpty()) {
                TextView b = new TextView(c);
                b.setText(it.badge.startsWith("P") && it.badge.length() <= 2 ? it.badge : "•");
                b.setTag("badge");
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                b.setTextColor(text);
                f.addView(b, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
            }
            return f;
        }

        private int dimmed() {
            return (text & 0x00FFFFFF) | 0xB0000000;
        }

        /** Shows one ring: the sections (-1) or one section's items. */
        private void showLevel(int lv, boolean animate) {
            if (lv == level) return;
            level = lv;
            wheel.shown.clear();
            for (int i = 0; i < tiles.size(); i++) tiles.get(i).setVisibility(View.GONE);
            List<Integer> ids = new ArrayList<>();
            if (lv < 0) for (int i = 0; i < sectionCount; i++) ids.add(i);
            else ids.addAll(members.get(lv));
            for (int i : ids) {
                View t = tiles.get(i);
                t.setVisibility(View.VISIBLE);
                wheel.shown.add(t);
                if (animate) {
                    t.setAlpha(0f);
                    t.setScaleX(0.7f);
                    t.setScaleY(0.7f);
                    t.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
                }
            }
            wheel.hl = -1;
            wheel.requestLayout();
            wheel.invalidate();
        }

        private int levelOf(int index) {
            if (index < sectionCount) return -1;
            for (int k = 0; k < members.size(); k++) if (members.get(k).contains(index)) return k;
            return 0;
        }

        private List<Integer> ring() {
            List<Integer> ids = new ArrayList<>();
            if (level < 0) for (int i = 0; i < sectionCount; i++) ids.add(i);
            else ids.addAll(members.get(level));
            return ids;
        }

        /** A: opens a section (its first item is highlighted). */
        private void enter(int k) {
            if (go != null && k < members.size() && !members.get(k).isEmpty()) go.accept(members.get(k).get(0));
        }

        @Override
        boolean back(int cur) {
            if (sectionCount == 0 || cur < sectionCount || go == null) return false;
            go.accept(levelOf(cur)); // back to that section's wedge
            return true;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            int lv = levelOf(index);
            boolean switched = lv != level;
            showLevel(lv, switched && level != -2);
            List<Integer> ids = ring();
            wheel.highlightTo(Math.max(0, ids.indexOf(index)), !switched);
            Item it = selectable.get(index);
            centerSection.setText(lv >= 0 && sectionCount > 0 ? sections.get(lv) : "");
            centerSection.setVisibility(lv >= 0 && sectionCount > 0 ? View.VISIBLE : View.GONE);
            centerTitle.setText(it.title);
            String sub = it.subtitle == null ? "" : it.subtitle;
            if (it.badge != null && !it.badge.isEmpty()) sub = sub.isEmpty() ? it.badge : it.badge + " · " + sub;
            centerSub.setText(sub);
            centerSub.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            for (int i : ids) {
                ImageView iv = tiles.get(i).findViewById(R.id.row_icon);
                if (iv != null && iv.getImageTintList() != null) {
                    iv.setImageTintList(ColorStateList.valueOf(i == index ? accentColor : dimmed()));
                }
            }
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            if (tiles.isEmpty()) return -1;
            if (cur < 0) return 0;
            List<Integer> ids = ring();
            int p = ids.indexOf(cur);
            if (p < 0) return ids.isEmpty() ? -1 : ids.get(0);
            int step = dir == LEFT || dir == UP ? -1 : 1;
            return ids.get(((p + step) % ids.size() + ids.size()) % ids.size());
        }

        /** L1/R1: the previous/next section (inside a section: straight into it). */
        @Override
        int shoulder(int cur, int delta) {
            if (sectionCount == 0) return move(cur, delta > 0 ? RIGHT : LEFT, true);
            int lv = levelOf(Math.max(0, cur));
            if (lv < 0) return move(cur, delta > 0 ? RIGHT : LEFT, true);
            int k = ((lv + delta) % members.size() + members.size()) % members.size();
            return members.get(k).isEmpty() ? -1 : members.get(k).get(0);
        }

        @Override
        int aim(float x, float y, int cur) {
            List<Integer> ids = ring();
            if (ids.isEmpty()) return -1;
            double a = Math.atan2(x, -y); // 0 at the top, clockwise
            if (a < 0) a += 2 * Math.PI;
            int n = ids.size();
            return ids.get((int) Math.round(a / (2 * Math.PI) * n) % n);
        }

        @Override
        int arrangeStep(int dir) {
            return level < 0 ? 0 : dir == UP || dir == LEFT ? -1 : 1; // sections are arranged in Sections & items
        }
    }

    // ------------------------------------------------------------------ tiles

    /**
     * A menu tile that adapts to its size: roomy tiles show the icon above the name; small ones show
     * the name on top of a large, faded icon, so the text is always readable.
     */
    static final class SmartTile extends android.widget.FrameLayout {
        final ImageView icon;
        final TextView title, badge;
        final boolean alwaysOverlay;
        private Boolean overlay;

        SmartTile(Context c, Item it, boolean alwaysOverlay, int tint, android.graphics.drawable.Drawable bg) {
            super(c);
            this.alwaysOverlay = alwaysOverlay;
            setBackground(bg);
            setForeground(c.getDrawable(R.drawable.focus_ring));
            icon = new ImageView(c);
            icon.setImageTintList(ColorStateList.valueOf(tint));
            setIcon(icon, it);
            addView(icon);
            title = new TextView(c);
            title.setText(it.title);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFFFFFFFF);
            title.setShadowLayer(4, 0, 1, 0xCC000000);
            title.setAutoSizeTextTypeUniformWithConfiguration(8, 16, 1, TypedValue.COMPLEX_UNIT_SP);
            addView(title);
            badge = new TextView(c);
            badge.setTag("badge");
            if (it.badge != null) {
                badge.setText(it.badge);
                badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
                badge.setBackgroundResource(R.drawable.badge_bg);
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            } else {
                badge.setVisibility(GONE);
            }
            addView(badge);
        }

        private int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            boolean small = alwaysOverlay || w < dp(88) || h < dp(84);
            if (overlay != null && overlay == small && w == ow && h == oh) return;
            overlay = small;
            post(() -> arrange(w, h, small));
        }

        private void arrange(int w, int h, boolean small) {
            int pad = Math.max(dp(4), Math.min(w, h) / 12);
            if (small) {
                int s = (int) (Math.min(w, h) * 0.72f);
                icon.setLayoutParams(new LayoutParams(s, s, Gravity.CENTER));
                icon.setAlpha(0.22f);
                LayoutParams tl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
                tl.setMargins(pad, pad, pad, badge.getVisibility() == VISIBLE ? pad + dp(14) : pad);
                title.setLayoutParams(tl);
            } else {
                int s = Math.min(dp(36), h / 3);
                LayoutParams il = new LayoutParams(s, s, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                il.topMargin = (int) (h * 0.14f);
                icon.setLayoutParams(il);
                icon.setAlpha(1f);
                LayoutParams tl = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (h * 0.42f),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                tl.setMargins(pad, 0, pad, badge.getVisibility() == VISIBLE ? pad + dp(14) : pad);
                title.setLayoutParams(tl);
            }
            LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            bl.bottomMargin = Math.max(dp(2), pad / 2);
            badge.setLayoutParams(bl);
        }
    }

    // ------------------------------------------------------------------ carousel

    /** Cards in a row; the selected one sits in the middle, the others slide past it. */
    static final class CarouselCanvas extends ViewGroup {
        int card, selected;
        final List<View> cards = new ArrayList<>();

        CarouselCanvas(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int W = MeasureSpec.getSize(w);
            for (View ch : cards) {
                ch.measure(MeasureSpec.makeMeasureSpec(card, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(card, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(W, (int) (card * 1.12f));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int cx = (r - l) / 2, cy = (b - t) / 2;
            for (View ch : cards) ch.layout(cx - card / 2, cy - card / 2, cx + card / 2, cy + card / 2);
            place(false);
        }

        void place(boolean animate) {
            int n = cards.size();
            float spacing = card * 0.92f;
            for (int i = 0; i < n; i++) {
                View ch = cards.get(i);
                int d = i - selected;
                if (n > 2) { // the shortest way round, so the strip wraps
                    if (d > n / 2) d -= n;
                    if (d < -n / 2) d += n;
                }
                int ad = Math.abs(d);
                float tx = d * spacing, scale = d == 0 ? 1.08f : Math.max(0.6f, 0.86f - 0.06f * (ad - 1));
                float alpha = ad > 3 ? 0f : d == 0 ? 1f : 0.75f - 0.15f * (ad - 1);
                boolean jump = Math.abs(ch.getTranslationX() - tx) > spacing * 2.5f; // wrapping: don't fly across
                if (animate && !jump) {
                    ch.animate().translationX(tx).scaleX(scale).scaleY(scale).alpha(alpha).setDuration(160).start();
                } else {
                    ch.animate().cancel();
                    ch.setTranslationX(tx);
                    ch.setScaleX(scale);
                    ch.setScaleY(scale);
                    ch.setAlpha(alpha);
                }
                ch.setTranslationZ(d == 0 ? 8 : 0);
            }
        }
    }

    static final class CarouselRenderer extends Renderer {
        CarouselCanvas canvas;
        TextView caption, captionSub;

        CarouselRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        @Override
        void build(List<Item> items) {
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            canvas = new CarouselCanvas(c);
            canvas.setClipChildren(false);
            int screen = Math.min(c.getResources().getDisplayMetrics().widthPixels,
                    c.getResources().getDisplayMetrics().heightPixels);
            canvas.card = (int) (screen * theme.sizeFraction() * 0.42f);
            for (Item it : items) {
                if (it.action == null) continue;
                View card = new SmartTile(c, it, false, color(androidx.appcompat.R.attr.colorPrimary), tileBackground(false));
                canvas.cards.add(card);
                canvas.addView(card);
                selectable(card, it);
            }
            column.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            caption = new TextView(c);
            caption.setGravity(Gravity.CENTER);
            caption.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            captionSub = new TextView(c);
            captionSub.setGravity(Gravity.CENTER);
            captionSub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            column.addView(caption);
            column.addView(captionSub);
            view = column;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            canvas.selected = index;
            canvas.place(true);
            Item it = selectable.get(index);
            caption.setText(it.title + (it.badge != null ? "  ·  " + it.badge : ""));
            captionSub.setText(it.subtitle == null ? "" : it.subtitle);
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            int n = tiles.size();
            if (n == 0) return -1;
            if (cur < 0) return 0;
            if (dir == LEFT) return (cur - 1 + n) % n;
            if (dir == RIGHT) return (cur + 1) % n;
            // up/down: the first item of the previous/next section
            String sec = selectable.get(cur).section;
            if (dir == DOWN) {
                for (int k = 1; k < n; k++) {
                    int i = (cur + k) % n;
                    if (!selectable.get(i).section.equals(sec)) return i;
                }
            } else {
                int start = cur;
                while (start > 0 && selectable.get(start - 1).section.equals(sec)) start--;
                int prev = (start - 1 + n) % n;
                String ps = selectable.get(prev).section;
                while (prev > 0 && selectable.get(prev - 1).section.equals(ps)) prev--;
                return prev == cur ? -1 : prev;
            }
            return -1;
        }

        @Override
        int arrangeStep(int dir) {
            return dir == LEFT ? -1 : dir == RIGHT ? 1 : 0;
        }
    }

    static Renderer create(Context c, MenuTheme t) {
        switch (t.layout) {
            case "compact": return new ListRenderer(c, t, true);
            case "grid": return new GridRenderer(c, t);
            case "radial": return new RadialRenderer(c, t);
            case "freeform": return new FreeRenderer(c, t);
            case "carousel": return new CarouselRenderer(c, t);
            case "xmb": return new MenuStyles.XmbRenderer(c, t);
            case "dock": return new MenuStyles.DockRenderer(c, t);
            case "arc": return new MenuStyles.ArcRenderer(c, t);
            case "wheel": return new MenuStyles.WheelRenderer(c, t);
            case "tabs": return new MenuStyles.TabsRenderer(c, t);
            case "rail": return new MenuStyles.RailRenderer(c, t);
            default: return new ListRenderer(c, t, false);
        }
    }
}
