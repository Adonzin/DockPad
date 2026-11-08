package com.adonzin.dockpad;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

/**
 * Long settings dialogs, folded: each group's title opens or closes the rows under it, so a
 * controller doesn't have to scroll past everything. Rows a dialog hides itself stay hidden.
 */
final class Collapse {
    private static final String HEADER = "collapse-header:";

    private Collapse() {
    }

    /** A group title that opens/closes its rows. Call {@link #apply} at the end of building. */
    static TextView header(Activity a, LinearLayout box, String title, Set<String> open) {
        TextView t = new TextView(a);
        t.setTag(HEADER + title);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        int p = Math.round(6 * a.getResources().getDisplayMetrics().density);
        t.setPadding(p, p * 3, p, p);
        t.setFocusable(true);
        t.setClickable(true);
        t.setForeground(a.getDrawable(R.drawable.focus_ring));
        t.setOnClickListener(v -> {
            if (!open.remove(title)) open.add(title);
            apply(box, open);
        });
        return t;
    }

    /** Shows the rows of open groups and hides the rest. */
    static void apply(LinearLayout box, Set<String> open) {
        boolean show = true;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof String && ((String) tag).startsWith(HEADER)) {
                String title = ((String) tag).substring(HEADER.length());
                show = open.contains(title);
                ((TextView) v).setText((show ? "▾  " : "▸  ") + title);
                continue;
            }
            if ("collapse-keep".equals(tag)) {
                show = true; // rows after the last group (e.g. a reset button) always show
                continue;
            }
            Object folded = v.getTag(R.id.collapse_folded);
            if (show) {
                if (folded != null) {
                    v.setVisibility(View.VISIBLE);
                    v.setTag(R.id.collapse_folded, null);
                }
            } else if (v.getVisibility() == View.VISIBLE) {
                v.setVisibility(View.GONE);
                v.setTag(R.id.collapse_folded, Boolean.TRUE);
            }
        }
    }
}
