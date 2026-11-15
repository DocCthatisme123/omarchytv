package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.InputStream;
import java.util.Random;

/**
 * Colin's fork: the Holmes Haven boot screen. Replaces the terminal-style boot (BootScreenView) when the
 * Holmes Haven art is bundled (assets/holmes/). Same triggers ("bootScreen": boot / wake / off), any key skips.
 *
 * About 6 s: the library room fades in with a slow push-in and twinkling stars, the knot emblem blooms in the
 * window, the "Holmes Haven" wordmark rises under it, a glowing blue-purple bar fills, then it all fades to Home.
 * Art: ~/Slater/notes/holmes-haven (B2 background, A1 emblem, A2 wordmark), sized for the 1080p UI.
 */
final class ColinHavenBoot extends View {

    private static final int FADE_IN = 900, TOTAL = 6200, FADE_OUT = 600;
    private static final int NIGHT_SKY = 0xFF0B1B3B, AURORA = 0xFF1E90FF, NEBULA = 0xFF8A2BE2, COSMIC = 0xFFFF2D95,
            GOLD = 0xFFFFC94D;
    /** Window centre in the background art (x as a fraction of width). */
    private static final float CX = 0.528f;

    private static ColinHavenBoot showing;

    private final Bitmap bg, emblem, wordmark;
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float[] starX = new float[28], starY = new float[28], starPhase = new float[28];
    private long start;
    private boolean fading, done, armed;

    private ColinHavenBoot(Context c, Bitmap bg, Bitmap emblem, Bitmap wordmark) {
        super(c);
        this.bg = bg;
        this.emblem = emblem;
        this.wordmark = wordmark;
        Random rnd = new Random(7);
        for (int i = 0; i < starX.length; i++) {   // stars only in the open window sky
            starX[i] = 0.32f + rnd.nextFloat() * 0.42f;
            starY[i] = 0.03f + rnd.nextFloat() * 0.27f;
            starPhase[i] = rnd.nextFloat() * 6.283f;
        }
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Screen going off: cover Home with the boot's black first frame, held until {@link #fireIfArmed}. */
    static void arm(Activity a, FrameLayout root) {
        if (showing != null) return;
        if (show(a, root) && showing != null) {
            showing.armed = true;
            showing.invalidate();
        }
    }

    /** Home resumed after the screen came on: start the held boot. False if nothing was armed. */
    static boolean fireIfArmed() {
        ColinHavenBoot v = showing;
        if (v == null || !v.armed) return false;
        v.armed = false;
        v.start = SystemClock.uptimeMillis();
        v.invalidate();
        return true;
    }

    /** Still armed after wake (the TV woke into another app): drop the cover. */
    static void disarm() {
        ColinHavenBoot v = showing;
        if (v != null && v.armed) v.finish();
    }

    /** True when the Holmes Haven art is bundled; then BootScreenView hands over to us. */
    static boolean show(Activity a, FrameLayout root) {
        if (showing != null) return fireIfArmed() || true;
        Bitmap bg = load(a, "holmes/boot_bg.webp"), em = load(a, "holmes/emblem.webp"), wm = load(a, "holmes/wordmark.webp");
        if (bg == null || em == null || wm == null || root == null) return false;
        ColinHavenBoot v = new ColinHavenBoot(a, bg, em, wm);
        showing = v;
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        v.setTranslationZ(10_000f);
        v.start = SystemClock.uptimeMillis();
        v.invalidate();
        return true;
    }

    static boolean isShowing() {
        return showing != null;
    }

    /** Any key skips; no key reaches Home while it plays. */
    static boolean consumeKey(KeyEvent e) {
        ColinHavenBoot v = showing;
        if (v == null) return false;
        if (v.armed) {   // a key while the cover is held: just start it
            fireIfArmed();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) v.fade();
        return true;
    }

    static void finishAll() {
        if (showing != null) showing.finish();
    }

    private static Bitmap load(Context c, String asset) {
        try (InputStream in = c.getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF000000);
        if (w == 0 || h == 0 || armed) return;   // armed: black cover, no animation until the screen is back
        int ms = (int) (SystemClock.uptimeMillis() - start);
        float t = ms / (float) TOTAL;

        // Room: fade in from black with a slow push-in (1.00 -> 1.04), centred on the window.
        float zoom = 1f + 0.04f * ease(clamp(t));
        float bw = w * zoom, bh = h * zoom, bx = w * CX - bw * CX, by = h * 0.4f - bh * 0.4f;
        img.setAlpha((int) (255 * ease(clamp(ms / (float) FADE_IN))));
        r.set(bx, by, bx + bw, by + bh);
        c.drawBitmap(bg, null, r, img);

        // Twinkling stars in the window.
        for (int i = 0; i < starX.length; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(ms / 380f + starPhase[i]);
            float a = tw * tw * clamp((ms - 400) / 800f);
            if (a <= 0.02f) continue;
            float x = bx + starX[i] * bw, y = by + starY[i] * bh, s = h * 0.0028f * (0.6f + tw);
            fill.setColor(withAlpha(0xFFE2E8F0, a));
            c.drawCircle(x, y, s, fill);
            fill.setColor(withAlpha(0xFFB57BFF, a * 0.35f));
            c.drawCircle(x, y, s * 3.2f, fill);
        }

        // Emblem: blooms in the window (scale 0.82 -> 1, fade), then breathes gently.
        float pe = ease(clamp((ms - 600) / 1300f));
        if (pe > 0) {
            float breathe = 1f + 0.012f * (float) Math.sin(ms / 520f) * clamp((ms - 1900) / 600f);
            float eh = h * 0.37f * (0.82f + 0.18f * pe) * breathe, ew = eh * emblem.getWidth() / emblem.getHeight();
            float ey = h * 0.275f;
            // soft purple-blue halo behind it
            glow.setShader(new android.graphics.RadialGradient(w * CX, ey, eh * 0.75f,
                    new int[]{withAlpha(NEBULA, 0.45f * pe), withAlpha(AURORA, 0.18f * pe), 0},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(w * CX, ey, eh * 0.75f, glow);
            img.setAlpha((int) (255 * pe));
            r.set(w * CX - ew / 2, ey - eh / 2, w * CX + ew / 2, ey + eh / 2);
            c.drawBitmap(emblem, null, r, img);
        }

        // Wordmark: rises into place under the emblem.
        float pw = ease(clamp((ms - 1600) / 1100f));
        if (pw > 0) {
            float ww = w * 0.42f, wh = ww * wordmark.getHeight() / wordmark.getWidth();
            float wy = h * 0.585f + h * 0.03f * (1f - pw);
            img.setAlpha((int) (255 * pw));
            r.set(w * CX - ww / 2, wy - wh / 2, w * CX + ww / 2, wy + wh / 2);
            c.drawBitmap(wordmark, null, r, img);
        }

        // Loading bar: gold-rimmed pill, filling Aurora -> Nebula -> Cosmic with a bright head.
        float pb = clamp((ms - 2300) / 3200f);
        float pa = ease(clamp((ms - 2100) / 500f));
        if (pa > 0) {
            float lw = w * 0.25f, lh = h * 0.022f, lx = w * CX - lw / 2, ly = h * 0.765f;
            r.set(lx, ly, lx + lw, ly + lh);
            fill.setShader(null);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(NIGHT_SKY, 0.75f * pa));
            c.drawRoundRect(r, lh / 2, lh / 2, fill);
            float fw = lw * easeInOut(pb);
            if (fw > lh * 0.6f) {
                RectF f = new RectF(lx, ly, lx + fw, ly + lh);
                fill.setShader(new LinearGradient(lx, 0, lx + lw, 0, new int[]{AURORA, NEBULA, COSMIC}, null,
                        Shader.TileMode.CLAMP));
                fill.setAlpha((int) (255 * pa));
                c.drawRoundRect(f, lh / 2, lh / 2, fill);
                fill.setShader(null);
                fill.setColor(withAlpha(0xFFFFFFFF, 0.85f * pa));   // bright head
                c.drawCircle(lx + fw - lh / 2, ly + lh / 2, lh * 0.55f, fill);
                fill.setColor(withAlpha(COSMIC, 0.25f * pa));
                c.drawCircle(lx + fw - lh / 2, ly + lh / 2, lh * 1.6f, fill);
            }
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(2f, lh * 0.12f));
            fill.setColor(withAlpha(GOLD, 0.9f * pa));
            c.drawRoundRect(r, lh / 2, lh / 2, fill);
            fill.setStyle(Paint.Style.FILL);
        }

        if (ms >= TOTAL) fade();
        if (!done) postInvalidateOnAnimation();
    }

    private void fade() {
        if (fading) return;
        fading = true;
        animate().alpha(0f).setDuration(FADE_OUT).withEndAction(this::finish).start();
    }

    private void finish() {
        if (done) return;
        done = true;
        animate().cancel();
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
        if (showing == this) showing = null;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float ease(float v) {
        return 1f - (1f - v) * (1f - v) * (1f - v);
    }

    private static float easeInOut(float v) {
        return v < 0.5f ? 2 * v * v : 1 - (float) Math.pow(-2 * v + 2, 2) / 2;
    }

    private static int withAlpha(int color, float a) {
        return (Math.round(255 * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }
}
