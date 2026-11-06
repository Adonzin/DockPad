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
                if (i == cur) continue;
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
                if (i == cur) continue;
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

        /** A tile: icon over a short title (grid, freeform). */
        View tile(Item it, boolean big) {
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            t.setBackground(tileBackground(false));
            t.setForeground(c.getDrawable(R.drawable.focus_ring));
            ImageView icon = new ImageView(c);
            icon.setImageResource(it.icon);
            icon.setImageTintList(ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
            t.addView(icon, new LinearLayout.LayoutParams(dp(big ? 32 : 26), dp(big ? 32 : 26)));
            TextView title = new TextView(c);
            title.setText(it.title);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = dp(6);
            t.addView(title, tl);
            if (it.badge != null) {
                TextView b = new TextView(c);
                b.setText(it.badge);
                b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
                b.setBackgroundResource(R.drawable.badge_bg);
                b.setPadding(dp(8), dp(2), dp(8), dp(2));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                bl.topMargin = dp(4);
                t.addView(b, bl);
            }
            return t;
        }

        android.graphics.drawable.Drawable tileBackground(boolean round) {
            GradientDrawable normal = new GradientDrawable();
            normal.setColor(0x22FFFFFF);
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(color(com.google.android.material.R.attr.colorPrimaryContainer));
            if (round) {
                normal.setShape(GradientDrawable.OVAL);
                focused.setShape(GradientDrawable.OVAL);
                focused.setStroke(dp(3), color(androidx.appcompat.R.attr.colorPrimary));
            } else {
                normal.setCornerRadius(dp(16));
                focused.setCornerRadius(dp(16));
            }
            StateListDrawable s = new StateListDrawable();
            s.addState(new int[]{android.R.attr.state_focused}, focused);
            s.addState(new int[]{}, normal);
            return s;
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
                ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(it.icon);
                ((TextView) r.findViewById(R.id.row_title)).setText(it.title);
                TextView sub = r.findViewById(R.id.row_subtitle);
                if (it.subtitle == null || compact) sub.setVisibility(View.GONE);
                else sub.setText(it.subtitle);
                if (it.badge != null) {
                    TextView b = r.findViewById(R.id.row_badge);
                    b.setVisibility(View.VISIBLE);
                    b.setText(it.badge);
                }
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

    /** Items on rings around a centre label. */
    static final class RadialCanvas extends ViewGroup {
        final List<int[]> slots = new ArrayList<>(); // {ring, indexInRing, countInRing} per child tile
        View center;
        float[] radii = new float[0];
        int tileSize;

        RadialCanvas(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            int rings = radii.length;
            float R = size / 2f;
            // fit the tiles to the outer ring's circumference
            int maxCount = 1;
            for (int[] s : slots) maxCount = Math.max(maxCount, s[2]);
            float outer = rings == 0 ? R : R * radii[rings - 1];
            tileSize = (int) Math.min(R * 0.30f, (2 * Math.PI * outer / maxCount) * 0.82f);
            if (rings > 1) tileSize = (int) Math.min(tileSize, R * (radii[1] - radii[0]) * 0.9f);
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch == center) {
                    int cs = (int) (R * (rings == 0 ? 1 : radii[0]) * 1.2f);
                    ch.measure(MeasureSpec.makeMeasureSpec(cs, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(cs, MeasureSpec.AT_MOST));
                } else {
                    ch.measure(MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY));
                }
            }
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float cx = (r - l) / 2f, cy = (b - t) / 2f, R = Math.min(cx, cy);
            int tile = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch == center) {
                    int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                    ch.layout((int) (cx - w / 2f), (int) (cy - h / 2f), (int) (cx + w / 2f), (int) (cy + h / 2f));
                    continue;
                }
                int[] s = slots.get(tile++);
                double angle = -Math.PI / 2 + 2 * Math.PI * s[1] / s[2]; // clockwise from the top
                float rad = R * radii[s[0]];
                float x = cx + (float) Math.cos(angle) * rad, y = cy + (float) Math.sin(angle) * rad;
                int half = ch.getMeasuredWidth() / 2;
                ch.layout((int) x - half, (int) y - half, (int) x + half, (int) y + half);
            }
        }
    }

    static final class RadialRenderer extends Renderer {
        RadialCanvas canvas;
        TextView centerTitle, centerSub;
        final List<int[]> slots = new ArrayList<>();

        RadialRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        static int capacity(int ring) {
            return 8 + ring * 4;
        }

        @Override
        void build(List<Item> items) {
            canvas = new RadialCanvas(c);
            List<Item> sel = new ArrayList<>();
            for (Item it : items) if (it.action != null) sel.add(it);
            // fill rings from the inside out; spread the last ring's items evenly
            List<Integer> counts = new ArrayList<>();
            int left = sel.size(), ring = 0;
            while (left > 0) {
                int n = Math.min(capacity(ring), left);
                counts.add(n);
                left -= n;
                ring++;
            }
            int rings = counts.size();
            canvas.radii = new float[rings];
            for (int i = 0; i < rings; i++) {
                canvas.radii[i] = rings == 1 ? 0.72f : 0.42f + (0.86f - 0.42f) * i / (rings - 1);
            }
            int idx = 0;
            for (int r = 0; r < rings; r++) {
                for (int k = 0; k < counts.get(r); k++) {
                    Item it = sel.get(idx++);
                    View t = radialTile(it);
                    int[] slot = {r, k, counts.get(r)};
                    slots.add(slot);
                    canvas.slots.add(slot);
                    canvas.addView(t);
                    selectable(t, it);
                }
            }
            LinearLayout center = new LinearLayout(c);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            centerTitle = new TextView(c);
            centerTitle.setGravity(Gravity.CENTER);
            centerTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            centerSub = new TextView(c);
            centerSub.setGravity(Gravity.CENTER);
            centerSub.setMaxLines(3);
            centerSub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            center.addView(centerTitle);
            center.addView(centerSub);
            canvas.center = center;
            canvas.addView(center);
            view = canvas;
        }

        private View radialTile(Item it) {
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setBackground(tileBackground(true));
            ImageView icon = new ImageView(c);
            icon.setImageResource(it.icon);
            icon.setImageTintList(ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
            t.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            if (it.badge != null) {
                TextView b = new TextView(c);
                b.setText(it.badge);
                b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
                t.addView(b);
            }
            return t;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            Item it = selectable.get(index);
            centerTitle.setText(it.title);
            centerSub.setText(it.subtitle == null ? "" : it.subtitle);
        }

        int indexOf(int ring, int k) {
            for (int i = 0; i < slots.size(); i++) if (slots.get(i)[0] == ring && slots.get(i)[1] == k) return i;
            return -1;
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            if (slots.isEmpty()) return -1;
            if (cur < 0) return 0;
            int[] s = slots.get(cur);
            int rings = slots.get(slots.size() - 1)[0] + 1;
            if (dir == LEFT || dir == RIGHT || rings == 1) {
                int step = dir == LEFT || dir == UP ? -1 : 1;
                return indexOf(s[0], ((s[1] + step) % s[2] + s[2]) % s[2]);
            }
            // up: outer ring, down: inner ring (at the nearest angle)
            int ring = s[0] + (dir == UP ? 1 : -1);
            if (ring < 0 || ring >= rings) {
                if (!fresh) return -1;
                ring = ring < 0 ? rings - 1 : 0;
            }
            double angle = (double) s[1] / s[2];
            int count = 0;
            for (int[] x : slots) if (x[0] == ring) count = x[2];
            return indexOf(ring, (int) Math.round(angle * count) % count);
        }

        @Override
        int aim(float x, float y, int cur) {
            if (slots.isEmpty()) return -1;
            int ring = cur >= 0 ? slots.get(cur)[0] : 0;
            double a = Math.atan2(x, -y); // 0 at the top, clockwise
            if (a < 0) a += 2 * Math.PI;
            int count = 0;
            for (int[] s : slots) if (s[0] == ring) count = s[2];
            if (count == 0) return -1;
            return indexOf(ring, (int) Math.round(a / (2 * Math.PI) * count) % count);
        }
    }

    static Renderer create(Context c, MenuTheme t) {
        switch (t.layout) {
            case "compact": return new ListRenderer(c, t, true);
            case "grid": return new GridRenderer(c, t);
            case "radial": return new RadialRenderer(c, t);
            case "freeform": return new FreeRenderer(c, t);
            default: return new ListRenderer(c, t, false);
        }
    }
}
