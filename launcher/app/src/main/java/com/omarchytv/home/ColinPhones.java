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

import com.omarchytv.home.link.LinkStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Colin's fork: "Phones" on the Holmes Haven TV, opened from the logo in the floating bar.
 * Lists the paired phones (the TV's allow-list) and lets the remote forget one, or "Allow a new phone":
 * pairing opens for 60 s with a big colourful code; the phone app types it in (SPAKE2, as Omarchy Link always
 * has). Nothing can start pairing while this isn't open: LinkServer refuses /v1/pair/start when the window
 * is closed. Bold, big and colourful so Emily can read it too.
 */
final class ColinPhones extends View {
    private static final long ALLOW_MS = 60_000, FORGET_CONFIRM_MS = 4_000, DONE_MS = 3_500;
    private static final int NIGHT_SKY = 0xFF0B1B3B, AURORA = 0xFF1E90FF, NEBULA = 0xFF8A2BE2, COSMIC = 0xFFFF2D95,
            GOLD = 0xFFFFC94D, LEAF = 0xFF22C55E, MOON = 0xFFE2E8F0, CREAM = 0xFFFFF6E6;
    private static final int[] DIGIT = {AURORA, COSMIC, LEAF, GOLD, NEBULA, 0xFF18F0FF};

    private static ColinPhones showing;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private List<LinkStore.Pc> pcs = new ArrayList<>();
    private int sel;
    /** 0 = list, 1 = allowing (code on screen), 2 = just paired / timed out message. */
    private int mode;
    private String code, message;
    private long allowUntil, forgetArmedAt, messageAt;
    private Set<String> before = new HashSet<>();

    private ColinPhones(Activity a) {
        super(a);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setColor(GOLD);
        body.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        digits.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        digits.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        refresh();
    }

    static void open(Activity a, FrameLayout root) {
        if (showing != null || root == null) return;
        ColinPhones v = new ColinPhones(a);
        showing = v;
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.setTranslationZ(9_000f);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180).start();
    }

    static boolean isShowing() {
        return showing != null;
    }

    static void close() {
        ColinPhones v = showing;
        if (v == null) return;
        if (v.mode == 1) OmarchyLink.closePairing();
        showing = null;
        v.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (v.getParent() instanceof FrameLayout) ((FrameLayout) v.getParent()).removeView(v);
        }).start();
    }

    private void refresh() {
        pcs = new ArrayList<>(OmarchyLink.pcs());
        if (sel > pcs.size()) sel = pcs.size();
    }

    private Set<String> ids() {
        Set<String> s = new HashSet<>();
        for (LinkStore.Pc p : OmarchyLink.pcs()) s.add(p.id);
        return s;
    }

    private void allow() {
        before = ids();
        code = OmarchyLink.openPairing();
        allowUntil = SystemClock.uptimeMillis() + ALLOW_MS;
        mode = 1;
        invalidate();
    }

    private void showMessage(String m) {
        message = m;
        messageAt = SystemClock.uptimeMillis();
        mode = 2;
        refresh();
        invalidate();
    }

    /** All keys while open. Back closes (or leaves the code screen), Up/Down pick, OK acts. */
    static boolean consumeKey(KeyEvent e) {
        ColinPhones v = showing;
        if (v == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int kc = e.getKeyCode();
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE;
        if (v.mode == 1) {
            if (back) {
                OmarchyLink.closePairing();
                v.mode = 0;
                v.invalidate();
            }
            return true;
        }
        if (v.mode == 2) {
            if (back || ok) {
                v.mode = 0;
                v.invalidate();
            }
            return true;
        }
        if (back) {
            close();
            return true;
        }
        if (kc == KeyEvent.KEYCODE_DPAD_UP) v.sel = Math.max(0, v.sel - 1);
        else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) v.sel = Math.min(v.pcs.size(), v.sel + 1);
        else if (ok && e.getRepeatCount() == 0) {
            if (v.sel == 0) v.allow();
            else {
                long now = SystemClock.uptimeMillis();
                LinkStore.Pc p = v.pcs.get(v.sel - 1);
                if (v.forgetArmedAt > 0 && now - v.forgetArmedAt < FORGET_CONFIRM_MS) {
                    OmarchyLink.forget(p.id);
                    v.forgetArmedAt = 0;
                    v.pcs.remove(v.sel - 1);
                    v.sel = Math.min(v.sel, v.pcs.size());
                    v.showMessage(p.name + " is forgotten");
                    return true;
                }
                v.forgetArmedAt = now;
            }
        }
        if (kc != KeyEvent.KEYCODE_DPAD_CENTER && kc != KeyEvent.KEYCODE_ENTER) v.forgetArmedAt = 0;
        v.invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        c.drawColor(0xD9050A18);   // dim the room behind

        float pw = w * 0.56f, ph = h * 0.70f, px = (w - pw) / 2f, py = (h - ph) / 2f, rad = h * 0.03f;
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

        float lx = px + pw * 0.07f, cx = w / 2f;
        title.setTextSize(h * 0.062f);
        title.setTextAlign(Paint.Align.CENTER);

        if (mode == 1) {
            if (now >= allowUntil) {
                OmarchyLink.closePairing();
                showMessage("Time's up. Choose “Allow a new phone” to try again");
                return;
            }
            Set<String> nowIds = ids();
            for (LinkStore.Pc p : OmarchyLink.pcs()) {
                if (!before.contains(p.id) && nowIds.contains(p.id)) {
                    OmarchyLink.closePairing();
                    showMessage("✓  " + p.name + " is paired");
                    return;
                }
            }
            c.drawText("Allow a new phone", cx, py + ph * 0.15f, title);
            body.setTextAlign(Paint.Align.CENTER);
            body.setTextSize(h * 0.032f);
            body.setColor(MOON);
            c.drawText("On the phone, open Holmes Haven and type this code", cx, py + ph * 0.27f, body);

            String cd = code == null ? "------" : code;
            float dsz = h * 0.15f, gap = dsz * 0.78f, dx = cx - gap * (cd.length() - 1) / 2f, dy = py + ph * 0.58f;
            digits.setTextSize(dsz);
            for (int i = 0; i < cd.length(); i++) {
                int col = DIGIT[i % DIGIT.length];
                float x = dx + i * gap;
                // a soft colour glow, then the bold digit
                fill.setColor((col & 0xFFFFFF) | 0x40000000);
                c.drawCircle(x, dy - dsz * 0.34f, dsz * 0.42f, fill);
                digits.setColor(col);
                digits.setShadowLayer(h * 0.01f, 0, 0, 0xCC000000);
                c.drawText(String.valueOf(cd.charAt(i)), x, dy, digits);
            }

            // time left: a bar that empties, Aurora -> Nebula -> Cosmic like the boot screen
            float left = (allowUntil - now) / (float) ALLOW_MS;
            float bw = pw * 0.6f, bh = h * 0.018f, bx = cx - bw / 2f, by = py + ph * 0.75f;
            r.set(bx, by, bx + bw, by + bh);
            fill.setColor(0xFF1B2547);
            c.drawRoundRect(r, bh / 2, bh / 2, fill);
            r.set(bx, by, bx + bw * left, by + bh);
            fill.setShader(new LinearGradient(bx, 0, bx + bw, 0, new int[]{AURORA, NEBULA, COSMIC}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, bh / 2, bh / 2, fill);
            fill.setShader(null);
            body.setTextSize(h * 0.026f);
            body.setColor(CREAM);
            c.drawText((allowUntil - now + 999) / 1000 + " seconds   ·   Back to stop", cx, by + bh + h * 0.045f, body);
            postInvalidateDelayed(250);
            return;
        }

        if (mode == 2) {
            c.drawText("Phones", cx, py + ph * 0.15f, title);
            body.setTextAlign(Paint.Align.CENTER);
            body.setTextSize(h * 0.042f);
            body.setColor(message != null && message.startsWith("✓") ? LEAF : GOLD);
            c.drawText(message == null ? "" : message, cx, py + ph * 0.52f, body);
            if (now - messageAt > DONE_MS) mode = 0;
            postInvalidateDelayed(250);
            return;
        }

        // list: "Allow a new phone", then each paired device
        c.drawText("Phones", cx, py + ph * 0.15f, title);
        body.setTextAlign(Paint.Align.LEFT);
        float rowH = h * 0.075f, y = py + ph * 0.24f;
        int rows = Math.min(pcs.size() + 1, 6);
        int first = Math.max(0, Math.min(sel - rows + 1, pcs.size() + 1 - rows));
        for (int i = first; i < first + rows; i++) {
            boolean on = i == sel;
            r.set(lx - pw * 0.02f, y, px + pw * 0.95f, y + rowH * 0.86f);
            fill.setColor(on ? 0x33FFC94D : 0x14FFFFFF);
            c.drawRoundRect(r, rowH * 0.2f, rowH * 0.2f, fill);
            if (on) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(Math.max(3f, h * 0.0035f));
                fill.setColor(GOLD);
                c.drawRoundRect(r, rowH * 0.2f, rowH * 0.2f, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            float ty = y + rowH * 0.56f;
            body.setTextSize(h * 0.034f);
            if (i == 0) {
                body.setColor(on ? GOLD : LEAF);
                c.drawText("+   Allow a new phone", lx, ty, body);
            } else {
                LinkStore.Pc p = pcs.get(i - 1);
                boolean confirm = on && forgetArmedAt > 0 && now - forgetArmedAt < FORGET_CONFIRM_MS;
                body.setColor(confirm ? COSMIC : on ? CREAM : MOON);
                c.drawText(confirm ? "Press OK again to forget " + p.name : p.name, lx, ty, body);
                if (!confirm) {
                    body.setTextSize(h * 0.024f);
                    body.setColor(0xFF94A3B8);
                    body.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(seen(p.lastSeen), px + pw * 0.92f, ty, body);
                    body.setTextAlign(Paint.Align.LEFT);
                }
                if (confirm) postInvalidateDelayed(500);
            }
            y += rowH;
        }
        body.setTextAlign(Paint.Align.CENTER);
        body.setTextSize(h * 0.024f);
        body.setColor(0xFF94A3B8);
        c.drawText(pcs.isEmpty() ? "No phones yet" : "OK on a phone to forget it   ·   Back to close", cx, py + ph * 0.93f, body);
    }

    private static String seen(long t) {
        if (t <= 0) return "";
        long ago = (System.currentTimeMillis() / 1000 - t) / 60;   // LinkStore keeps seconds
        if (ago < 2) return "just now";
        if (ago < 60) return ago + " min ago";
        if (ago < 48 * 60) return ago / 60 + " h ago";
        return ago / (60 * 24) + " days ago";
    }
}
