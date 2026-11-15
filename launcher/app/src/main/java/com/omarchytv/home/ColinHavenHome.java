package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Colin's fork: the Holmes Haven home. The bear family's library room fills the screen, and the apps are glowing
 * neon books stacked in the empty bookshelf on the left: Up/Down picks a book (it slides out and glows gold), OK
 * opens it. Big, bright, one colour per app, for Emily (about 95% visually impaired, sees colour).
 *
 * It is a layer over the stock OmarchyTV home, which keeps running underneath: Right (or the Technology book)
 * slides the bookshelf away to reveal it, and Home brings the bookshelf back. After 22:00 and before 06:00 the
 * room switches to the sleeping family. Shown when assets/holmes/home_bg.webp exists and "havenHome" isn't false.
 */
final class ColinHavenHome extends View {

    /** One book: label, cover colour, icon, text colour on the cover, and what it opens. */
    private static final class Book {
        final String label, icon, pkg;
        final int color, ink;
        Bitmap iconBmp;

        Book(String label, int color, String icon, int ink, String pkg) {
            this.label = label;
            this.color = color;
            this.icon = icon;
            this.ink = ink;
            this.pkg = pkg;
        }
    }

    private static final int NIGHT_SKY = 0xFF0B1B3B, CREAM = 0xFFFFF6E6, GOLD = 0xFFFFC94D, INK_DARK = 0xFF111827;
    /** Opens the stock OmarchyTV home (all apps, settings, widgets). */
    private static final String STOCK = "@stock";

    private static final Book[] BOOKS = {
            new Book("Storybooks", 0xFFF4F4F8, "book", INK_DARK, "com.netflix.ninja"),
            new Book("Learning", 0xFF22C55E, "cap", Color.WHITE, "com.google.android.youtube.tv"),
            new Book("Music", 0xFFE8202A, "piano", Color.WHITE, "com.google.android.youtube.tvmusic"),
            new Book("Discovery", 0xFF1E5BFF, "compass", Color.WHITE, "com.amazon.amazonvideo.livingroom"),
            new Book("Family", 0xFFFFD21F, "family", INK_DARK, "com.smartdevice.livetv"),
            new Book("Technology", 0xFF9B30FF, "laptop", Color.WHITE, STOCK),
    };

    private static ColinHavenHome view;

    private final Bitmap dayBg, nightBg;
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path clip = new android.graphics.Path();
    private final android.graphics.Matrix quad = new android.graphics.Matrix();
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int sel;
    /** Selected book's slide-out, animated 0..1 per book. */
    private final float[] out = new float[BOOKS.length];
    private long lastFrame;
    private boolean hidden;
    private String lastClock = "";

    private ColinHavenHome(Context c, Bitmap day, Bitmap night) {
        super(c);
        dayBg = day;
        nightBg = night;
        for (Book b : BOOKS) {
            b.iconBmp = load(c, "holmes/icon_" + b.icon + ".webp");
        }
        Typeface serif = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        Typeface heavy = android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.SANS_SERIF, 800, false)
                : Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        text.setTypeface(serif);
        small.setTypeface(serif);
        label.setTypeface(heavy);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // ---- MainActivity hooks ----

    /** From MainActivity.onCreate, after setContentView. */
    static void attach(Activity a, FrameLayout root) {
        view = null;
        if (root == null || !LauncherConfig.get(a).havenHome) return;
        Bitmap day = load(a, "holmes/home_bg.webp");
        if (day == null) return;
        Bitmap night = load(a, "holmes/night_bg.webp");
        ColinHavenHome v = new ColinHavenHome(a, day, night != null ? night : day);
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        v.setTranslationZ(5_000f);   // above Home's panes, below the boot screen (10 000)
        view = v;
    }

    /** From MainActivity.onResume and onNewIntent (Home pressed): the bookshelf comes back. */
    static void show() {
        ColinHavenHome v = view;
        if (v == null) return;
        v.hidden = false;
        v.setVisibility(VISIBLE);
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.invalidate();
    }

    /** From the top of MainActivity.dispatchKeyEvent (after the boot screen). True = handled here. */
    static boolean consumeKey(KeyEvent e) {
        ColinHavenHome v = view;
        if (v == null || v.hidden) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) v.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) v.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) v.open(BOOKS[v.sel]);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && e.getRepeatCount() == 0) v.hide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return true;
            default:
                return false;   // Menu, Back, colour and media keys keep their OmarchyTV meaning
        }
    }

    // ---- behaviour ----

    private void move(int d) {
        sel = (sel + d + BOOKS.length) % BOOKS.length;
        invalidate();
    }

    private void open(Book b) {
        if (STOCK.equals(b.pkg)) {
            hide();
            return;
        }
        Context c = getContext();
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(b.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(b.pkg);
        if (i == null) {
            Toast.makeText(c, b.label + ": app not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, b.label + ": can't open", Toast.LENGTH_SHORT).show();
        }
    }

    /** Slide the bookshelf away to the left and reveal the stock OmarchyTV home. */
    private void hide() {
        hidden = true;
        animate().translationX(-getWidth() * 0.35f).alpha(0f).setDuration(260)
                .withEndAction(() -> { if (hidden) setVisibility(GONE); }).start();
    }

    private static Bitmap load(Context c, String asset) {
        try (InputStream in = c.getAssets().open(asset)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean night() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    // ---- drawing (1080p UI; the art is laid out for 16:9) ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        r.set(0, 0, w, h);
        img.setColorFilter(null);
        img.setAlpha(255);
        c.drawBitmap(night() ? nightBg : dayBg, null, r, img);

        // Books: thick hardcovers lying flat, stacked inside the shelf opening (between the pillars, from the top
        // shelf down to the base) and following the shelf's perspective: the top shelf slopes down to the right,
        // the lower ones flatten out. Each book is drawn flat and mapped onto its quad (measured on the B1 art).
        boolean animating = false;
        for (int i = 0; i < BOOKS.length; i++) {
            float target = i == sel ? 1f : 0f;
            float step = dt * 7f;
            if (Math.abs(out[i] - target) > 0.001f) {
                out[i] += Math.max(-step, Math.min(step, target - out[i]));
                animating = true;
            }
        }
        float xL = w * 0.0583f, xR = w * 0.2719f, tL = h * 0.2889f, tR = h * 0.3278f, bL = h * 0.7944f, bR = h * 0.80f;
        int n = BOOKS.length;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((i == sel) != (pass == 1)) continue;   // the selected book last, on top
                float yl0 = tL + (bL - tL) * i / n, yr0 = tR + (bR - tR) * i / n;
                float yl1 = tL + (bL - tL) * (i + 1) / n - 2f, yr1 = tR + (bR - tR) * (i + 1) / n - 2f;
                float bw = xR - xL, bh = yl1 - yl0;
                quad.setPolyToPoly(new float[]{0, 0, bw, 0, bw, bh, 0, bh},
                        0, new float[]{xL, yl0, xR, yr0, xR, yr1, xL, yl1}, 0, 4);
                c.save();
                c.concat(quad);
                drawBook(c, i, 0, 0, bw, bh, now);
                c.restore();
            }
        }

        // Clock and date, top right, on the calm sky.
        Date d = new Date();
        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(h * 0.075f);
        text.setColor(CREAM);
        text.setShadowLayer(h * 0.012f, 0, 0, 0xCC000000);
        c.drawText(clock, w * 0.965f, h * 0.105f, text);
        small.setTextAlign(Paint.Align.RIGHT);
        small.setTextSize(h * 0.028f);
        small.setColor(0xFFE2E8F0);
        small.setShadowLayer(h * 0.008f, 0, 0, 0xCC000000);
        c.drawText(date, w * 0.965f, h * 0.15f, small);
        lastClock = clock;

        // Hint, bottom left under the shelf: what Right does.
        small.setTextAlign(Paint.Align.LEFT);
        small.setTextSize(h * 0.022f);
        small.setColor(0xB3FFF6E6);
        c.drawText("more apps  \u203A", w * 0.075f, h * 0.86f, small);

        if (animating) postInvalidateOnAnimation();
        else postInvalidateDelayed(1000);   // keeps the clock and the selected book's glow alive
    }

    private void drawBook(Canvas c, int i, float x, float y, float bw, float bh, long now) {
        Book b = BOOKS[i];
        boolean isSel = i == sel;
        float o = out[i], ease = 1f - (1f - o) * (1f - o);
        float scale = 1f + 0.07f * ease;
        float w = bw * scale, h = bh * scale, x0 = x + bw * 0.07f * ease - (w - bw) * 0.3f, y0 = y - (h - bh) / 2f;
        float rad = h * 0.30f;
        int color = b.color;

        // Neon glow around the cover (stronger, pulsing, when selected).
        float pulse = isSel ? 0.8f + 0.2f * (float) Math.sin(now / 420.0) : 1f;
        shape.setShader(null);
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(color);
        shape.setShadowLayer(h * (isSel ? 0.5f * pulse : 0.22f), 0, 0, color);
        r.set(x0, y0, x0 + w, y0 + h);
        c.drawRoundRect(r, rad, rad, shape);
        shape.clearShadowLayer();

        // Padded hardcover shading: light top band, full colour, deep bottom.
        shape.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + h,
                new int[]{mix(color, 0.18f, false), mix(color, 0.40f, true), color, mix(color, 0.12f, false), mix(color, 0.55f, false)},
                new float[]{0f, 0.12f, 0.30f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, shape);
        shape.setShader(null);

        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        // Left end: cream page block (curved), with faint page lines, then two gold bands following the curve.
        float ew = h * 0.62f, cx = x0 + ew * 0.42f, cy = y0 + h / 2f;
        shape.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + h, new int[]{0xFFD9CFB8, CREAM, 0xFFBFB39A}, null,
                android.graphics.Shader.TileMode.CLAMP));
        r.set(cx - ew * 0.42f, cy - h * 0.46f, cx + ew * 0.42f, cy + h * 0.46f);
        c.drawOval(r, shape);
        shape.setShader(null);
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(1f);
        shape.setColor(0x59786446);
        for (int k = 1; k < 6; k++) {
            float rx = ew * 0.42f * (1f - k * 0.12f);
            r.set(cx - rx, cy - h * 0.46f, cx + rx, cy + h * 0.46f);
            c.drawArc(r, 90, 180, false, shape);
        }
        shape.setColor(0xE6FFC94D);
        shape.setStrokeWidth(Math.max(2f, h * 0.035f));
        for (float f : new float[]{0.72f, 0.86f}) {
            float bx = x0 + ew * f;
            r.set(bx - ew * 0.18f, y0 + h * 0.02f, bx + ew * 0.18f, y0 + h * 0.98f);
            c.drawArc(r, -90, 180, false, shape);
        }
        shape.setStyle(Paint.Style.FILL);
        // Soft sheen across the top third.
        shape.setShader(new android.graphics.LinearGradient(0, y0 + h * 0.08f, 0, y0 + h * 0.42f, 0x59FFFFFF, 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(x0 + ew, y0 + h * 0.08f, x0 + w - h * 0.2f, y0 + h * 0.42f, shape);
        shape.setShader(null);
        c.restore();

        // Rim: gold when selected.
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(isSel ? 5f : 2f);
        shape.setColor(isSel ? 0xFFFFE9A8 : 0x8CFFFFFF);
        r.set(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1);
        c.drawRoundRect(r, rad, rad, shape);
        shape.setStyle(Paint.Style.FILL);

        // Big icon and bold label, centred on the cover right of the page end.
        float isz = h * 0.56f, gapI = h * 0.20f;
        label.setTextSize(h * 0.40f);
        float maxText = w - ew - isz - gapI - h * 0.4f;
        if (label.measureText(b.label) > maxText) label.setTextSize(label.getTextSize() * maxText / label.measureText(b.label));
        float tw = label.measureText(b.label), ax = x0 + ew + (w - ew - (isz + gapI + tw)) / 2f;
        if (b.iconBmp != null) {
            r.set(ax, cy - isz / 2f, ax + isz, cy + isz / 2f);
            img.setColorFilter(new PorterDuffColorFilter(b.ink, PorterDuff.Mode.SRC_IN));
            img.setAlpha(255);
            c.drawBitmap(b.iconBmp, null, r, img);
            img.setColorFilter(null);
        }
        label.setColor(b.ink);
        label.setShadowLayer(h * 0.05f, 0, 0, b.ink == Color.WHITE ? 0x80000000 : 0x73FFFFFF);
        Paint.FontMetrics fm = label.getFontMetrics();
        c.drawText(b.label, ax + isz + gapI, cy - (fm.ascent + fm.descent) / 2f, label);
    }

    /** Blend a colour toward white (light) or black. */
    private static int mix(int color, float t, boolean light) {
        int k = light ? 255 : 0;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb(Math.round(r + (k - r) * t), Math.round(g + (k - g) * t), Math.round(b + (k - b) * t));
    }
}
