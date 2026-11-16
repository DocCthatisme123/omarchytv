package com.omarchytv.home;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Colin's fork: the Holmes Haven power menu, opened by holding the phone app's power button. Skyworth ignores a
 * long power press from anything but its own remote, so this is ours: Sleep, Restart, Turn off (and Back).
 * Up/Down pick, OK does it; it closes by itself after 30 s untouched. Restart and Turn off go through the
 * launcher's own ADB (ColinRemote), the same way the phone remote presses keys.
 */
final class ColinPower extends View {
    private static final long IDLE_MS = 30_000;
    private static final int NIGHT_SKY = 0xFF0B1B3B, AURORA = 0xFF1E90FF, NEBULA = 0xFF8A2BE2, COSMIC = 0xFFFF2D95,
            GOLD = 0xFFFFC94D, MOON = 0xFFE2E8F0, CREAM = 0xFFFFF6E6;
    private static final String[] LABEL = {"Sleep", "Restart", "Turn off", "Back"};
    private static final String[] HINT = {"Screen off, wakes in a moment",
            "Starts the TV again (about a minute)",
            "All the way off. The phone app can still wake it",
            "Leave the TV as it is"};
    private static final int[] COL = {AURORA, NEBULA, COSMIC, 0xFF334155};

    private static ColinPower showing;
    private static volatile long pendingAt;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int sel;
    private long touchedAt = SystemClock.uptimeMillis();
    private String doing;

    private ColinPower(Activity a) {
        super(a);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setColor(GOLD);
        title.setTextAlign(Paint.Align.CENTER);
        body.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        body.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
    }

    /** The phone asked (any thread). If Home isn't in front, bring it, and show() opens the menu. */
    static void request(android.content.Context c) {
        pendingAt = SystemClock.uptimeMillis();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (ColinHavenHome.openPower()) {
                pendingAt = 0;
                return;
            }
            c.startActivity(new android.content.Intent(c, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });
    }

    /** From ColinHavenHome.show(): a request from the last few seconds is still waiting. */
    static boolean pending() {
        boolean p = pendingAt > 0 && SystemClock.uptimeMillis() - pendingAt < 4000;
        if (p) pendingAt = 0;
        return p;
    }

    static void open(Activity a, FrameLayout root) {
        if (root == null) return;
        if (showing != null) {
            showing.sel = 0;
            showing.touchedAt = SystemClock.uptimeMillis();
            showing.invalidate();
            return;
        }
        ColinPower v = new ColinPower(a);
        showing = v;
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.setTranslationZ(9_100f);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(160).start();
    }

    static void close() {
        ColinPower v = showing;
        if (v == null) return;
        showing = null;
        v.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            if (v.getParent() instanceof FrameLayout) ((FrameLayout) v.getParent()).removeView(v);
        }).start();
    }

    /** All keys while open. */
    static boolean consumeKey(KeyEvent e) {
        ColinPower v = showing;
        if (v == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        v.touchedAt = SystemClock.uptimeMillis();
        if (v.doing != null) return true;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
            close();
        } else if (kc == KeyEvent.KEYCODE_DPAD_UP) {
            v.sel = Math.max(0, v.sel - 1);
        } else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
            v.sel = Math.min(LABEL.length - 1, v.sel + 1);
        } else if ((kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER)
                && e.getRepeatCount() == 0) {
            v.choose();
        }
        v.invalidate();
        return true;
    }

    private void choose() {
        switch (sel) {
            case 0:
                close();
                ColinRemote.sleepSoon();
                break;
            case 1:
                doing = "Restarting…";
                // svc may not return an error code when Android refuses it, so the fallback follows regardless.
                ColinRemote.shellSoon("svc power reboot; sleep 2; reboot");
                giveUpLater();
                break;
            case 2:
                doing = "Turning off…";
                ColinRemote.shellSoon("svc power shutdown; sleep 2; reboot -p");
                giveUpLater();
                break;
            default:
                close();
        }
    }

    /** Still here after 15 s: the TV refused. Say so, then close. */
    private void giveUpLater() {
        postDelayed(() -> {
            if (showing != this) return;
            doing = "The TV wouldn’t allow it. Use the TV’s own remote";
            invalidate();
            postDelayed(ColinPower::close, 5000);
        }, 15_000);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (doing == null && SystemClock.uptimeMillis() - touchedAt > IDLE_MS) {
            close();
            return;
        }
        c.drawColor(0xD9050A18);
        float pw = w * 0.40f, ph = h * 0.74f, px = (w - pw) / 2f, py = (h - ph) / 2f, rad = h * 0.03f, cx = w / 2f;
        r.set(px, py, px + pw, py + ph);
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(0, py, 0, py + ph, NIGHT_SKY, 0xFF1A1240, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(3f, h * 0.004f));
        fill.setColor(GOLD);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setStyle(Paint.Style.FILL);

        title.setTextSize(h * 0.058f);
        c.drawText("Power", cx, py + ph * 0.13f, title);

        if (doing != null) {
            body.setTextSize(h * 0.045f);
            body.setColor(CREAM);
            c.drawText(doing, cx, py + ph * 0.5f, body);
            return;
        }

        float bw = pw * 0.78f, bh = ph * 0.13f, gap = ph * 0.045f, top = py + ph * 0.21f;
        for (int i = 0; i < LABEL.length; i++) {
            float y = top + i * (bh + gap);
            boolean on = i == sel;
            r.set(cx - bw / 2f, y, cx + bw / 2f, y + bh);
            fill.setColor(on ? COL[i] : 0xFF1B2547);
            c.drawRoundRect(r, bh / 2f, bh / 2f, fill);
            if (on) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setColor(CREAM);
                c.drawRoundRect(r, bh / 2f, bh / 2f, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            body.setTextSize(h * 0.04f);
            body.setColor(on ? 0xFFFFFFFF : MOON);
            c.drawText(LABEL[i], cx, y + bh * 0.66f, body);
        }
        body.setTextSize(h * 0.026f);
        body.setColor(CREAM);
        c.drawText(HINT[sel], cx, py + ph * 0.93f, body);
        postInvalidateDelayed(1000);   // for the 30 s auto-close
    }
}
