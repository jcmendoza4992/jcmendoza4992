package com.voicepilot.app;

import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the screen and performs taps, swipes and typing through the accessibility service. */
class Screen {
    interface Visitor { void visit(AccessibilityNodeInfo n); }

    private final VoiceService s;
    private NumberView overlay;
    private final List<Rect> marks = new ArrayList<>();

    Screen(VoiceService s) { this.s = s; }

    // ---------- reading ----------

    List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) out.add(r);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = s.getRootInActiveWindow();
            if (r != null) out.add(r);
        }
        return out;
    }

    void walk(Visitor v) {
        int count = 0;
        for (AccessibilityNodeInfo root : roots()) {
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty() && count < 5000) {
                AccessibilityNodeInfo n = q.poll();
                count++;
                try {
                    v.visit(n);
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) q.add(c);
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    static String str(CharSequence c) { return c == null ? "" : c.toString().toLowerCase().trim(); }

    static String clean(String t) { return t.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").trim(); }

    AccessibilityNodeInfo findByText(String query) {
        final String q = clean(query);
        if (q.isEmpty()) return null;
        final AccessibilityNodeInfo[] best = {null};
        final int[] bestScore = {0};
        walk(n -> {
            if (!n.isVisibleToUser()) return;
            int sc = Math.max(score(clean(str(n.getText())), q), score(clean(str(n.getContentDescription())), q));
            if (sc <= 0) return;
            if (n.isClickable()) sc += 5;
            if (sc > bestScore[0]) { bestScore[0] = sc; best[0] = n; }
        });
        return best[0];
    }

    private static int score(String label, String q) {
        if (label.isEmpty()) return 0;
        int penalty = Math.min(label.length(), 90);
        if (label.equals(q)) return 300;
        if (label.startsWith(q)) return 200 - penalty;
        if (label.contains(q)) return 100 - penalty;
        return 0;
    }

    AccessibilityNodeInfo findExact(String value) {
        final AccessibilityNodeInfo[] hit = {null};
        walk(n -> {
            if (hit[0] != null || !n.isVisibleToUser()) return;
            if (str(n.getText()).equals(value) || str(n.getContentDescription()).equals(value)) hit[0] = n;
        });
        return hit[0];
    }

    AccessibilityNodeInfo findById(String suffix) {
        final AccessibilityNodeInfo[] hit = {null};
        walk(n -> {
            if (hit[0] != null || !n.isVisibleToUser()) return;
            String id = n.getViewIdResourceName();
            if (id != null && id.endsWith(suffix)) hit[0] = n;
        });
        return hit[0];
    }

    AccessibilityNodeInfo editable() {
        for (AccessibilityNodeInfo r : roots()) {
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        final AccessibilityNodeInfo[] hit = {null};
        walk(n -> { if (hit[0] == null && n.isEditable() && n.isVisibleToUser()) hit[0] = n; });
        return hit[0];
    }

    // ---------- acting ----------

    boolean click(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 8 && c != null && !c.isClickable(); i++) c = c.getParent();
        if (c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        tap(b.centerX(), b.centerY(), 60);
        return true;
    }

    boolean tapText(String text) { return click(findByText(text)); }

    boolean longPressText(String text) {
        AccessibilityNodeInfo n = findByText(text);
        if (n == null) return false;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        tap(b.centerX(), b.centerY(), 800);
        return true;
    }

    void tap(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        stroke(p, ms);
    }

    void swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        stroke(p, ms);
    }

    private void stroke(Path p, long ms) {
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
            s.dispatchGesture(g, null, null);
        } catch (Exception ignored) { }
    }

    /** dir is the direction the finger moves: up, down, left or right. */
    void swipeDir(String dir, boolean big) {
        DisplayMetrics d = s.getResources().getDisplayMetrics();
        float w = d.widthPixels, h = d.heightPixels;
        float a = big ? 0.85f : 0.70f, b = big ? 0.15f : 0.30f;
        long ms = big ? 180 : 320;
        if (dir.equals("up")) swipe(w / 2, h * a, w / 2, h * b, ms);
        else if (dir.equals("down")) swipe(w / 2, h * b, w / 2, h * a, ms);
        else if (dir.equals("left")) swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, ms);
        else swipe(w * 0.15f, h / 2, w * 0.85f, h / 2, ms);
    }

    boolean typeText(String text, boolean replace) {
        AccessibilityNodeInfo n = editable();
        if (n == null) return false;
        String old = "";
        if (!replace && n.getText() != null && !n.isShowingHintText() && !n.isPassword()) old = n.getText().toString();
        String value = old.isEmpty() ? text : old + (old.endsWith(" ") ? "" : " ") + text;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean deleteLastWord() {
        AccessibilityNodeInfo n = editable();
        if (n == null || n.getText() == null || n.isShowingHintText()) return false;
        String old = n.getText().toString().trim();
        int cut = old.lastIndexOf(' ');
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cut < 0 ? "" : old.substring(0, cut));
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean imeEnter() {
        AccessibilityNodeInfo n = editable();
        if (n != null && Build.VERSION.SDK_INT >= 30
                && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return true;
        return tapText("send") || tapText("search") || tapText("done") || tapText("go");
    }

    // ---------- numbered labels ----------

    boolean numbersShowing() { return overlay != null; }

    int showNumbers() {
        marks.clear();
        final DisplayMetrics d = s.getResources().getDisplayMetrics();
        walk(n -> {
            if (!n.isVisibleToUser()) return;
            if (!(n.isClickable() || n.isEditable() || n.isCheckable() || n.isLongClickable())) return;
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() < 8 || b.height() < 8) return;
            if (b.width() >= d.widthPixels && b.height() >= d.heightPixels * 0.8f) return;
            for (Rect r : marks) if (r.equals(b)) return;
            if (marks.size() < 120) marks.add(b);
        });
        Collections.sort(marks, (a, b) -> a.top != b.top ? a.top - b.top : a.left - b.left);
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        try {
            if (overlay == null) {
                overlay = new NumberView(s);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                wm.addView(overlay, lp);
            }
            overlay.invalidate();
        } catch (Exception e) {
            overlay = null;
        }
        return marks.size();
    }

    void hideNumbers() {
        if (overlay == null) return;
        try {
            ((WindowManager) s.getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (Exception ignored) { }
        overlay = null;
    }

    boolean tapNumber(int n) {
        if (n < 1 || n > marks.size()) return false;
        Rect r = marks.get(n - 1);
        tap(r.centerX(), r.centerY(), 60);
        return true;
    }

    private class NumberView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        NumberView(Context c) {
            super(c);
            bg.setColor(Color.argb(235, 20, 90, 200));
            fg.setColor(Color.WHITE);
            fg.setTextAlign(Paint.Align.CENTER);
            fg.setFakeBoldText(true);
            fg.setTextSize(15 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(loc);
            float den = getResources().getDisplayMetrics().density;
            float hgt = 22 * den;
            for (int i = 0; i < marks.size(); i++) {
                Rect r = marks.get(i);
                String label = String.valueOf(i + 1);
                float wid = Math.max(hgt, fg.measureText(label) + 10 * den);
                float x = r.left - loc[0] + 2 * den, y = r.top - loc[1] + 2 * den;
                RectF box = new RectF(x, y, x + wid, y + hgt);
                canvas.drawRoundRect(box, 6 * den, 6 * den, bg);
                canvas.drawText(label, box.centerX(), box.centerY() - (fg.ascent() + fg.descent()) / 2, fg);
            }
        }
    }
}
