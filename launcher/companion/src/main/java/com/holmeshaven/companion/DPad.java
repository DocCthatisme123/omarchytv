package com.holmeshaven.companion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The big round D-pad: four coloured wedges and OK in the middle (bold colours so Emily can find them).
 * Tap = one press; hold an arrow = it repeats, like holding the real remote.
 */
final class DPad extends View {
    interface Press {
        void key(String name);
    }

    private static final String[] DIR = {"up", "right", "down", "left"};
    private static final int[] COL = {MainActivity.AURORA, MainActivity.COSMIC, MainActivity.LEAF, MainActivity.GOLD};
    private static final String[] ARROW = {"▲", "▶", "▼", "◀"};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Press press;
    private int down = -2;   // -2 none, -1 OK, 0..3 arrows
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (down >= 0) {
                press.key(DIR[down]);
                h.postDelayed(this, 140);
            }
        }
    };

    DPad(Context c, Press press) {
        super(c);
        this.press = press;
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setColor(0xFFFFFFFF);
        setContentDescription("Arrows and OK");
    }

    @Override
    protected void onMeasure(int w, int hh) {
        int s = MeasureSpec.getSize(w);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), cx = w / 2f, cy = w / 2f, R = w * 0.48f, ok = w * 0.19f;
        r.set(cx - R, cy - R, cx + R, cy + R);
        for (int i = 0; i < 4; i++) {
            float start = -135 + i * 90 + 2;   // up wedge spans -135..-45
            path.reset();
            path.moveTo(cx, cy);
            path.arcTo(r, start, 86);
            path.close();
            p.setColor(down == i ? lighten(COL[i]) : COL[i]);
            c.drawPath(path, p);
            double mid = Math.toRadians(start + 43);
            t.setTextSize(w * 0.1f);
            float ax = cx + (float) Math.cos(mid) * R * 0.66f, ay = cy + (float) Math.sin(mid) * R * 0.66f;
            c.drawText(ARROW[i], ax, ay + w * 0.035f, t);
        }
        p.setColor(MainActivity.NIGHT_SKY);
        c.drawCircle(cx, cy, ok + w * 0.03f, p);
        p.setColor(down == -1 ? lighten(MainActivity.NEBULA) : MainActivity.NEBULA);
        c.drawCircle(cx, cy, ok, p);
        t.setTextSize(w * 0.09f);
        c.drawText("OK", cx, cy + w * 0.032f, t);
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 70), g = Math.min(255, ((c >> 8) & 0xFF) + 70), b = Math.min(255, (c & 0xFF) + 70);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int hit(float x, float y) {
        float w = getWidth(), dx = x - w / 2f, dy = y - w / 2f, d = (float) Math.hypot(dx, dy);
        if (d < w * 0.22f) return -1;
        if (d > w * 0.5f) return -2;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 1 : 3;
        return dy > 0 ? 2 : 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = hit(e.getX(), e.getY());
                if (down == -2) return false;
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                press.key(down == -1 ? "select" : DIR[down]);
                if (down >= 0) h.postDelayed(repeat, 450);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(repeat);
                down = -2;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
