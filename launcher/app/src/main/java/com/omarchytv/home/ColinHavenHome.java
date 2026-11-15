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
    private static final int FADE_MS = 160, IDLE_MS = 30_000, CREAM = 0xFFFFF6E6, GOLD_BAR = 0xFFFFC94D;

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
    /**
     * True 4K: Android draws app UI at 1080p on this TV, only the video plane is 4K. So each family workspace's
     * background can be a still 4K clip (files/video/haven-N.mp4, every frame the same picture) on a SurfaceView
     * behind this view. Once its first frame is up we stop drawing the 1080p bitmap (this view turns see-through)
     * and hide Marco's home underneath so it can't show through. No clip = the 1080p bitmap, as before.
     */
    private final ColinVideoWallpaper video4k = new ColinVideoWallpaper();
    private FrameLayout root;
    private int videoPage;
    private boolean videoOn;
    /** When the 4K frame came up: the 1080p picture fades out over FADE_4K_MS instead of switching (no flicker). */
    private long videoOnAt;
    private static final int FADE_4K_MS = 350, LEAVE_MS = 160;
    /** Opening a book: when the fade to black started (0 = not leaving). */
    private long leavingAt;
    /** Floating Omarchy bar: Marco's right-hand icons (same actions), -1 = not focused. */
    private int barFocus = -1;
    private static final int[] BAR_ICONS = {R.drawable.ln_cinemarchy, R.drawable.ln_arcade, R.drawable.ln_wifi,
            R.drawable.ln_bt, R.drawable.ln_vol, R.drawable.ln_input, R.drawable.ln_monitor};
    private final android.graphics.drawable.Drawable[] barIcons = new android.graphics.drawable.Drawable[BAR_ICONS.length];
    private static java.util.function.IntConsumer barClick;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.List<View> hiddenStock = new java.util.ArrayList<>();

    private ColinHavenHome(Context c, Bitmap master) {
        super(c);
        this.master = master;
        pageBg = load(c, "holmes/boot_bg.webp");
        logo = load(c, "holmes/logo_bar.webp");
        for (int i = 0; i < BAR_ICONS.length; i++) {
            barIcons[i] = c.getDrawable(BAR_ICONS[i]);
            if (barIcons[i] != null) barIcons[i] = barIcons[i].mutate();
        }
        bar.setTypeface(CastUi.font(c));
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
                       java.util.function.IntSupplier getStock, java.util.function.IntConsumer clickBarIcon) {
        view = null;
        barClick = clickBarIcon;
        stockSet = setStock;
        stockGet = getStock;
        if (root == null || !LauncherConfig.get(a).havenHome) return;
        Bitmap m = load(a, "holmes/home_master.webp");
        if (m == null) return;
        ColinHavenHome v = new ColinHavenHome(a, m);
        v.root = root;
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
        v.barFocus = -1;
        v.leavingAt = 0;
        v.syncVideo();
        v.invalidate();
    }

    /** From MainActivity.onPause: release the 4K decoder while Home isn't in front (show() restarts it). */
    static void pauseVideo() {
        ColinHavenHome v = view;
        if (v != null) v.stopVideo();
    }

    private void syncVideo() {
        java.io.File dir = getContext().getExternalFilesDir("video");
        java.io.File f = dir == null || hidden ? null : new java.io.File(dir, "haven-" + page + ".mp4");
        if (!ColinVideoWallpaper.usable(f)) {
            stopVideo();
            return;
        }
        if (videoPage == page && video4k.playing()) return;
        videoPage = page;
        final int want = page;
        Runnable up = () -> {
            if (hidden || page != want || videoPage != want) return;
            if (!videoOn) videoOnAt = SystemClock.uptimeMillis();   // first picture: fade up from black
            videoOn = true;
            hideStock();
            invalidate();
        };
        // Already showing a 4K picture: swap the clip in the same player (old 4K stays until the new one is up).
        if (videoOn && video4k.switchTo(f, up)) return;
        videoOn = false;   // black until the first 4K frame, then it fades up (no 1080p step)
        hideStock();
        video4k.startFile(getContext(), root, f, up);
    }

    private void stopVideo() {
        video4k.stop(root);
        videoOn = false;
        videoPage = 0;
        restoreStock();
        invalidate();
    }

    /** Hide Marco's home (every other root child except our 4K surface and the boot screen) while the video shows. */
    private void hideStock() {
        if (root == null) return;
        View surface = video4k.surface();
        for (int i = 0; i < root.getChildCount(); i++) {
            View ch = root.getChildAt(i);
            if (ch == this || ch == surface || ch.getTranslationZ() >= 9_000f || ch.getVisibility() != VISIBLE) continue;
            ch.setVisibility(INVISIBLE);
            hiddenStock.add(ch);
        }
    }

    private void restoreStock() {
        for (View ch : hiddenStock) if (ch.getVisibility() == INVISIBLE) ch.setVisibility(VISIBLE);
        hiddenStock.clear();
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
            v.barFocus = -1;
            v.syncVideo();
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
        boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;

        if (v.barFocus >= 0) {   // in the floating bar: Left/Right along the icons, OK runs Marco's action, Down leaves
            switch (kc) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) v.barFocus = Math.max(0, v.barFocus - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) v.barFocus = Math.min(BAR_ICONS.length - 1, v.barFocus + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) v.barFocus = -1;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    break;
                default:
                    if (ok) {
                        if (press && barClick != null) barClick.accept(v.barFocus);
                        break;
                    }
                    return false;
            }
            v.invalidate();
            return true;
        }

        if (v.page != 1) {   // family workspaces 2-5: Left/Right step, Up to the bar, the rest waits for its design
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (press) go(v.getContext(), v.page - 1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (press) go(v.getContext(), v.page + 1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                if (press) v.toBar();
                return true;
            }
            return kc == KeyEvent.KEYCODE_DPAD_DOWN || ok;
        }
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:   // Up from the top book (or with nothing picked) goes to the bar
                if (down) {
                    if (v.sel <= 0) {
                        if (press) v.toBar();
                    } else v.move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) v.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (press) {
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

    private void toBar() {
        barFocus = BAR_ICONS.length - 1;   // start on the right end, like reaching up to the tray
        if (sel >= 0) select(-1);
        invalidate();
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
        // Fade the room to black around the pulled-out book, then open: the 4K picture has to give the
        // decoder to the app, and this way that moment is black, not a flash of black squiggles.
        if (leavingAt == 0) {
            leavingAt = SystemClock.uptimeMillis();
            invalidate();
            postDelayed(() -> launch(b), LEAVE_MS);
        }
    }

    private void launch(Book b) {
        Context c = getContext();
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(b.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(b.pkg);
        if (i == null) {
            leavingAt = 0;
            invalidate();
            Toast.makeText(c, b.label + ": app not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            leavingAt = 0;
            invalidate();
            Toast.makeText(c, b.label + ": can't open", Toast.LENGTH_SHORT).show();
        }
    }

    /** Slide the bookshelf away to the left and reveal the stock OmarchyTV home. */
    private void hide() {
        hidden = true;
        stopVideo();
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
        if (barFocus >= 0 && now - lastKey > IDLE_MS) barFocus = -1;

        // 1080p picture: fully drawn until the 4K frame is up, then a soft fade (the 4K clip shows through).
        float pic = videoOn ? 1f - Math.min(1f, (now - videoOnAt) / (float) FADE_4K_MS) : 1f;
        boolean clip = videoPage == page && videoPage != 0;   // a 4K clip is (being) shown: cover with black, not 1080p
        boolean fading = videoOn && pic > 0f;
        img.setAlpha(255);
        r.set(0, 0, w, h);
        if (page != 1) {
            drawPage(c, w, h, pic, clip);
        } else {
            if (pic > 0f) {
                if (clip) c.drawColor(((int) (255 * pic) << 24));
                else {   // no 4K clip on the TV: the 1080p picture (fallback only)
                    img.setAlpha((int) (255 * pic));
                    c.drawBitmap(master, null, r, img);
                    img.setAlpha(255);
                }
            }
            // Shelf: the previous selection fading out under the new one fading in (both identical to the
            // master outside their own book, so the crossfade only moves the books).
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
            fading |= t < 1f;
        }
        if (leavingAt != 0) {   // opening a book: the room fades to black, the pulled-out book stays lit
            float lv = Math.min(1f, (now - leavingAt) / (float) LEAVE_MS);
            c.drawColor(((int) (255 * lv) << 24));
            if (sel >= 0 && shelf[sel] != null) {
                r.set(w * SX, h * SY, w * (SX + SW), h * (SY + SH));
                img.setAlpha(255);
                c.drawBitmap(shelf[sel], null, r, img);
            }
            fading |= lv < 1f;
        }
        drawBar(c, w, h);

        if (fading) postInvalidateOnAnimation();
        else postInvalidateDelayed(1000);   // clock and idle check
    }

    /**
     * Floating Omarchy bar (no background, everything with a soft shadow): our logo + time/date in the middle,
     * Marco's tray icons on the right; on 2-5 also the workspace numbers on the left (this one bold, the rest faded)
     * after our emblem, where Omarchy has its logo. Workspace 1 stays clean: no numbers.
     */
    private void drawBar(Canvas c, int w, int h) {
        float bh = h * 0.048f, cy = bh * 0.55f + h * 0.008f;
        Date d = new Date();
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d);
        // the bar's own text (workspace numbers) keeps Marco's mono font; its baseline:
        bar.setTextSize(h * 0.026f);
        bar.setTextAlign(Paint.Align.LEFT);
        bar.setColor(CREAM);
        bar.setShadowLayer(h * 0.008f, 0, 0, 0xE6000000);
        bar.setFakeBoldText(false);
        Paint.FontMetrics fm = bar.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2f;
        if (page == 1) {
            // Workspace 1 (Colin's pick): logo centred, the big clock and full date in the top right corner,
            // just under the tray icons, in the bookshelf's serif.
            if (logo != null) {
                float lh = h * 0.095f, lw = lh * logo.getWidth() / logo.getHeight(), lx = w * 0.5f - lw / 2f - w * 0.02f, ly = h * 0.013f;
                r.set(lx, ly, lx + lw, ly + lh);
                img.setAlpha(255);
                c.drawBitmap(logo, null, r, img);
            }
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(h * 0.075f);
            text.setColor(CREAM);
            text.setShadowLayer(h * 0.012f, 0, 0, 0xCC000000);
            c.drawText(time, w * 0.965f, h * 0.145f, text);
            small.setTextAlign(Paint.Align.RIGHT);
            small.setTextSize(h * 0.028f);
            small.setColor(0xFFE2E8F0);
            small.setShadowLayer(h * 0.008f, 0, 0, 0xCC000000);
            c.drawText(date, w * 0.965f, h * 0.19f, small);
        } else {
            // Workspaces 2-5: logo and, to its right, the date and time in the same serif as workspace 1.
            if (logo != null) {
                float lh = h * 0.080f, lw = lh * logo.getWidth() / logo.getHeight(), lx = w * 0.5f - lw - w * 0.008f, ly = cy - lh / 2f;
                r.set(lx, ly, lx + lw, ly + lh);
                img.setAlpha(255);
                c.drawBitmap(logo, null, r, img);
            }
            small.setTextAlign(Paint.Align.LEFT);
            small.setTextSize(h * 0.030f);
            small.setColor(CREAM);
            small.setShadowLayer(h * 0.008f, 0, 0, 0xE6000000);
            Paint.FontMetrics sm = small.getFontMetrics();
            c.drawText(date + "   " + time, w * 0.5f + w * 0.008f, cy - (sm.ascent + sm.descent) / 2f, small);
        }

        // right: Marco's tray icons, same order and actions
        float isz = h * 0.024f, step = h * 0.046f, x = w - w * 0.02f - step * BAR_ICONS.length;
        for (int i = 0; i < BAR_ICONS.length; i++) {
            android.graphics.drawable.Drawable ic = barIcons[i];
            float ix = x + i * step + (step - isz) / 2f;
            if (ic != null) {
                ic.setTint(barFocus == i ? GOLD_BAR : CREAM);
                ic.setBounds((int) ix, (int) (cy - isz / 2f), (int) (ix + isz), (int) (cy + isz / 2f));
                // soft shadow: draw a dark copy slightly larger behind it
                ic.setTint(0x99000000);
                ic.setBounds((int) ix + 1, (int) (cy - isz / 2f) + 2, (int) (ix + isz) + 1, (int) (cy + isz / 2f) + 2);
                ic.draw(c);
                ic.setTint(barFocus == i ? GOLD_BAR : CREAM);
                ic.setBounds((int) ix, (int) (cy - isz / 2f), (int) (ix + isz), (int) (cy + isz / 2f));
                ic.draw(c);
            }
            if (barFocus == i) {   // Waybar focus: 3 px underline
                bar.setColor(GOLD_BAR);
                c.drawRect(ix - isz * 0.2f, cy + isz * 0.85f, ix + isz * 1.2f, cy + isz * 0.85f + 3f, bar);
            }
        }

        // left (2-5 only): our emblem where Omarchy has its logo, then 1 2 3 4 5, this one bold
        if (page != 1) {
            float lx = w * 0.02f;
            if (logo != null) {   // the emblem is the left part of the logo lockup
                float eh = h * 0.040f, srcW = logo.getHeight();   // emblem is roughly square at the left of the lockup
                android.graphics.Rect src = new android.graphics.Rect(0, 0, (int) srcW, logo.getHeight());
                r.set(lx, cy - eh / 2f, lx + eh, cy + eh / 2f);
                c.drawBitmap(logo, src, r, img);
                lx += eh + w * 0.012f;
            }
            bar.setTextSize(h * 0.026f);
            for (int n = 1; n <= 5; n++) {
                boolean on = n == page;
                bar.setFakeBoldText(on);
                bar.setColor(on ? CREAM : 0x73FFF6E6);
                c.drawText(String.valueOf(n), lx, base, bar);
                if (on) {   // Waybar active marker
                    float tw = bar.measureText(String.valueOf(n));
                    c.drawRect(lx - 2f, cy + h * 0.016f, lx + tw + 2f, cy + h * 0.016f + 3f, bar);
                }
                lx += h * 0.042f;
            }
            bar.setFakeBoldText(false);
        }
    }

    /** Colin's upscaled landscapes for 2-5 (holmes/ws2..ws5.webp); only the one on screen is kept in memory. */
    private Bitmap wsArt;
    private int wsArtFor;

    /** Family workspaces 2-5: the page's landscape (1080p until its 4K clip is up), the number and a note. */
    private void drawPage(Canvas c, int w, int h, float pic, boolean clip) {
        if (!clip && wsArtFor != page) {   // only decode the 1080p fallback when there is no 4K clip
            if (wsArt != null) wsArt.recycle();
            wsArt = load(getContext(), "holmes/ws" + page + ".webp");
            wsArtFor = page;
        }
        Bitmap bg = wsArt != null ? wsArt : pageBg;
        if (pic > 0f) {
            if (clip) c.drawColor(((int) (255 * pic) << 24));
            else {   // no 4K clip on the TV: the 1080p picture (fallback only)
                img.setAlpha((int) (255 * pic));
                if (bg != null) c.drawBitmap(bg, null, r, img);
                else c.drawColor(0xFF0B1B3B);
                img.setAlpha(255);
            }
        }
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
    }
}
