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

    /** One book: label, spine art, icon, text colour on the spine, and what it opens. */
    private static final class Book {
        final String label, spine, icon, pkg;
        final int ink;
        Bitmap spineBmp, iconBmp;

        Book(String label, String spine, String icon, int ink, String pkg) {
            this.label = label;
            this.spine = spine;
            this.icon = icon;
            this.ink = ink;
            this.pkg = pkg;
        }
    }

    private static final int NIGHT_SKY = 0xFF0B1B3B, CREAM = 0xFFFFF6E6, GOLD = 0xFFFFC94D;
    /** Opens the stock OmarchyTV home (all apps, settings, widgets). */
    private static final String STOCK = "@stock";

    private static final Book[] BOOKS = {
            new Book("Storybooks", "spine1", "book", NIGHT_SKY, "com.netflix.ninja"),
            new Book("Learning", "spine2", "cap", Color.WHITE, "com.google.android.youtube.tv"),
            new Book("Music", "spine3", "piano", Color.WHITE, "com.google.android.youtube.tvmusic"),
            new Book("Discovery", "spine4", "compass", Color.WHITE, "com.amazon.amazonvideo.livingroom"),
            new Book("Family", "spine5", "family", NIGHT_SKY, "com.smartdevice.livetv"),
            new Book("Technology", "spine6", "laptop", Color.WHITE, STOCK),
    };

    private static ColinHavenHome view;

    private final Bitmap dayBg, nightBg, selGlow;
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int sel;
    /** Selected book's slide-out, animated 0..1 per book. */
    private final float[] out = new float[BOOKS.length];
    private long lastFrame;
    private boolean hidden;
    private String lastClock = "";

    private ColinHavenHome(Context c, Bitmap day, Bitmap night, Bitmap glow) {
        super(c);
        dayBg = day;
        nightBg = night;
        selGlow = glow;
        for (Book b : BOOKS) {
            b.spineBmp = load(c, "holmes/" + b.spine + ".webp");
            b.iconBmp = load(c, "holmes/icon_" + b.icon + ".webp");
        }
        Typeface serif = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        text.setTypeface(serif);
        small.setTypeface(serif);
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
        ColinHavenHome v = new ColinHavenHome(a, day, night != null ? night : day, load(a, "holmes/spine_sel.webp"));
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

        // Books: stacked in the shelf alcove. The spine art has a wide soft glow, so the images overlap
        // (step < height) and the visible books sit snugly, as in the concept render.
        float shelfX = w * 0.052f, shelfW = w * 0.225f, top = h * 0.205f, stepY = h * 0.10f, bookH = h * 0.17f;
        boolean animating = false;
        for (int i = 0; i < BOOKS.length; i++) {
            float target = i == sel ? 1f : 0f;
            float step = dt * 7f;
            if (Math.abs(out[i] - target) > 0.001f) {
                out[i] += Math.max(-step, Math.min(step, target - out[i]));
                animating = true;
            }
        }
        for (int i = 0; i < BOOKS.length; i++) {
            if (i == sel) continue;   // the selected book is drawn last, on top
            drawBook(c, i, shelfX, top + i * stepY, shelfW, bookH, now);
        }
        drawBook(c, sel, shelfX, top + sel * stepY, shelfW, bookH, now);

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
        c.drawText("more apps  \u203A", shelfX + w * 0.04f, h * 0.885f, small);

        if (animating) postInvalidateOnAnimation();
        else postInvalidateDelayed(1000);   // keeps the clock and the selected book's glow alive
    }

    private void drawBook(Canvas c, int i, float x, float y, float bw, float bh, long now) {
        Book b = BOOKS[i];
        float o = out[i], ease = 1f - (1f - o) * (1f - o);
        float scale = 1f + 0.08f * ease, slide = bw * 0.10f * ease;
        float cw = bw * scale, ch = bh * scale, cx = x + slide - (cw - bw) * 0.25f, cy = y - (ch - bh) / 2f;

        if (i == sel && selGlow != null) {   // gold glow behind/around the selected book, gently pulsing
            float pulse = 0.75f + 0.25f * (float) Math.sin(now / 420.0);
            float gx = cw * 0.03f, gy = ch * 0.05f;
            r.set(cx - gx, cy - gy, cx + cw + gx, cy + ch + gy);
            img.setColorFilter(null);
            img.setAlpha((int) (255 * ease * pulse));
            c.drawBitmap(selGlow, null, r, img);
        }
        if (b.spineBmp != null) {
            r.set(cx, cy, cx + cw, cy + ch);
            img.setColorFilter(null);
            img.setAlpha(i == sel ? 255 : 225);
            c.drawBitmap(b.spineBmp, null, r, img);
        }
        // Icon then label, past the page-edge cap on the left of the spine.
        float ix = cx + cw * 0.14f, isz = ch * 0.32f, iy = cy + (ch - isz) / 2f;
        if (b.iconBmp != null) {
            r.set(ix, iy, ix + isz, iy + isz);
            img.setColorFilter(new PorterDuffColorFilter(b.ink, PorterDuff.Mode.SRC_IN));
            img.setAlpha(255);
            c.drawBitmap(b.iconBmp, null, r, img);
            img.setColorFilter(null);
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(ch * 0.22f);
        text.setColor(b.ink);
        text.setShadowLayer(ch * 0.04f, 0, 0, b.ink == Color.WHITE ? 0x99000000 : 0x55FFFFFF);
        float maxW = cw * 0.62f;
        if (text.measureText(b.label) > maxW) text.setTextSize(text.getTextSize() * maxW / text.measureText(b.label));
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(b.label, ix + isz + cw * 0.035f, cy + ch / 2f - (fm.ascent + fm.descent) / 2f, text);
    }
}
