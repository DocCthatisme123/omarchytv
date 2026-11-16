package com.omarchytv.home.link;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
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
    private static final long MAX_APK = 40L * 1024 * 1024;

    private static volatile Context app;
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
    static LinkServer.Response route(String m, String p) throws JSONException {
        if (!"GET".equals(m) || p == null || !p.startsWith("/v1/companion/")) return null;
        if ("/v1/companion/update".equals(p)) return new LinkServer.Response(200, update());
        if ("/v1/companion/apk".equals(p)) {
            File f = apk();
            if (f == null) return new LinkServer.Response(404, new JSONObject().put("error", "no_update"));
            try {
                LinkServer.Response r = new LinkServer.Response(200, null);
                r.body = Files.readAllBytes(f.toPath());
                r.headers.put("X-Haven-Sha256", sha256(f));
                return r;
            } catch (Exception e) {
                return new LinkServer.Response(500, new JSONObject().put("error", "read_failed"));
            }
        }
        return new LinkServer.Response(404, new JSONObject().put("error", "not_found"));
    }

    private static JSONObject update() throws JSONException {
        JSONObject o = new JSONObject().put("available", false).put("package", PACKAGE);
        File f = apk();
        Context c = app;
        if (f == null || c == null) return o;
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null || !PACKAGE.equals(pi.packageName)) return o;
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
        } catch (Exception ignored) {
        }
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
