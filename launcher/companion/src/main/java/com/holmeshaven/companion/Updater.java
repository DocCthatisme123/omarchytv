package com.holmeshaven.companion;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Update-on-open: the TV holds the newest phone APK (Nook puts it there with each build). The app asks over the
 * signed link, downloads it over the same link, checks its SHA-256 against what the TV announced, and hands it
 * to Android, which shows its own "Update?" prompt and refuses anything not signed with Colin's key.
 */
final class Updater {
    private Updater() {
    }

    static long myVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The TV's offer if it's newer than this app, else null. Blocking. */
    static JSONObject check(Context c, Link link) throws IOException {
        JSONObject o = link.getJson("/v1/companion/update");
        if (!o.optBoolean("available", false)) return null;
        if (!c.getPackageName().equals(o.optString("package"))) return null;
        return o.optLong("versionCode", 0) > myVersion(c) ? o : null;
    }

    /** Download, verify and start the install. Blocking; Android's prompt follows. */
    static void install(Context c, Link link, JSONObject offer) throws Exception {
        byte[] apk = link.getBytes("/v1/companion/apk", 60_000);
        String want = offer.optString("sha256", "");
        String got = LinkCrypto.hex(MessageDigest.getInstance("SHA-256").digest(apk));
        if (want.isEmpty() || !want.equalsIgnoreCase(got)) throw new IOException("the download didn't match; not installing");
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(apk.length);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("holmes-haven.apk", 0, apk.length)) {
                o.write(apk);
                s.fsync(o);
            }
            Intent done = new Intent(c, Done.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    /** Android asks the user to confirm: show its prompt. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation") Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                android.widget.Toast.makeText(c, "Update didn't install: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
