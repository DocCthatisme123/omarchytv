package com.omarchytv.home;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colin's fork: the Holmes Haven phone remote's hands on the TV. Android won't let an app press keys in other apps,
 * so the launcher opens its own ADB (LocalAdb, 127.0.0.1:5555; the TV asks "Allow USB debugging?" once, tick Always
 * allow) and runs Android's `hid -` tool, which makes a virtual "Holmes Haven Remote" (a HID keyboard plus consumer
 * control). Key reports written to it reach every app as real remote presses, like the BLE remote: arrows, OK,
 * Back, Home, numbers, media, volume, channels, and power (tap = standby or wake, hold = the shutdown prompt).
 * One fixed process and fixed report bytes from a whitelist: nothing from the phone reaches the shell as text.
 */
final class ColinRemote {
    private static final String TAG = "HavenRemote";
    /** HID report descriptor: report 1 = consumer control (one 16-bit usage), report 2 = keyboard (one 8-bit usage). */
    private static final String DESCRIPTOR = "5,12,9,1,161,1,133,1,21,0,38,255,3,25,0,42,255,3,117,16,149,1,129,0,192,"
            + "5,1,9,6,161,1,133,2,5,7,21,0,37,255,25,0,41,255,117,8,149,1,129,0,192";
    /** name -> {report id, usage}. Consumer usages map to KEY_* in the kernel, then Generic.kl. */
    private static final Map<String, int[]> KEYS = new HashMap<>();

    static {
        c("up", 0x42); c("down", 0x43); c("left", 0x44); c("right", 0x45); c("select", 0x41);
        c("back", 0x224); c("home", 0x223); c("menu", 0x40); c("power", 0x30);
        c("play_pause", 0xCD); c("play", 0xB0); c("pause", 0xB1); c("stop", 0xB7);
        c("next", 0xB5); c("previous", 0xB6); c("rewind", 0xB4); c("fast_forward", 0xB3);
        c("volume_up", 0xE9); c("volume_down", 0xEA); c("mute", 0xE2);
        c("channel_up", 0x9C); c("channel_down", 0x9D);
        for (int i = 1; i <= 9; i++) KEYS.put(String.valueOf(i), new int[]{2, 0x1D + i});   // 1..9 = 0x1E..0x26
        KEYS.put("0", new int[]{2, 0x27});
    }

    private static void c(String name, int usage) {
        KEYS.put(name, new int[]{1, usage});
    }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "haven-remote");
        t.setDaemon(true);
        return t;
    });
    private static volatile Context app;
    private static LocalAdb adb;   // worker thread only
    private static int stream;
    private static volatile String mac, state = "off";
    private static volatile Future<?> connecting;

    private ColinRemote() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
        com.omarchytv.home.link.ColinCompanion.remote = new com.omarchytv.home.link.ColinCompanion.Remote() {
            @Override public JSONObject key(String name, boolean hold) { return ColinRemote.key(name, hold); }
            @Override public JSONObject workspace(int n) { return ColinRemote.workspace(n); }
            @Override public JSONObject power(boolean wake) { return ColinRemote.power(wake); }
            @Override public void warm() { ColinRemote.warm(); }
            @Override public String state() { return state; }
            @Override public String mac() { return mac; }
        };
    }

    /** Get the virtual remote ready in the background (the phone app opened). */
    static void warm() {
        Future<?> f = connecting;
        if (f != null && !f.isDone()) return;
        connecting = worker.submit(() -> {
            try {
                ensure();
            } catch (Exception e) {
                Log.w(TAG, "connect: " + e.getMessage());
            }
        });
    }

    /** Press (or hold, for power) one key. {ok:true} or {error: "remote_starting" | "needs_allow" | "adb_off" | "bad_key"}. */
    static JSONObject key(String name, boolean hold) {
        int[] k = KEYS.get(name);
        if (k == null) return err("bad_key");
        if (hold && "power".equals(name)) {   // Skyworth ignores a held power key from us: our own power menu instead
            Context c = app;
            if (c == null) return err("remote_starting");
            ColinPower.request(c);
            return ok();
        }
        return send(press(k, 40));
    }

    /** The power menu's Sleep (UI thread): press power off the UI thread. */
    static void sleepSoon() {
        new Thread(() -> power(false), "haven-sleep").start();
    }

    /** The power menu's Restart / Turn off: a fixed command (never text from the phone) on our ADB shell. */
    static void shellSoon(String fixedCommand) {
        worker.submit(() -> {
            try {
                ensure();
                adb.shell(fixedCommand);
            } catch (Exception e) {
                Log.w(TAG, "power menu: " + e.getMessage());
            }
        });
    }

    /** Power button meaning "wake" or "sleep" (no-op if the TV already is). */
    static JSONObject power(boolean wake) {
        Context c = app;
        PowerManager pm = c == null ? null : c.getSystemService(PowerManager.class);
        boolean on = pm == null || pm.isInteractive();
        if (on == wake) return ok();
        return send(press(KEYS.get("power"), 40));
    }

    /** Workspace 1-10: switched directly in the launcher (no Home key, so no flash of workspace 1). */
    static JSONObject workspace(int n) {
        if (n < 1 || n > 10) return err("bad_workspace");
        Context c = app;
        if (c == null) return err("remote_starting");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ColinHavenHome.fromRemote(c, n));
        return ok();
    }

    private static String press(int[] k, int holdMs) {
        int id = k[0], u = k[1];
        String down = id == 1 ? "[1," + (u & 0xFF) + "," + (u >> 8) + "]" : "[2," + u + "]";
        String up = id == 1 ? "[1,0,0]" : "[2,0]";
        return "{\"id\":1,\"command\":\"report\",\"report\":" + down + "}\n"
                + "{\"id\":1,\"command\":\"delay\",\"duration\":" + holdMs + "}\n"
                + "{\"id\":1,\"command\":\"report\",\"report\":" + up + "}\n";
    }

    /** Queue the reports; waits briefly so the phone hears about problems (first connect, Allow prompt). */
    private static JSONObject send(String reports) {
        if (!"ready".equals(state)) {
            warm();
            return err("needs_allow".equals(state) ? "needs_allow" : "adb_off".equals(state) ? "adb_off" : "remote_starting");
        }
        Future<?> f = worker.submit(() -> {
            byte[] data = reports.getBytes(StandardCharsets.UTF_8);
            for (int attempt = 0; attempt < 2; attempt++) {   // a dropped connection: reconnect once, same press
                try {
                    ensure();
                    adb.write(stream, data);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "send: " + e.getMessage());
                    drop();
                }
            }
            return false;
        });
        try {
            Object r = f.get(3, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(r) ? ok() : err("remote_starting");
        } catch (Exception e) {
            JSONObject o = ok();   // still writing (a held power key): it should land
            try {
                o.put("pending", true);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    /** Worker thread: connect, learn the MAC (for Wake-on-LAN from the phone), start `hid -`, register the device. */
    private static void ensure() throws Exception {
        if (adb != null && stream != 0) return;
        Context c = app;
        if (c == null) throw new IllegalStateException("not started");
        state = "starting";
        adb = connectWaitingForAllow(c);
        try {
            String eth = adb.shell("dumpsys ethernet");
            Matcher m = Pattern.compile("hwAddress: ([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})").matcher(eth);
            if (m.find()) mac = m.group(1).toLowerCase(java.util.Locale.ROOT);
            stream = adb.openStream("hid -");
            String reg = "{\"id\":1,\"command\":\"register\",\"name\":\"Holmes Haven Remote\",\"vid\":4660,\"pid\":22136,"
                    + "\"descriptor\":[" + DESCRIPTOR + "]}\n{\"id\":1,\"command\":\"delay\",\"duration\":800}\n";
            adb.write(stream, reg.getBytes(StandardCharsets.UTF_8));
            state = "ready";
            Log.i(TAG, "ready (mac " + mac + ")");
        } catch (Exception e) {
            drop();
            throw e;
        }
    }

    /**
     * Known key: straight in. Unknown: one connection offers the key (the TV's prompt) and waits in the background,
     * while a quiet connection that never offers the key retries every 2 s; Android 10 doesn't always tell the
     * waiting connection about "Always allow", but the key is trusted from the moment it's tapped (Nook, 12:02).
     */
    private static LocalAdb connectWaitingForAllow(Context c) throws Exception {
        try {
            return LocalAdb.connectKnown(c);
        } catch (java.io.IOException e) {
            if (String.valueOf(e.getMessage()).contains("port 5555")) {
                state = "adb_off";
                throw e;
            }
        }
        // Say on the TV, before Android's prompt, that it's ours (Colin only taps Allow when one of us asked).
        explain(c, "Holmes Haven Remote: the TV will now ask \u201cAllow USB debugging?\u201d. "
                + "That's your phone remote. Tick \u201cAlways allow\u201d, then OK.");
        final LocalAdb[] prompted = new LocalAdb[1];
        final boolean[] done = new boolean[1];
        Thread asker = new Thread(() -> {
            try {
                LocalAdb a = LocalAdb.connect(c, 120_000, () -> state = "needs_allow");
                synchronized (prompted) {
                    if (done[0]) a.close();   // the quiet connection got in first
                    else prompted[0] = a;
                }
            } catch (Exception ignored) {
            }
        }, "haven-remote-allow");
        asker.setDaemon(true);
        asker.start();
        long until = android.os.SystemClock.uptimeMillis() + 120_000;
        while (android.os.SystemClock.uptimeMillis() < until) {
            Thread.sleep(2000);
            synchronized (prompted) {
                if (prompted[0] != null) {
                    done[0] = true;
                    return prompted[0];
                }
            }
            try {
                LocalAdb a = LocalAdb.connectKnown(c);
                synchronized (prompted) {
                    done[0] = true;
                    if (prompted[0] != null) prompted[0].close();
                }
                return a;
            } catch (java.io.IOException ignored) {
            }
        }
        synchronized (prompted) {
            done[0] = true;
            if (prompted[0] != null) return prompted[0];
        }
        state = "needs_allow";
        throw new java.io.IOException("Allow wasn't tapped on the TV");
    }

    /** A long toast on the TV, on the UI thread. */
    private static void explain(Context c, String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (int i = 0; i < 2; i++) android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private static void drop() {
        if (adb != null) adb.close();
        adb = null;
        stream = 0;
        if ("ready".equals(state) || "starting".equals(state)) state = "off";
    }

    private static JSONObject ok() {
        try {
            return new JSONObject().put("ok", true);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject err(String code) {
        try {
            return new JSONObject().put("error", code);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
