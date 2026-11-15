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
        File f = clipFor(c, theme);
        if (f == null || root == null) {
            stop(root);
            return false;
        }
        if (view != null && f.getAbsolutePath().equals(path)) return true;
        stop(root);
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
                try {
                    MediaPlayer mp = new MediaPlayer();
                    player = mp;
                    mp.setDisplay(h);
                    mp.setDataSource(p);
                    mp.setVolume(0f, 0f);
                    mp.setLooping(true);
                    mp.setScreenOnWhilePlaying(false);
                    mp.setOnVideoSizeChangedListener((m, w, hh) -> {
                        // Full-size buffer so the hardware scaler, not the GPU, fits it to the panel.
                        if (w > 0 && hh > 0 && view == sv) h.setFixedSize(w, hh);
                    });
                    mp.setOnPreparedListener(m -> {
                        if (resumeAt > 0 && resumeAt < m.getDuration()) m.seekTo(resumeAt);
                        m.start();
                        Log.i(TAG, "playing " + p + " " + m.getVideoWidth() + "x" + m.getVideoHeight());
                    });
                    mp.setOnErrorListener((m, what, extra) -> {
                        Log.w(TAG, "failed " + p + " what=" + what + " extra=" + extra + "; skipping it");
                        broken.add(k);
                        if (view == sv) stop((ViewGroup) sv.getParent());
                        return true;
                    });
                    mp.prepareAsync();
                } catch (Throwable t) {
                    Log.w(TAG, "failed " + p, t);
                    broken.add(k);
                    if (view == sv) stop((ViewGroup) sv.getParent());
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { release(); }
        });
        return true;
    }

    boolean playing() { return view != null; }

    void stop(ViewGroup root) {
        release();
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
