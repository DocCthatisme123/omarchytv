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
 *
 * Workspaces (Omarchy style, 10 of them): 1-5 are the family's (1 = this bookshelf, 2-5 = placeholders until Colin
 * and Mikayla decide what each one is for), and Marco's original five move to 6, 7, 8, 9 and 0. Number keys jump,
 * CH+/CH- step through all ten (wrapping), Left/Right step through 1-5 (Right on 5 goes on to 6), Back on any other
 * workspace comes home to 1, and the Home button always brings back 1. Technology opens 8 (Marco's app grid).
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
    /** The stock home's own workspaces (MainActivity.setWorkspace / its current one). */
    private static java.util.function.IntConsumer stockSet;
    private static java.util.function.IntSupplier stockGet;

    private final Bitmap master, pageBg, logo;
    private final Bitmap[] shelf = new Bitmap[BOOKS.length];
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    /** -1 = nothing selected (the master). */
    private int sel = -1, prev = -1;
    private long changedAt, lastKey;
    private boolean hidden;
    /** The family workspace showing, 1..5 (1 = the bookshelf). */
    private int page = 1;

    private ColinHavenHome(Context c, Bitmap master) {
        super(c);
        this.master = master;
        pageBg = load(c, "holmes/boot_bg.webp");
        logo = load(c, "holmes/logo_bar.webp");
        for (int i = 0; i < BOOKS.length; i++) shelf[i] = load(c, "holmes/shelf_sel" + (i + 1) + ".webp");
        Typeface serif = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        text.setTypeface(serif);
        small.setTypeface(serif);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // ---- MainActivity hooks ----

    /** From MainActivity.onCreate, after setContentView. */
    static void attach(Activity a, FrameLayout root, java.util.function.IntConsumer setStock,
                       java.util.function.IntSupplier getStock) {
        view = null;
        stockSet = setStock;
        stockGet = getStock;
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
        v.page = 1;
        v.invalidate();
    }

    /** Workspace number 1..10 the TV is on (10 shows as "0"). */
    private static int current(Context c) {
        ColinHavenHome v = view;
        if (v == null) return 0;
        if (!v.hidden) return v.page;
        int pos = stockGet != null ? LauncherConfig.get(c).positionOf(stockGet.getAsInt()) : 1;
        return 5 + Math.max(1, pos);
    }

    /** Go to workspace 1..10: 1-5 here, 6-10 = Marco's workspaces in bar order. */
    private static void go(Context c, int n) {
        ColinHavenHome v = view;
        if (v == null || n < 1 || n > 10) return;
        if (n <= 5) {
            if (v.hidden) show();
            v.page = n;
            v.prev = v.sel = -1;
            v.invalidate();
            return;
        }
        int ws = LauncherConfig.get(c).workspaceAt(n - 5);
        if (ws <= 0) ws = n - 5;
        if (stockSet != null) stockSet.accept(ws);
        if (!v.hidden) v.hide();
    }

    /**
     * From MainActivity's workspace keys (number keys, CH+/CH-, Back), whether or not the bookshelf is showing.
     * True = handled here.
     */
    static boolean workspaceKey(KeyEvent e) {
        ColinHavenHome v = view;
        if (v == null) return false;
        Context c = v.getContext();
        int kc = e.getKeyCode();
        boolean press = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
            if (press) go(c, kc == KeyEvent.KEYCODE_0 ? 10 : kc - KeyEvent.KEYCODE_0);
            return true;
        }
        if (kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_CHANNEL_DOWN
                || kc == KeyEvent.KEYCODE_PAGE_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN) {
            boolean up = kc == KeyEvent.KEYCODE_CHANNEL_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN;
            if (press) go(c, (current(c) - 1 + (up ? 1 : 9)) % 10 + 1);
            return true;
        }
        if (kc == KeyEvent.KEYCODE_BACK && current(c) != 1) {
            if (e.getAction() == KeyEvent.ACTION_UP) go(c, 1);
            return true;
        }
        return false;
    }

    /** From MainActivity.dispatchKeyEvent (after the boot screen). True = handled here. */
    static boolean consumeKey(KeyEvent e) {
        ColinHavenHome v = view;
        if (v == null || v.hidden) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean press = down && e.getRepeatCount() == 0;
        if (down) v.lastKey = SystemClock.uptimeMillis();
        int kc = e.getKeyCode();
        if (v.page != 1) {   // family workspaces 2-5: Left/Right step, the rest waits for its design
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (press) go(v.getContext(), v.page - 1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (press) go(v.getContext(), v.page + 1);
                return true;
            }
            return kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                    || kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
        }
        switch (kc) {
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
                if (press) go(v.getContext(), 2);
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
            go(getContext(), 8);   // Marco's app grid
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
        if (page != 1) {
            drawPage(c, w, h);
            return;
        }
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
        drawLogo(c, w, h);

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

    /** Holmes Haven logo (emblem + wordmark) in the dark sky, top centre. */
    private void drawLogo(Canvas c, int w, int h) {
        if (logo == null) return;
        float lh = h * 0.095f, lw = lh * logo.getWidth() / logo.getHeight(), lx = w * 0.5f - lw / 2f - w * 0.02f, ly = h * 0.013f;
        r.set(lx, ly, lx + lw, ly + lh);
        img.setAlpha(255);
        c.drawBitmap(logo, null, r, img);
    }

    /** Colin's upscaled landscapes for 2-5 (holmes/ws2..ws5.webp); only the one on screen is kept in memory. */
    private Bitmap wsArt;
    private int wsArtFor;

    /** Family workspaces 2-5: the page's landscape (else the library window), the logo, the number, and a note. */
    private void drawPage(Canvas c, int w, int h) {
        if (wsArtFor != page) {
            if (wsArt != null) wsArt.recycle();
            wsArt = load(getContext(), "holmes/ws" + page + ".webp");
            wsArtFor = page;
        }
        Bitmap bg = wsArt != null ? wsArt : pageBg;
        if (bg != null) c.drawBitmap(bg, null, r, img);
        else c.drawColor(0xFF0B1B3B);
        c.drawColor(wsArt != null ? 0x33000000 : 0x66000000);   // lighter dim over the landscapes
        drawLogo(c, w, h);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(CREAM);
        text.setShadowLayer(h * 0.02f, 0, 0, 0xCC000000);
        text.setTextSize(h * 0.22f);
        c.drawText(String.valueOf(page), w * 0.528f, h * 0.55f, text);
        small.setTextAlign(Paint.Align.CENTER);
        small.setColor(0xFFE2E8F0);
        small.setShadowLayer(h * 0.01f, 0, 0, 0xCC000000);
        small.setTextSize(h * 0.034f);
        c.drawText("A space for the Holmes family, coming soon", w * 0.528f, h * 0.65f, small);
        small.setTextSize(h * 0.024f);
        small.setColor(0xB3FFF6E6);
        c.drawText("\u2039  " + (page - 1) + "      " + (page == 5 ? 6 : page + 1) + "  \u203A", w * 0.528f, h * 0.72f, small);
        postInvalidateDelayed(1000);
    }
}
