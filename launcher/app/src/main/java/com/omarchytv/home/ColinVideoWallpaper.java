package com.omarchytv.home;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Colin's fork: a full-quality looping video wallpaper on a SurfaceView, so the TV decodes it in hardware
 * on the video plane (4K on the Skyworth) instead of going through a TextureView like the bundled intros.
 *
 * Drop clips in the app's own folder (no storage permission needed):
 *   /sdcard/Android/data/com.omarchytv.home/files/video/{theme}.mp4   (e.g. cumulus.mp4), or
 *   /sdcard/Android/data/com.omarchytv.home/files/video/default.mp4     (any theme)
 * No file = the stock wallpaper and intro, unchanged. A clip that fails to play is skipped until the app restarts,
 * and the still wallpaper underneath stays visible. Muted, always looping; resumes where it left off.
 */
final class ColinVideoWallpaper {
    private static final String TAG = "OmarchyVideo";
    private static final Set<String> broken = new HashSet<>();

    private SurfaceView view;
    private MediaPlayer player;
    private SurfaceHolder holder;
    /** Hold mode (one clip, many stills): -1 = normal looping wallpaper; else the ms to show and freeze on. */
    private int holdMs = -1;
    private boolean prepared;
    private Runnable holdFrame;
    private String path;
    private int resumeAt;

    /** A replaced clip at the same path gets a fresh chance. */
    private static String key(File f) { return f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified(); }

    /** Clip for this theme, or null. */
    static File clipFor(Context c, String theme) {
        File dir = c.getExternalFilesDir("video");
        if (dir == null) return null;
        for (String n : new String[]{theme + ".mp4", "default.mp4"}) {
            File f = new File(dir, n);
            if (f.isFile() && f.length() > 0 && !broken.contains(key(f))) return f;
        }
        return null;
    }

    /** Start the video behind the UI (at index 1, just above the still wallpaper). True if a clip is playing/starting. */
    boolean start(Context c, ViewGroup root, String theme) {
        return startFile(c, root, clipFor(c, theme), null);
    }

    /** True if a playable clip exists at this path (not marked broken). */
    static boolean usable(File f) {
        return f != null && f.isFile() && f.length() > 0 && !broken.contains(key(f));
    }

    /**
     * Play this clip behind the UI. {@code onFrame} runs on the UI thread once its first frame is on screen
     * (or never, if it fails). True if a clip is playing/starting.
     */
    boolean startFile(Context c, ViewGroup root, File f, Runnable onFrame) {
        return start(c, root, f, onFrame, -1, null);
    }

    /** startFile, in hold mode when {@code hold >= 0}. The hold is set after stop(), which clears it. */
    private boolean start(Context c, ViewGroup root, File f, Runnable onFrame, int hold, Runnable frame) {
        if (!usable(f)) f = null;
        if (f == null || root == null) {
            stop(root);
            return false;
        }
        if (view != null && f.getAbsolutePath().equals(path)) {
            if (hold >= 0) {   // surface still there, player coming back with it: hold the current page then
                holdMs = hold;
                holdFrame = frame;
            }
            if (onFrame != null && player != null) view.post(onFrame);   // already playing
            return true;
        }
        stop(root);
        holdMs = hold;   // after stop(): it resets the hold, which left a wake-up looping all five pictures behind black
        holdFrame = frame;
        if (!f.getAbsolutePath().equals(path)) resumeAt = 0;
        path = f.getAbsolutePath();
        final String p = path, k = key(f);
        SurfaceView sv = new SurfaceView(c);
        sv.setFocusable(false);
        sv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view = sv;
        root.addView(sv, Math.min(1, root.getChildCount()), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (view != sv) return;
                holder = h;
                MediaPlayer mp = new MediaPlayer();
                player = mp;
                if (!load(mp, sv, h, p, k, onFrame)) {
                    broken.add(k);
                    if (view == sv) stop((ViewGroup) sv.getParent());
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                holder = null;
                release();
            }
        });
        return true;
    }

    /**
     * Swap to another clip on the same surface and player (no teardown): the old picture stays on the video plane
     * until the new one's first frame replaces it, so there is no black or 1080p step in between.
     * False if nothing is playing yet (then use {@link #startFile}).
     */
    boolean switchTo(File f, Runnable onFrame) {
        if (!usable(f) || view == null || player == null || holder == null) return false;
        if (f.getAbsolutePath().equals(path)) {
            if (onFrame != null) view.post(onFrame);
            return true;
        }
        path = f.getAbsolutePath();
        resumeAt = 0;
        MediaPlayer mp = player;
        try {
            mp.reset();
        } catch (Throwable t) {
            return false;
        }
        return load(mp, view, holder, path, key(f), onFrame);
    }

    /**
     * Hold mode: play this clip (a strip of stills, one per segment) and freeze on the picture at {@code ms}.
     * The first call starts the player; later calls only seek within the same stream, so the video never stops
     * (no black gap, and the TV's picture processing doesn't re-adapt). onFrame once that picture is up.
     */
    boolean holdAt(Context c, ViewGroup root, File f, int ms, Runnable onFrame) {
        if (!usable(f)) return false;
        holdFrame = onFrame;
        if (view != null && player != null && f.getAbsolutePath().equals(path)) {
            holdMs = ms;
            if (prepared) seekHold(player);
            return true;
        }
        prepared = false;
        return start(c, root, f, null, ms, onFrame);
    }

    /** Seek to holdMs, play a moment so the frame is decoded and shown, then pause on it. */
    private void seekHold(MediaPlayer m) {
        final int target = holdMs;
        final SurfaceView sv = view;
        m.setOnSeekCompleteListener(mp -> {
            if (player != mp || holdMs != target) return;
            try {
                mp.start();
            } catch (Throwable ignored) {
            }
            if (sv != null) sv.postDelayed(() -> {
                if (player == mp && holdMs == target) {
                    try { mp.pause(); } catch (Throwable ignored) {}
                    if (holdFrame != null) holdFrame.run();
                }
            }, 120);
        });
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) m.seekTo(target, MediaPlayer.SEEK_CLOSEST_SYNC);
            else m.seekTo(target);
        } catch (Throwable t) {
            Log.w(TAG, "seek failed", t);
        }
    }

    /** Point the player at clip p on this surface, looping and muted; onFrame once its picture is up. */
    private boolean load(MediaPlayer mp, SurfaceView sv, SurfaceHolder h, String p, String k, Runnable onFrame) {
        try {
            mp.setDisplay(h);
            mp.setDataSource(p);
            mp.setVolume(0f, 0f);
            mp.setLooping(holdMs < 0);
            mp.setScreenOnWhilePlaying(false);
            mp.setOnVideoSizeChangedListener((m, w, hh) -> {
                // Full-size buffer so the hardware scaler, not the GPU, fits it to the panel.
                if (w > 0 && hh > 0 && view == sv) h.setFixedSize(w, hh);
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && onFrame != null && view == sv && p.equals(path)) sv.post(onFrame);
                return false;
            });
            mp.setOnPreparedListener(m -> {
                if (!p.equals(path) || player != m) return;
                prepared = true;
                if (holdMs >= 0) {   // hold mode: show and freeze on one still
                    Log.i(TAG, "holding " + p + " " + m.getVideoWidth() + "x" + m.getVideoHeight() + " at " + holdMs);
                    seekHold(m);
                    return;
                }
                if (resumeAt > 0 && resumeAt < m.getDuration()) m.seekTo(resumeAt);
                m.start();
                Log.i(TAG, "playing " + p + " " + m.getVideoWidth() + "x" + m.getVideoHeight());
                // Some players (this MediaTek one) never send VIDEO_RENDERING_START: assume the frame is up shortly after start.
                if (onFrame != null) sv.postDelayed(() -> { if (view == sv && player == m && p.equals(path)) onFrame.run(); }, 450);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "failed " + p + " what=" + what + " extra=" + extra + "; skipping it");
                broken.add(k);
                if (view == sv) stop((ViewGroup) sv.getParent());
                return true;
            });
            mp.prepareAsync();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "failed " + p, t);
            return false;
        }
    }

    boolean playing() { return view != null; }

    /** The SurfaceView it plays on, or null. */
    View surface() { return view; }

    void stop(ViewGroup root) {
        release();
        prepared = false;
        holdMs = -1;
        holdFrame = null;
        if (view != null) {
            View v = view;
            view = null;
            ViewGroup parent = (ViewGroup) v.getParent();
            if (parent != null) parent.removeView(v);
            else if (root != null) root.removeView(v);
        }
    }

    private void release() {
        if (player != null) {
            MediaPlayer mp = player;
            player = null;
            try { resumeAt = mp.getCurrentPosition(); } catch (Throwable ignored) {}
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
    }
}
