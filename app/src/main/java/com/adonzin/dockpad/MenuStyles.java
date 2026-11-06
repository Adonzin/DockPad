package com.adonzin.dockpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static com.adonzin.dockpad.MenuRender.DOWN;
import static com.adonzin.dockpad.MenuRender.LEFT;
import static com.adonzin.dockpad.MenuRender.RIGHT;
import static com.adonzin.dockpad.MenuRender.UP;

/** More quick menu styles: XMB, dock, edge arc, wheel, tabs, honeycomb and rail. */
final class MenuStyles {
    private MenuStyles() {
    }

    /** Sections of the selectable items, in order: section name -> item indexes. */
    static List<List<Integer>> sectionsOf(List<MenuRender.Item> sel, List<String> names) {
        List<List<Integer>> out = new ArrayList<>();
        String last = null;
        for (int i = 0; i < sel.size(); i++) {
            String s = sel.get(i).section;
            if (!s.equals(last)) {
                out.add(new ArrayList<>());
                names.add(s);
                last = s;
            }
            out.get(out.size() - 1).add(i);
        }
        return out;
    }

    static int sectionIndex(List<List<Integer>> secs, int item) {
        for (int k = 0; k < secs.size(); k++) if (secs.get(k).contains(item)) return k;
        return 0;
    }

    static View row(Context c, MenuRender.Item it, ViewGroup parent) {
        View r = LayoutInflater.from(c).inflate(R.layout.item_row, parent, false);
        ((ImageView) r.findViewById(R.id.row_icon)).setImageResource(it.icon);
        ((TextView) r.findViewById(R.id.row_title)).setText(it.title);
        TextView sub = r.findViewById(R.id.row_subtitle);
        if (it.subtitle == null) sub.setVisibility(View.GONE);
        else sub.setText(it.subtitle);
        if (it.badge != null) {
            TextView b = r.findViewById(R.id.row_badge);
            b.setVisibility(View.VISIBLE);
            b.setText(it.badge);
        }
        return r;
    }

    // ------------------------------------------------------------------ XMB

    /** Sections across the top as icons; the chosen section's items run down beneath it. */
    static final class XmbRenderer extends MenuRender.Renderer {
        final List<String> names = new ArrayList<>();
        List<List<Integer>> secs;
        final List<View> headers = new ArrayList<>();
        final int[] remembered = new int[64];
        XmbCanvas canvas;

        XmbRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        final class XmbCanvas extends ViewGroup {
            int sec, item, first, rowH = 1;

            XmbCanvas(Context c) {
                super(c);
                setClipChildren(false);
            }

            int head() {
                return (int) (dp(64) * (0.6f + theme.sizeFraction() * 0.6f));
            }

            @Override
            protected void onMeasure(int w, int h) {
                int W = MeasureSpec.getSize(w), H = MeasureSpec.getSize(h);
                int hs = head();
                for (View v : headers) v.measure(MeasureSpec.makeMeasureSpec(hs * 2, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(hs + dp(22), MeasureSpec.EXACTLY));
                for (View v : tiles) v.measure(MeasureSpec.makeMeasureSpec((int) (W * 0.55f), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.AT_MOST));
                setMeasuredDimension(W, H);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int W = r - l, H = b - t, hs = head();
                int anchorX = (int) (W * 0.24f), top = (int) (H * 0.12f);
                for (int k = 0; k < headers.size(); k++) {
                    View v = headers.get(k);
                    int x = anchorX + (k - sec) * hs * 2 - v.getMeasuredWidth() / 2;
                    v.layout(x, top, x + v.getMeasuredWidth(), top + v.getMeasuredHeight());
                    boolean on = k == sec;
                    v.setAlpha(on ? 1f : 0.5f);
                    v.setScaleX(on ? 1.15f : 0.9f);
                    v.setScaleY(on ? 1.15f : 0.9f);
                }
                int y0 = top + hs + dp(34);
                List<Integer> mine = secs.isEmpty() ? new ArrayList<>() : secs.get(sec);
                for (int i = 0; i < tiles.size(); i++) {
                    View v = tiles.get(i);
                    int at = mine.indexOf(i);
                    if (at < 0) continue;
                    int y = y0 + (at - first) * rowH;
                    int x = anchorX - hs;
                    v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                }
            }
        }

        @Override
        void build(List<MenuRender.Item> items) {
            canvas = new XmbCanvas(c);
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                View r = row(c, it, canvas);
                canvas.addView(r);
                selectable(r, it);
            }
            secs = sectionsOf(selectable, names);
            for (int k = 0; k < secs.size(); k++) {
                LinearLayout h = new LinearLayout(c);
                h.setOrientation(LinearLayout.VERTICAL);
                h.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView icon = new ImageView(c);
                icon.setImageResource(selectable.get(secs.get(k).get(0)).icon);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary)));
                int hs = (int) (dp(64) * (0.6f + theme.sizeFraction() * 0.6f));
                h.addView(icon, new LinearLayout.LayoutParams(hs, hs));
                TextView name = new TextView(c);
                name.setText(names.get(k));
                name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
                h.addView(name);
                headers.add(h);
                canvas.addView(h);
            }
            view = canvas;
        }

        @Override
        void onSelected(int index) {
            if (secs.isEmpty() || index < 0) return;
            canvas.sec = sectionIndex(secs, index);
            canvas.item = index;
            // which of this section's items fit on screen (decided here, not while drawing)
            List<Integer> mine = secs.get(canvas.sec);
            int H = canvas.getHeight() > 0 ? canvas.getHeight() : c.getResources().getDisplayMetrics().heightPixels;
            int hs = canvas.head(), y0 = (int) (H * 0.12f) + hs + dp(34);
            View any = tiles.get(mine.get(0));
            canvas.rowH = Math.max(dp(48), any.getMeasuredHeight() > 0 ? any.getMeasuredHeight() : dp(60));
            int visible = Math.max(1, (H - y0 - dp(36)) / canvas.rowH);
            int pos = mine.indexOf(index);
            canvas.first = Math.max(0, Math.min(pos - visible / 2, mine.size() - visible));
            // show the new items first, so the highlighted one is never hidden
            for (int at = 0; at < mine.size(); at++) {
                boolean show = at >= canvas.first && at < canvas.first + visible;
                tiles.get(mine.get(at)).setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            }
            for (int i = 0; i < tiles.size(); i++) if (!mine.contains(i)) tiles.get(i).setVisibility(View.GONE);
            remembered[Math.min(63, canvas.sec)] = secs.get(canvas.sec).indexOf(index);
            canvas.requestLayout();
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            if (secs.isEmpty()) return -1;
            if (cur < 0) return 0;
            int k = sectionIndex(secs, cur), n = secs.size();
            if (dir == LEFT || dir == RIGHT) {
                int nk = (k + (dir == LEFT ? -1 : 1) + n) % n;
                List<Integer> s = secs.get(nk);
                return s.get(Math.min(s.size() - 1, remembered[Math.min(63, nk)]));
            }
            List<Integer> s = secs.get(k);
            int at = s.indexOf(cur) + (dir == UP ? -1 : 1);
            if (at < 0) return fresh ? s.get(s.size() - 1) : -1;
            if (at >= s.size()) return fresh ? s.get(0) : -1;
            return s.get(at);
        }

        @Override
        int arrangeStep(int dir) {
            return dir == UP ? -1 : dir == DOWN ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ dock

    /** A row of icons that magnify as the highlight passes. */
    static final class DockRenderer extends MenuRender.Renderer {
        DockCanvas canvas;
        TextView caption;

        DockRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        final class DockCanvas extends ViewGroup {
            int base, sel;

            DockCanvas(Context c) {
                super(c);
                setClipChildren(false);
            }

            @Override
            protected void onMeasure(int w, int h) {
                int W = MeasureSpec.getSize(w);
                int n = Math.max(1, tiles.size());
                base = (int) Math.min(dp(56) * (0.6f + theme.sizeFraction() * 0.8f), (W - dp(32)) / (n + 2.5f));
                for (View v : tiles) v.measure(MeasureSpec.makeMeasureSpec(base, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(base, MeasureSpec.EXACTLY));
                setMeasuredDimension(W, (int) (base * 1.9f));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int W = r - l, H = b - t, n = tiles.size(), gap = dp(8);
                int total = n * base + (n - 1) * gap, x = (W - total) / 2;
                for (View v : tiles) {
                    v.layout(x, H - base - dp(6), x + base, H - dp(6));
                    v.setPivotX(base / 2f);
                    v.setPivotY(base);
                    x += base + gap;
                }
                place(false);
            }

            void place(boolean animate) {
                int n = tiles.size(), gap = dp(8);
                if (n == 0) return;
                float[] sc = new float[n];
                float total = 0;
                for (int i = 0; i < n; i++) {
                    float d = i - sel;
                    sc[i] = 1f + 0.7f * (float) Math.exp(-d * d / 2.2f);
                    total += base * sc[i] + gap;
                }
                total -= gap;
                float x = (getWidth() - total) / 2f;
                for (int i = 0; i < n; i++) {
                    View v = tiles.get(i);
                    float w = base * sc[i], target = x + w / 2f, now = v.getLeft() + base / 2f;
                    if (animate) {
                        v.animate().translationX(target - now).scaleX(sc[i]).scaleY(sc[i]).setDuration(130).start();
                    } else {
                        v.setTranslationX(target - now);
                        v.setScaleX(sc[i]);
                        v.setScaleY(sc[i]);
                    }
                    x += w + gap;
                }
            }
        }

        @Override
        void build(List<MenuRender.Item> items) {
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipChildren(false);
            caption = new TextView(c);
            caption.setGravity(Gravity.CENTER);
            caption.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            column.addView(caption);
            canvas = new DockCanvas(c);
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                View t = iconTile(it, dp(56));
                canvas.addView(t);
                selectable(t, it);
            }
            column.addView(canvas, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            view = column;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            canvas.sel = index;
            canvas.place(true);
            MenuRender.Item it = selectable.get(index);
            caption.setText(it.title + (it.badge != null ? "  ·  " + it.badge : "")
                    + (it.subtitle != null ? "\n" + it.subtitle : ""));
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            int n = tiles.size();
            if (n == 0 || dir == UP || dir == DOWN) return -1;
            if (cur < 0) return 0;
            return (cur + (dir == LEFT ? -1 : 1) + n) % n;
        }

        @Override
        int arrangeStep(int dir) {
            return dir == LEFT ? -1 : dir == RIGHT ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ edge arc

    /** A half-ring hugging the left or right edge, around the thumb. */
    static final class ArcRenderer extends MenuRender.Renderer {
        final List<int[]> slots = new ArrayList<>(); // {ring, index, count}
        ArcCanvas canvas;
        TextView caption;
        View disc;
        float[] radii = new float[0];

        ArcRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        boolean right() {
            return theme.position.equals("right");
        }

        static int capacity(int ring) {
            return 6 + ring * 3;
        }

        static final float SPAN = (float) Math.toRadians(70); // each side of the middle

        final class ArcCanvas extends ViewGroup {
            int tile;
            float rMax;

            ArcCanvas(Context c) {
                super(c);
                setClipChildren(false);
            }

            @Override
            protected void onMeasure(int w, int h) {
                int W = MeasureSpec.getSize(w), H = MeasureSpec.getSize(h);
                rMax = H * 0.48f * (0.5f + theme.sizeFraction() * 0.5f);
                int maxCount = 1;
                for (int[] s : slots) maxCount = Math.max(maxCount, s[2]);
                float outer = rMax * (radii.length == 0 ? 1 : radii[radii.length - 1]);
                tile = (int) Math.min(rMax * 0.26f, (2 * SPAN * outer / Math.max(1, maxCount - 1)) * 0.85f);
                if (radii.length > 1) tile = (int) Math.min(tile, rMax * (radii[1] - radii[0]) * 0.92f);
                for (View v : tiles) v.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY));
                int inner = (int) (rMax * (radii.length == 0 ? 0.5f : radii[0]) - tile * 0.7f);
                caption.measure(MeasureSpec.makeMeasureSpec(Math.max(dp(80), inner), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(H, MeasureSpec.AT_MOST));
                int d = (int) (2 * (rMax + tile * 0.7f));
                disc.measure(MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY));
                setMeasuredDimension(W, H);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int W = r - l, H = b - t;
                float cx = right() ? W : 0, cy = H / 2f;
                int d = disc.getMeasuredWidth();
                disc.layout((int) (cx - d / 2f), (int) (cy - d / 2f), (int) (cx + d / 2f), (int) (cy + d / 2f));
                for (int i = 0; i < tiles.size(); i++) {
                    int[] s = slots.get(i);
                    float phi = s[2] == 1 ? 0 : -SPAN + 2 * SPAN * s[1] / (s[2] - 1);
                    float rad = rMax * radii[s[0]];
                    float x = cx + (right() ? -1 : 1) * (float) Math.cos(phi) * rad, y = cy + (float) Math.sin(phi) * rad;
                    int half = tile / 2;
                    tiles.get(i).layout((int) x - half, (int) y - half, (int) x + half, (int) y + half);
                }
                int cw = caption.getMeasuredWidth(), ch = caption.getMeasuredHeight();
                int x = right() ? W - cw - dp(8) : dp(8);
                caption.layout(x, (int) (cy - ch / 2f), x + cw, (int) (cy + ch / 2f));
            }
        }

        @Override
        void build(List<MenuRender.Item> items) {
            canvas = new ArcCanvas(c);
            disc = new View(c);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xE6121519);
            disc.setBackground(bg);
            canvas.addView(disc);
            List<MenuRender.Item> sel = new ArrayList<>();
            for (MenuRender.Item it : items) if (it.action != null) sel.add(it);
            List<Integer> counts = new ArrayList<>();
            int left = sel.size(), ring = 0;
            while (left > 0) {
                int n = Math.min(capacity(ring), left);
                counts.add(n);
                left -= n;
                ring++;
            }
            radii = new float[counts.size()];
            for (int i = 0; i < radii.length; i++) {
                radii[i] = radii.length == 1 ? 0.78f : 0.5f + 0.45f * i / (radii.length - 1);
            }
            int idx = 0;
            for (int r = 0; r < counts.size(); r++) {
                for (int k = 0; k < counts.get(r); k++) {
                    MenuRender.Item it = sel.get(idx++);
                    View t = new MenuRender.SmartTile(c, it, true, color(androidx.appcompat.R.attr.colorPrimary),
                            tileBackground(true));
                    slots.add(new int[]{r, k, counts.get(r)});
                    canvas.addView(t);
                    selectable(t, it);
                }
            }
            caption = new TextView(c);
            caption.setGravity(right() ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.CENTER_VERTICAL);
            caption.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            canvas.addView(caption);
            view = canvas;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            MenuRender.Item it = selectable.get(index);
            caption.setText(it.title + (it.subtitle != null ? "\n" + it.subtitle : ""));
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
            if (dir == UP || dir == DOWN) {
                int k = s[1] + (dir == UP ? -1 : 1);
                if (k < 0) return fresh ? indexOf(s[0], s[2] - 1) : -1;
                if (k >= s[2]) return fresh ? indexOf(s[0], 0) : -1;
                return indexOf(s[0], k);
            }
            boolean outward = (dir == RIGHT) != right();
            return ringStep(cur, outward ? 1 : -1, fresh);
        }

        int ringStep(int cur, int delta, boolean wrap) {
            int[] s = slots.get(cur);
            int rings = radii.length, ring = s[0] + delta;
            if (ring < 0 || ring >= rings) {
                if (!wrap || rings == 1) return -1;
                ring = ring < 0 ? rings - 1 : 0;
            }
            float frac = s[2] == 1 ? 0.5f : (float) s[1] / (s[2] - 1);
            int count = 0;
            for (int[] x : slots) if (x[0] == ring) count = x[2];
            return indexOf(ring, Math.round(frac * (count - 1)));
        }

        @Override
        int shoulder(int cur, int delta) {
            if (slots.isEmpty()) return -1;
            return ringStep(cur < 0 ? 0 : cur, delta, true);
        }

        @Override
        int aim(float x, float y, int cur) {
            if (slots.isEmpty()) return -1;
            float in = right() ? -x : x;
            if (in < -0.2f) return -1; // pointing at the edge
            double phi = Math.atan2(y, Math.max(0.05, in));
            int ring = cur >= 0 ? slots.get(cur)[0] : 0, count = 0;
            for (int[] s : slots) if (s[0] == ring) count = s[2];
            float frac = (float) ((phi + SPAN) / (2 * SPAN));
            return indexOf(ring, Math.max(0, Math.min(count - 1, Math.round(frac * (count - 1)))));
        }

        @Override
        int arrangeStep(int dir) {
            return dir == UP ? -1 : dir == DOWN ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ wheel

    /** A vertical drum: the middle item is big, the ones above and below curve away. */
    static final class WheelRenderer extends MenuRender.Renderer {
        WheelCanvas canvas;

        WheelRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        final class WheelCanvas extends ViewGroup {
            int sel;

            WheelCanvas(Context c) {
                super(c);
                setClipChildren(false);
            }

            @Override
            protected void onMeasure(int w, int h) {
                int W = MeasureSpec.getSize(w), H = MeasureSpec.getSize(h);
                for (View v : tiles) v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(H, MeasureSpec.AT_MOST));
                setMeasuredDimension(W, H);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int W = r - l, H = b - t;
                for (View v : tiles) {
                    int h = v.getMeasuredHeight();
                    v.layout(0, (H - h) / 2, W, (H + h) / 2);
                }
                place(false);
            }

            void place(boolean animate) {
                int n = tiles.size();
                float R = getHeight() * 0.44f;
                double step = Math.toRadians(n > 9 ? 18 : 24);
                for (int i = 0; i < n; i++) {
                    View v = tiles.get(i);
                    int d = i - sel;
                    if (n > 2) {
                        if (d > n / 2) d -= n;
                        if (d < -n / 2) d += n;
                    }
                    double th = d * step;
                    boolean show = Math.abs(th) < Math.toRadians(80);
                    float ty = (float) (R * Math.sin(th)), cos = (float) Math.cos(th);
                    float sy = show ? Math.max(0.2f, cos) : 0.2f, sx = d == 0 ? 1.04f : 0.9f + 0.08f * cos;
                    float alpha = show ? (d == 0 ? 1f : 0.25f + 0.55f * cos) : 0f;
                    boolean jump = Math.abs(v.getTranslationY() - ty) > R;
                    if (animate && !jump) v.animate().translationY(ty).scaleY(sy).scaleX(sx).alpha(alpha).setDuration(150).start();
                    else {
                        v.animate().cancel();
                        v.setTranslationY(ty);
                        v.setScaleY(sy);
                        v.setScaleX(sx);
                        v.setAlpha(alpha);
                    }
                    v.setTranslationZ(d == 0 ? 6 : 0);
                }
            }
        }

        @Override
        void build(List<MenuRender.Item> items) {
            canvas = new WheelCanvas(c);
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                View r = row(c, it, canvas);
                r.setBackground(tileBackground(false));
                canvas.addView(r);
                selectable(r, it);
            }
            view = canvas;
        }

        @Override
        void onSelected(int index) {
            canvas.sel = index;
            canvas.place(true);
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            int n = tiles.size();
            if (n == 0) return -1;
            if (cur < 0) return 0;
            if (dir == UP) return (cur - 1 + n) % n;
            if (dir == DOWN) return (cur + 1) % n;
            // left/right: jump a section
            String sec = selectable.get(cur).section;
            for (int k = 1; k < n; k++) {
                int i = dir == RIGHT ? (cur + k) % n : (cur - k + n) % n;
                if (!selectable.get(i).section.equals(sec)) {
                    if (dir == LEFT) { // to the start of that section
                        String s2 = selectable.get(i).section;
                        while (i > 0 && selectable.get(i - 1).section.equals(s2)) i--;
                    }
                    return i;
                }
            }
            return -1;
        }

        @Override
        int arrangeStep(int dir) {
            return dir == UP ? -1 : dir == DOWN ? 1 : 0;
        }
    }

    // ------------------------------------------------------------------ tabs

    /** A tab per section (L1/R1), with that section's tiles below. */
    static final class TabsRenderer extends MenuRender.Renderer {
        final List<String> names = new ArrayList<>();
        final List<TextView> tabViews = new ArrayList<>();
        final List<View> pages = new ArrayList<>();
        List<List<Integer>> secs = new ArrayList<>();
        HorizontalScrollView strip;
        int shown = -1;

        TabsRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        @Override
        void build(List<MenuRender.Item> items) {
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            strip = new HorizontalScrollView(c);
            strip.setHorizontalScrollBarEnabled(false);
            strip.setFocusable(false);
            LinearLayout tabs = new LinearLayout(c);
            strip.addView(tabs);
            column.addView(strip);
            // group by section, in order
            List<List<MenuRender.Item>> groups = new ArrayList<>();
            String last = null;
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                if (!it.section.equals(last)) {
                    groups.add(new ArrayList<>());
                    names.add(it.section);
                    last = it.section;
                }
                groups.get(groups.size() - 1).add(it);
            }
            int cols = Math.max(2, theme.columns);
            for (int g = 0; g < groups.size(); g++) {
                TextView tab = new TextView(c);
                tab.setText(names.get(g));
                tab.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
                tab.setPadding(dp(14), dp(8), dp(14), dp(8));
                tabs.addView(tab);
                tabViews.add(tab);
                int before = selectable.size();
                LinearLayout page = gridOf(groups.get(g), cols, dp(92));
                List<Integer> idx = new ArrayList<>();
                for (int i = before; i < selectable.size(); i++) idx.add(i);
                secs.add(idx);
                page.setVisibility(View.GONE);
                pages.add(page);
                column.addView(page);
            }
            view = column;
        }

        void show(int k) {
            if (k == shown || k < 0 || k >= pages.size()) return;
            shown = k;
            for (int i = 0; i < pages.size(); i++) {
                pages.get(i).setVisibility(i == k ? View.VISIBLE : View.GONE);
                TextView t = tabViews.get(i);
                boolean on = i == k;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                bg.setColor(on ? color(com.google.android.material.R.attr.colorPrimaryContainer) : 0x00000000);
                t.setBackground(bg);
                t.setAlpha(on ? 1f : 0.65f);
            }
            View tab = tabViews.get(k);
            strip.post(() -> strip.smoothScrollTo(Math.max(0, tab.getLeft() - dp(24)), 0));
        }

        @Override
        void onSelected(int index) {
            show(sectionIndex(secs, index));
        }

        @Override
        int shoulder(int cur, int delta) {
            if (secs.isEmpty()) return -1;
            int k = (sectionIndex(secs, Math.max(0, cur)) + delta + secs.size()) % secs.size();
            return secs.get(k).get(0);
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            if (cur >= 0 && (dir == LEFT || dir == RIGHT)) {
                // at the row's edge, left/right go to the next tab
                int next = spatial(cur, dir, false);
                if (next >= 0) return next;
                return fresh ? shoulder(cur, dir == LEFT ? -1 : 1) : -1;
            }
            return spatial(cur, dir, fresh);
        }

        @Override
        int arrangeStep(int dir) {
            int cols = Math.max(2, theme.columns);
            return dir == LEFT ? -1 : dir == RIGHT ? 1 : dir == UP ? -cols : cols;
        }
    }

    // ------------------------------------------------------------------ honeycomb

    /** A pointy-top hexagon. */
    static final class HexDrawable extends Drawable {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        HexDrawable(int color, int strokeColor, float strokeWidth) {
            fill.setColor(color);
            stroke.setColor(strokeColor);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeWidth);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            path.reset();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(b.width() / (float) Math.sqrt(3), b.height() / 2f) - stroke.getStrokeWidth();
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(60 * i - 90);
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(path, fill);
            if (stroke.getStrokeWidth() > 0 && stroke.getColor() != 0) canvas.drawPath(path, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static final class HoneyRenderer extends MenuRender.Renderer {
        HoneyRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        final class HoneyCanvas extends ViewGroup {
            HoneyCanvas(Context c) {
                super(c);
                setClipChildren(false);
            }

            int cols() {
                return Math.max(3, theme.columns);
            }

            @Override
            protected void onMeasure(int w, int h) {
                int W = MeasureSpec.getSize(w);
                int cw = (int) (W / (cols() + 0.5f)), ch = (int) (cw * 1.1547f);
                for (View v : tiles) v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
                int rows = (tiles.size() + cols() - 1) / cols();
                setMeasuredDimension(W, (int) (rows * ch * 0.75f + ch * 0.25f) + dp(8));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int W = r - l, cw = (int) (W / (cols() + 0.5f)), ch = (int) (cw * 1.1547f);
                for (int i = 0; i < tiles.size(); i++) {
                    int row = i / cols(), col = i % cols();
                    int x = col * cw + (row % 2 == 1 ? cw / 2 : 0), y = (int) (row * ch * 0.75f);
                    tiles.get(i).layout(x, y, x + cw, y + ch);
                }
            }
        }

        @Override
        void build(List<MenuRender.Item> items) {
            HoneyCanvas canvas = new HoneyCanvas(c);
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, new HexDrawable(
                        color(com.google.android.material.R.attr.colorPrimaryContainer),
                        color(androidx.appcompat.R.attr.colorPrimary), dp(3)));
                bg.addState(new int[]{}, new HexDrawable(0x26FFFFFF, 0, 0));
                MenuRender.SmartTile t = new MenuRender.SmartTile(c, it, true,
                        color(androidx.appcompat.R.attr.colorPrimary), bg);
                t.setForeground(null); // the hexagon itself shows the highlight
                canvas.addView(t);
                selectable(t, it);
            }
            view = canvas;
        }

        @Override
        int arrangeStep(int dir) {
            int cols = Math.max(3, theme.columns);
            return dir == LEFT ? -1 : dir == RIGHT ? 1 : dir == UP ? -cols : cols;
        }
    }

    // ------------------------------------------------------------------ rail

    /** A thin column of icons; the highlighted one shows its name beside it. */
    static final class RailRenderer extends MenuRender.Renderer {
        LinearLayout bubble;
        TextView bubbleTitle, bubbleSub;
        ScrollView scroll;
        FrameLayout holder;

        RailRenderer(Context c, MenuTheme t) {
            super(c, t);
        }

        boolean right() {
            return theme.position.equals("right");
        }

        @Override
        void build(List<MenuRender.Item> items) {
            LinearLayout outer = new LinearLayout(c);
            outer.setOrientation(LinearLayout.HORIZONTAL);
            outer.setClipChildren(false);
            LinearLayout column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(8), dp(12), dp(8), dp(12));
            int size = (int) (dp(52) * (0.7f + theme.sizeFraction() * 0.5f));
            for (MenuRender.Item it : items) {
                if (it.action == null) continue;
                View t = iconTile(it, size);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(0, dp(4), 0, dp(4));
                column.addView(t, lp);
                selectable(t, it);
            }
            scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFocusable(false);
            scroll.addView(column);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6121519);
            float r = dp(20);
            bg.setCornerRadii(right() ? new float[]{r, r, 0, 0, 0, 0, r, r} : new float[]{0, 0, r, r, r, r, 0, 0});
            scroll.setBackground(bg);
            holder = new FrameLayout(c);
            holder.setClipChildren(false);
            bubble = new LinearLayout(c);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(14));
            bb.setColor(0xF0121519);
            bubble.setBackground(bb);
            bubbleTitle = new TextView(c);
            bubbleTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            bubbleSub = new TextView(c);
            bubbleSub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            bubbleSub.setMaxWidth(dp(260));
            bubble.addView(bubbleTitle);
            bubble.addView(bubbleSub);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, right() ? Gravity.END : Gravity.START);
            bl.setMargins(dp(8), 0, dp(8), 0);
            holder.addView(bubble, bl);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT);
            if (right()) {
                outer.addView(holder, hl);
                outer.addView(scroll, sl);
            } else {
                outer.addView(scroll, sl);
                outer.addView(holder, hl);
            }
            view = outer;
        }

        @Override
        void onSelected(int index) {
            if (index < 0 || index >= selectable.size()) return;
            MenuRender.Item it = selectable.get(index);
            bubbleTitle.setText(it.title + (it.badge != null ? "  ·  " + it.badge : ""));
            bubbleSub.setText(it.subtitle == null ? "" : it.subtitle);
            bubbleSub.setVisibility(it.subtitle == null ? View.GONE : View.VISIBLE);
            View t = tiles.get(index);
            holder.post(() -> {
                int[] a = new int[2], b = new int[2];
                t.getLocationOnScreen(a);
                holder.getLocationOnScreen(b);
                float y = a[1] - b[1] + t.getHeight() / 2f - bubble.getHeight() / 2f;
                bubble.animate().translationY(Math.max(0, y)).setDuration(110).start();
            });
        }

        @Override
        int move(int cur, int dir, boolean fresh) {
            int n = tiles.size();
            if (n == 0 || dir == LEFT || dir == RIGHT) return -1;
            if (cur < 0) return 0;
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
}
