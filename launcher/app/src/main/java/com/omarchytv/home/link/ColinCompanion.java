package com.omarchytv.home.link;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Colin's fork: the Holmes Haven phone app's routes on the Omarchy Link (paired and signed, like every other
 * route). Nook drops the newest phone APK at
 *   /sdcard/Android/data/com.omarchytv.home/files/companion/holmes-haven.apk
 * and the phone, when opened, asks:
 *   GET /v1/companion/update  {available, package, versionCode, versionName, size, sha256}
 *   GET /v1/companion/apk     the APK bytes (the response signature covers them; the phone also checks sha256)
 * Plus TV status extras for the phone's status card (uptime, storage).
 */
public final class ColinCompanion {
    public static final String PACKAGE = "com.holmeshaven.companion";
    private static final long MAX_APK = 8L * 1024 * 1024;   // held in memory on a 32-bit TV; ours is ~0.2 MB

    private static volatile Context app;

    /** The phone remote (ColinRemote, in the launcher package; set at start). */
    public interface Remote {
        JSONObject key(String name, boolean hold);
        JSONObject workspace(int n);
        JSONObject power(boolean wake);
        void warm();
        String state();
        String mac();
    }

    public static volatile Remote remote;
    private static String shaKey, sha;

    private ColinCompanion() {
    }

    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    static File apk() {
        Context c = app;
        File dir = c == null ? null : c.getExternalFilesDir("companion");
        File f = dir == null ? null : new File(dir, "holmes-haven.apk");
        return f != null && f.isFile() && f.length() > 0 && f.length() <= MAX_APK ? f : null;
    }

    /** One of ours, or null so LinkServer carries on with its own routes. */
    static LinkServer.Response route(String m, String p, JSONObject body) throws JSONException {
        if ("POST".equals(m) && p != null && p.startsWith("/v1/haven/")) return haven(p, body);
        if (!"GET".equals(m) || p == null || !p.startsWith("/v1/companion/")) return null;
        if ("/v1/companion/update".equals(p)) return new LinkServer.Response(200, update());
        if ("/v1/companion/apk".equals(p)) {
            File f = apk();
            if (f == null) return new LinkServer.Response(404, new JSONObject().put("error", "no_update"));
            try {
                LinkServer.Response r = new LinkServer.Response(200, null);
                r.body = readAll(f);
                r.headers.put("X-Haven-Sha256", sha256(f));
                return r;
            } catch (Exception e) {
                return new LinkServer.Response(500, new JSONObject().put("error", "read_failed"));
            }
        }
        return new LinkServer.Response(404, new JSONObject().put("error", "not_found"));
    }

    /**
     * POST /v1/haven/key {key, hold}: a real remote press (see ColinRemote); key "wakeup" / "sleep" = power only if
     * needed. POST /v1/haven/workspace {n}: Home, then workspace n (1-10).
     * 200 {ok}, or 503 {error: remote_starting | needs_allow | adb_off}, 400 {error: bad_key}.
     */
    private static LinkServer.Response haven(String p, JSONObject body) throws JSONException {
        Remote r = remote;
        if (r == null) return new LinkServer.Response(503, new JSONObject().put("error", "remote_starting"));
        JSONObject o;
        if ("/v1/haven/key".equals(p)) {
            String k = body.optString("key", "");
            o = "wakeup".equals(k) ? r.power(true) : "sleep".equals(k) ? r.power(false) : r.key(k, body.optBoolean("hold", false));
        } else if ("/v1/haven/warm".equals(p)) {   // the phone app opened: get the remote ready, quick first press
            r.warm();
            o = new JSONObject().put("ok", true).put("remote", r.state());
        } else if ("/v1/haven/workspace".equals(p)) {
            o = r.workspace(body.optInt("n", 0));
        } else {
            return new LinkServer.Response(404, new JSONObject().put("error", "not_found"));
        }
        String e = o.optString("error", "");
        int status = e.isEmpty() ? 200 : e.startsWith("bad_") ? 400 : 503;
        return new LinkServer.Response(status, o);
    }

    private static JSONObject update() throws JSONException {
        JSONObject o = new JSONObject().put("available", false).put("package", PACKAGE);
        File f = apk();
        Context c = app;
        if (f == null || c == null) return o;
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null || !PACKAGE.equals(pi.packageName)) return o;
        // Other TV apps can write into our external folder: only offer an APK signed with the same key as this launcher.
        if (!sameSigner(c, f)) return o;
        String s = sha256(f);
        if (s == null) return o;
        @SuppressWarnings("deprecation") long vc = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        return o.put("available", true).put("versionCode", vc).put("versionName", String.valueOf(pi.versionName))
                .put("size", f.length()).put("sha256", s);
    }

    /** Uptime and storage for the phone's TV status card. */
    public static void statusExtras(JSONObject o) {
        try {
            o.put("uptimeS", SystemClock.elapsedRealtime() / 1000);
            StatFs fs = new StatFs(android.os.Environment.getDataDirectory().getPath());
            o.put("storageFreeMB", fs.getAvailableBytes() / (1024 * 1024));
            o.put("storageTotalMB", fs.getTotalBytes() / (1024 * 1024));
            o.put("androidVersion", android.os.Build.VERSION.RELEASE);
            Remote r = remote;
            if (r != null) {
                o.put("remote", r.state());
                if (r.mac() != null) o.put("wakeMac", r.mac());
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean sameSigner(Context c, File f) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            PackageInfo a = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            Signature[] theirs = a == null ? null : a.signatures;
            if (mine == null || theirs == null || mine.length == 0 || mine.length != theirs.length) return false;
            for (int i = 0; i < mine.length; i++) if (!mine[i].equals(theirs[i])) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            if (off != b.length) throw new java.io.IOException("short read");
        }
        return b;
    }

    private static synchronized String sha256(File f) {
        String k = f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
        if (k.equals(shaKey)) return sha;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            sha = LinkCrypto.hex(md.digest());
            shaKey = k;
            return sha;
        } catch (Exception e) {
            return null;
        }
    }
}
