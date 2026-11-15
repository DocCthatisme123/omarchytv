package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Colin's fork: the Holmes Haven home. One painted scene: the bear family's library room with six glowing books
 * in the bookshelf (painted by GPT, names and icons on the spines). Each book also has a painted "selected" version
 * (pulled out, glowing), pixel-identical to the master outside the shelf, so only the shelf crop is stored and
 * crossfaded over the master: Up/Down picks a book, OK opens it. Big, bright, one colour per app, for Emily.
 *
 * Nothing is selected until the first Up/Down (the master shows all books level); after 30 s idle it goes back.
 * It is a layer over the stock OmarchyTV home, which keeps running underneath: Right (or the Technology book)
 * slides it away to reveal the stock home, and Home brings it back.
 * Art: Slater's ~/Slater/notes/holmes-haven/final-pack (00 master, 01-06 selected). Off: "havenHome": false.
 */
final class ColinHavenHome extends View {

    private static final class Book {
        final String label, pkg;

        Book(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    /** Opens the stock OmarchyTV home (all apps, settings, widgets). */
    private static final String STOCK = "@stock";

    /** Same order as the painted books, top to bottom. */
    private static final Book[] BOOKS = {
            new Book("Storybooks", "com.netflix.ninja"),
            new Book("Learning", "com.google.android.youtube.tv"),
            new Book("Music", "com.google.android.youtube.tvmusic"),
            new Book("Discovery", "com.amazon.amazonvideo.livingroom"),
            new Book("Family", "com.smartdevice.livetv"),
            new Book("Technology", STOCK),
    };

    /** Where the shelf crops sit, as fractions of the screen (source box x 60..600, y 230..880 of 1672x941). */
    private static final float SX = 60f / 1672f, SY = 230f / 941f, SW = 540f / 1672f, SH = 650f / 941f;
    private static final int FADE_MS = 160, IDLE_MS = 30_000, CREAM = 0xFFFFF6E6;

    private static ColinHavenHome view;

    private final Bitmap master;
    private final Bitmap[] shelf = new Bitmap[BOOKS.length];
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    /** -1 = nothing selected (the master). */
    private int sel = -1, prev = -1;
    private long changedAt, lastKey;
    private boolean hidden;

    private ColinHavenHome(Context c, Bitmap master) {
        super(c);
        this.master = master;
        for (int i = 0; i < BOOKS.length; i++) shelf[i] = load(c, "holmes/shelf_sel" + (i + 1) + ".webp");
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
        Bitmap m = load(a, "holmes/home_master.webp");
        if (m == null) return;
        ColinHavenHome v = new ColinHavenHome(a, m);
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        v.setTranslationZ(5_000f);   // above Home's panes, below the boot screen (10 000)
        view = v;
    }

    /** From MainActivity.onResume and onNewIntent (Home pressed): the bookshelf comes back, nothing selected. */
    static void show() {
        ColinHavenHome v = view;
        if (v == null) return;
        v.hidden = false;
        v.setVisibility(VISIBLE);
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.prev = v.sel = -1;
        v.invalidate();
    }

    /** From MainActivity.dispatchKeyEvent (after the boot screen). True = handled here. */
    static boolean consumeKey(KeyEvent e) {
        ColinHavenHome v = view;
        if (v == null || v.hidden) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) v.lastKey = SystemClock.uptimeMillis();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) v.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) v.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    if (v.sel < 0) v.select(0);   // first press only picks a book
                    else v.open(BOOKS[v.sel]);
                }
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
        if (sel < 0) select(d > 0 ? 0 : BOOKS.length - 1);
        else select((sel + d + BOOKS.length) % BOOKS.length);
    }

    private void select(int i) {
        if (i == sel) return;
        prev = sel;
        sel = i;
        changedAt = SystemClock.uptimeMillis();
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

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        if (sel >= 0 && now - lastKey > IDLE_MS && now - changedAt > IDLE_MS) select(-1);   // idle: back to level books

        img.setAlpha(255);
        r.set(0, 0, w, h);
        c.drawBitmap(master, null, r, img);

        // Shelf: the previous selection fading out under the new one fading in (both identical to the master
        // outside their own book, so the crossfade only moves the books).
        float t = Math.min(1f, (now - changedAt) / (float) FADE_MS);
        r.set(w * SX, h * SY, w * (SX + SW), h * (SY + SH));
        if (prev >= 0 && shelf[prev] != null && t < 1f) {
            img.setAlpha((int) (255 * (1f - t)));
            c.drawBitmap(shelf[prev], null, r, img);
        }
        if (sel >= 0 && shelf[sel] != null) {
            img.setAlpha((int) (255 * t));
            c.drawBitmap(shelf[sel], null, r, img);
        }
        img.setAlpha(255);

        // Clock and date, top right, on the calm sky.
        Date d = new Date();
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(h * 0.075f);
        text.setColor(CREAM);
        text.setShadowLayer(h * 0.012f, 0, 0, 0xCC000000);
        c.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d), w * 0.965f, h * 0.105f, text);
        small.setTextAlign(Paint.Align.RIGHT);
        small.setTextSize(h * 0.028f);
        small.setColor(0xFFE2E8F0);
        small.setShadowLayer(h * 0.008f, 0, 0, 0xCC000000);
        c.drawText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d), w * 0.965f, h * 0.15f, small);

        if (t < 1f) postInvalidateOnAnimation();
        else postInvalidateDelayed(1000);   // clock and idle check
    }
}
