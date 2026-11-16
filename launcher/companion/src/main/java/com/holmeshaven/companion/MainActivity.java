package com.holmeshaven.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holmes Haven on the phone, step M1: find the TV, pair with the code it shows, then a status card and
 * update-on-open. The remote (M2) and the app list (M3) build on this screen.
 * Holmes Haven look: Night Sky to Nebula, Haven Gold serif titles, bold colourful buttons.
 */
public final class MainActivity extends Activity {
    static final int NIGHT_SKY = 0xFF0B1B3B, DEEP = 0xFF1A1240, AURORA = 0xFF1E90FF, NEBULA = 0xFF8A2BE2,
            COSMIC = 0xFFFF2D95, GOLD = 0xFFFFC94D, LEAF = 0xFF22C55E, MOON = 0xFFE2E8F0, CREAM = 0xFFFFF6E6,
            MUTED = 0xFF94A3B8, CARD = 0x26FFFFFF;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    /** Key presses get their own thread, so a slow status refresh never delays the remote. */
    private final ExecutorService keys = Executors.newSingleThreadExecutor();
    private TextView remoteLine;
    /** Last status: null = TV didn't answer (off or away), else whether its screen is on. */
    private Boolean screenOn;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private Finder finder;
    private Vault.Tv tv;
    private boolean onHome, busy;
    private TextView statusLine, updateLine;
    private LinearLayout statusRows;
    private Button updateButton;
    private JSONObject offer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(NIGHT_SKY);
        getWindow().setNavigationBarColor(DEEP);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{NIGHT_SKY, DEEP}));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(28));
        sv.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        finder = new Finder(this);
        tv = Vault.load(this);
        if (tv == null) showFind();
        else showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (onHome) {
            refresh(true);   // update-on-open: every time the app comes to the front
            warm();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        finder.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
        keys.shutdownNow();
    }

    // ---- 1. find the TV ------------------------------------------------------------------------

    private void showFind() {
        onHome = false;
        clear();
        header("Let's find your TV");
        body("Make sure this phone is on the home Wi-Fi. Your TV shows up here by itself.");
        LinearLayout list = column();
        page.addView(list);
        TextView searching = body("Looking\u2026");
        searching.setTextColor(MUTED);
        list.addView(own(searching));
        Set<String> seen = new HashSet<>();
        finder.start((name, host, tvId, open) -> {
            if (!seen.add(host)) return;
            list.removeView(searching);
            Button btn = button("\u1F4FA  " + name, AURORA, NEBULA);
            btn.setOnClickListener(v -> showPair(host, name));
            list.addView(own(btn));
        });

        space(18);
        TextView or = small("Not showing? Type the TV's address instead:");
        EditText ip = input("192.168.40.180", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        Button use = button("Use this address", NEBULA, COSMIC);
        use.setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                toastLine(or, "That doesn't look like an address (four numbers with dots).");
                return;
            }
            hideKeyboard(ip);
            work(() -> {
                JSONObject info = Link.info(host);
                String name = info.optString("name", "TV");
                main.post(() -> showPair(host, name));
            }, e -> toastLine(or, "No Holmes Haven TV answered at " + host + "."));
        });
    }

    // ---- 2. pair with the code on the TV --------------------------------------------------------

    private void showPair(String host, String name) {
        finder.stop();
        onHome = false;
        clear();
        header("Pair with " + name);
        steps(new String[]{
                "On the TV, press Up to reach the bar at the top.",
                "Press Left until the Holmes Haven logo lights up, then OK.",
                "Choose \u201CAllow a new phone\u201D. A big code appears.",
                "Type that code here."});
        EditText code = input("123456", InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        code.setLetterSpacing(0.35f);
        code.setGravity(Gravity.CENTER);
        TextView msg = small("");
        Button pair = button("Pair", LEAF, AURORA);
        Button back = flat("Back");
        back.setOnClickListener(v -> showFind());
        pair.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (!c.matches("[0-9]{6}")) {
                toastLine(msg, "The code has 6 numbers.");
                return;
            }
            hideKeyboard(code);
            toastLine(msg, "Pairing\u2026");
            msg.setTextColor(MOON);
            work(() -> {
                Link.Paired p = Link.pair(host, Vault.phoneId(this), phoneName(), c);
                Vault.save(this, host, p);
                tv = Vault.load(this);
                main.post(() -> {
                    if (tv == null) toastLine(msg, "Paired, but this phone couldn't store the key safely.");
                    else showHome();
                });
            }, e -> {
                String why = e.getMessage();
                if (e instanceof Link.Error) {
                    Link.Error le = (Link.Error) e;
                    if ("pairing_closed".equals(le.code)) why = "The TV isn't allowing phones right now. Do the steps above, then try again.";
                    else if ("bad_code".equals(le.code)) {
                        int left = le.body == null ? -1 : le.body.optInt("attemptsLeft", -1);
                        why = "That code didn't match." + (left > 0 ? " " + left + " tries left." : " The TV made a new code; type that one.");
                    } else if ("no_pairing".equals(le.code)) why = "That took too long. Press Pair again.";
                    else if ("too_many_pcs".equals(le.code)) why = "The TV already has 8 devices. Forget one on the TV first.";
                    else if ("rate_limited".equals(le.code)) why = "One moment, then press Pair again.";
                }
                toastLine(msg, why == null ? "Couldn't reach the TV." : why);
            });
        });
    }

    // ---- 3. home: TV status and updates ---------------------------------------------------------

    private void showHome() {
        onHome = true;
        clear();
        header(tv.name);
        remote();
        space(18);
        TextView about = text("About the TV", 18, GOLD, true);
        about.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        statusLine = small("Checking the TV\u2026");
        statusLine.setTextColor(MOON);
        LinearLayout card = card();
        statusRows = column();
        card.addView(statusRows);
        page.addView(card);

        space(14);
        LinearLayout up = card();
        updateLine = text("Holmes Haven " + version(), 16, CREAM, false);
        up.addView(own(updateLine));
        updateButton = button("Update", LEAF, AURORA);
        updateButton.setVisibility(View.GONE);
        up.addView(own(updateButton));
        updateButton.setOnClickListener(v -> installUpdate());
        page.addView(up);

        space(10);
        Button refresh = button("Refresh", AURORA, NEBULA);
        refresh.setOnClickListener(v -> refresh(true));
        space(24);
        TextView next = small("The app list comes next.");
        next.setGravity(Gravity.CENTER);
        Button forget = flat("Forget this TV");
        forget.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Forget " + tv.name + "?")
                .setMessage("You'll need the code on the TV to pair again. (To remove this phone from the TV too, use Phones on the TV.)")
                .setPositiveButton("Forget", (d, w) -> {
                    Vault.forget(this);
                    tv = null;
                    showFind();
                })
                .setNegativeButton("Cancel", null).show());
        refresh(true);
    }

    private void refresh(boolean checkUpdate) {
        if (tv == null || busy) return;
        busy = true;
        final Vault.Tv t = tv;
        work(() -> {
            Link link = new Link(t.host, t.pcId, t.token);
            JSONObject st;
            try {
                st = link.getJson("/v1/status");
            } catch (Link.Error e) {
                throw e;
            } catch (Exception e) {
                // The TV may have a new address: look for it by its id, then try once more.
                String moved = findById(t.tvId);
                if (moved == null || moved.equals(t.host)) throw e;
                Vault.moveTo(this, moved);
                t.host = moved;
                link = new Link(moved, t.pcId, t.token);
                st = link.getJson("/v1/status");
            }
            JSONObject o = null;
            if (checkUpdate) {
                try {
                    o = Updater.check(this, link);
                } catch (Exception ignored) {
                }
            }
            final JSONObject status = st, upd = o;
            main.post(() -> {
                busy = false;
                if (!onHome) return;
                showStatus(status);
                if (checkUpdate) showOffer(upd);
            });
        }, e -> {
            busy = false;
            if (!onHome) return;
            screenOn = null;
            statusLine.setVisibility(View.GONE);
            statusRows.removeAllViews();
            if (e instanceof Link.Error && ((Link.Error) e).status == 401 && "unauthorized".equals(((Link.Error) e).code)) {
                row("TV", "has forgotten this phone", COSMIC);
                Button again = button("Pair again", LEAF, AURORA);
                again.setOnClickListener(v -> {
                    Vault.forget(this);
                    tv = null;
                    showFind();
                });
                statusRows.addView(own(again));
            } else if (e instanceof Link.Error && "clock_skew".equals(((Link.Error) e).code)) {
                row("TV", "its clock and this phone's disagree; check the time on both", GOLD);
            } else {
                row("TV", "can't reach it. Is it on, and is this phone on the home Wi-Fi?", GOLD);
            }
        });
    }

    private void showStatus(JSONObject s) {
        statusLine.setVisibility(View.GONE);
        statusRows.removeAllViews();
        boolean on = s.optBoolean("screenOn", true);
        screenOn = on;
        String mac = s.optString("wakeMac", "");
        if (mac.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) getSharedPreferences("tv", MODE_PRIVATE).edit().putString("mac", mac).apply();
        String rs = s.optString("remote", "");
        if ("needs_allow".equals(rs) || "adb_off".equals(rs)) remoteHint(rs);
        row("Screen", on ? "on" : "asleep", on ? LEAF : GOLD);
        JSONObject fg = s.optJSONObject("foreground");
        String now = fg == null ? "" : fg.optString("label", "");
        if ("OmarchyTV".equals(now) || s.optBoolean("homeVisible", false)) now = "Holmes Haven home";
        if (!now.isEmpty()) row("On screen", now, CREAM);
        row("TV software", s.optString("launcherVersion", "?"), CREAM);
        if (s.has("androidVersion")) row("Android", s.optString("androidVersion"), CREAM);
        if (s.has("uptimeS")) row("On for", duration(s.optLong("uptimeS")), CREAM);
        if (s.has("storageFreeMB")) {
            long free = s.optLong("storageFreeMB"), total = s.optLong("storageTotalMB");
            row("Storage", gb(free) + " free of " + gb(total), free < 1024 ? COSMIC : CREAM);
        }
    }

    private void showOffer(JSONObject o) {
        offer = o;
        if (o == null) {
            updateLine.setText("Holmes Haven " + version() + "  \u00B7  up to date");
            updateButton.setVisibility(View.GONE);
        } else {
            updateLine.setText("A new version is ready: " + o.optString("versionName", "?"));
            updateLine.setTextColor(GOLD);
            updateButton.setText("Update to " + o.optString("versionName", "the new version"));
            updateButton.setVisibility(View.VISIBLE);
        }
    }

    private void installUpdate() {
        if (offer == null || tv == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow updates")
                    .setMessage("Android needs your OK once: turn on \u201CAllow from this source\u201D for Holmes Haven, then come back and press Update.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Later", null).show();
            return;
        }
        updateButton.setEnabled(false);
        updateLine.setText("Downloading from the TV\u2026");
        final JSONObject o = offer;
        final Vault.Tv t = tv;
        work(() -> {
            Updater.install(this, new Link(t.host, t.pcId, t.token), o);
            main.post(() -> {
                updateLine.setText("Android will ask you to confirm the update.");
                updateButton.setEnabled(true);
            });
        }, e -> {
            updateLine.setText("Update failed: " + e.getMessage());
            updateLine.setTextColor(COSMIC);
            updateButton.setEnabled(true);
        });
    }

    /** Look for our TV on the Wi-Fi by its id for a few seconds (blocking, off the UI thread). */
    private String findById(String tvId) {
        if (tvId == null || tvId.isEmpty()) return null;
        final String[] hit = new String[1];
        final Object lock = new Object();
        main.post(() -> finder.start((name, host, id, open) -> {
            if (tvId.equals(id)) synchronized (lock) {
                hit[0] = host;
                lock.notifyAll();
            }
        }));
        synchronized (lock) {
            try {
                if (hit[0] == null) lock.wait(6000);
            } catch (InterruptedException ignored) {
            }
        }
        main.post(finder::stop);
        return hit[0];
    }

    // ---- the remote (M2) -------------------------------------------------------------------------

    private void remote() {
        // power: tap = sleep or wake, hold = the TV's shutdown prompt (like the real power button)
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        remoteLine = new TextView(this);
        remoteLine.setTextColor(GOLD);
        remoteLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        top.addView(remoteLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button power = roundKey("⏻", COSMIC, 64);
        power.setOnClickListener(v -> powerTap());
        power.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            press("power", true);
            return true;
        });
        top.addView(power, new LinearLayout.LayoutParams(dp(64), dp(64)));
        page.addView(top);
        remoteLine.setText("");

        DPad pad = new DPad(this, k -> press(k, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.leftMargin = lp.rightMargin = dp(18);
        page.addView(pad, lp);

        keyRow(new String[]{"↩  Back", "⌂  Home", "☰  Menu"}, new String[]{"back", "home", "menu"},
                new int[]{NEBULA, AURORA, 0xFF475569});
        keyRow(new String[]{"⏪", "⏯", "⏩"}, new String[]{"rewind", "play_pause", "fast_forward"},
                new int[]{0xFF334155, LEAF, 0xFF334155});
        keyRow(new String[]{"Vol −", "Mute", "Vol +"}, new String[]{"volume_down", "mute", "volume_up"},
                new int[]{AURORA, 0xFF475569, AURORA});
        keyRow(new String[]{"CH −", "CH +"}, new String[]{"channel_down", "channel_up"}, new int[]{NEBULA, NEBULA});

        space(10);
        TextView ws = small("Workspaces");
        ws.setTextColor(GOLD);
        int[] fam = {AURORA, COSMIC, LEAF, GOLD, NEBULA};
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            for (int i = 1; i <= 5; i++) {
                final int n = row * 5 + i;
                Button b = roundKey(n == 10 ? "0" : String.valueOf(n), row == 0 ? fam[i - 1] : 0xFF334155, 52);
                b.setOnClickListener(v -> workspace(n));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                r.addView(b, p);
            }
            page.addView(r);
        }

        // number pad for Live TV channels, folded away until needed
        Button more = flat("Numbers ▾");
        LinearLayout nums = column();
        nums.setVisibility(View.GONE);
        String[][] grid = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"0"}};
        for (String[] g : grid) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            for (String k : g) {
                Button b = roundKey(k, 0xFF334155, 56);
                b.setOnClickListener(v -> press(k, false));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                r.addView(b, p);
            }
            nums.addView(r);
        }
        page.addView(nums);
        more.setOnClickListener(v -> {
            boolean show = nums.getVisibility() != View.VISIBLE;
            nums.setVisibility(show ? View.VISIBLE : View.GONE);
            more.setText(show ? "Numbers ▴" : "Numbers ▾");
        });
    }

    private void keyRow(String[] labels, String[] names, int[] colours) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final String k = names[i];
            Button b = roundKey(labels[i], colours[i], 54);
            b.setOnClickListener(v -> press(k, false));
            if (k.startsWith("volume")) {   // hold volume = keep going
                b.setOnLongClickListener(v -> {
                    repeatWhileHeld(v, k);
                    return true;
                });
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f);
            p.setMargins(dp(4), dp(5), dp(4), dp(5));
            r.addView(b, p);
        }
        page.addView(r);
    }

    private void repeatWhileHeld(View v, String k) {
        Runnable rep = new Runnable() {
            @Override public void run() {
                if (!v.isPressed()) return;
                press(k, false);
                main.postDelayed(this, 180);
            }
        };
        main.post(rep);
    }

    private Button roundKey(String label, int colour, int heightDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() <= 2 ? 22 : 16);
        b.setMinHeight(dp(heightDp));
        b.setMinimumHeight(dp(heightDp));
        b.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(dp(heightDp / 2f));
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setOnTouchListener((v, e) -> {   // a small buzz on every press, so you feel it went
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            return false;
        });
        return b;
    }

    /** One press on the TV. Shows the reason if the remote isn't ready yet (first time: the TV's Allow prompt). */
    private void press(String key, boolean hold) {
        final Vault.Tv t = tv;
        if (t == null) return;
        keys.execute(() -> {
            try {
                JSONObject b = new JSONObject().put("key", key);
                if (hold) b.put("hold", true);
                new Link(t.host, t.pcId, t.token).postJson("/v1/haven/key", b);
                main.post(() -> remoteHint(""));
            } catch (Link.Error e) {
                main.post(() -> remoteHint(e.code));
            } catch (Exception e) {
                main.post(() -> remoteHint("unreachable"));
            }
        });
    }

    private void workspace(int n) {
        final Vault.Tv t = tv;
        if (t == null) return;
        keys.execute(() -> {
            try {
                new Link(t.host, t.pcId, t.token).postJson("/v1/haven/workspace", new JSONObject().put("n", n));
                main.post(() -> remoteHint(""));
            } catch (Link.Error e) {
                main.post(() -> remoteHint(e.code));
            } catch (Exception e) {
                main.post(() -> remoteHint("unreachable"));
            }
        });
    }

    /** The phone app opened: ask the TV to get its remote ready, so the first press is quick. */
    private void warm() {
        final Vault.Tv t = tv;
        if (t == null) return;
        keys.execute(() -> {
            try {
                JSONObject o = new Link(t.host, t.pcId, t.token).postJson("/v1/haven/warm", null);
                String rs = o.optString("remote", "");
                if ("needs_allow".equals(rs) || "adb_off".equals(rs)) main.post(() -> remoteHint(rs));
            } catch (Exception ignored) {
            }
        });
    }

    private void remoteHint(String code) {
        if (remoteLine == null) return;
        String s;
        switch (code) {
            case "": s = ""; break;
            case "needs_allow": s = "On the TV: tick “Always allow”, then Allow (only once)"; break;
            case "remote_starting": s = "Getting the remote ready… press again in a moment"; break;
            case "adb_off": s = "The TV's debugging is off, so the remote can't press keys"; break;
            case "unreachable": s = "Can't reach the TV. If it's off, tap ⏻ to wake it"; break;
            default: s = "The TV said: " + code;
        }
        remoteLine.setText(s);
    }

    /** Power tap: awake -> sleep, asleep -> wake, no answer -> Wake-on-LAN (works from a full shutdown too). */
    private void powerTap() {
        final Vault.Tv t = tv;
        if (t == null) return;
        keys.execute(() -> {
            Link link = new Link(t.host, t.pcId, t.token);
            try {
                JSONObject st = link.getJson("/v1/status");
                boolean on = st.optBoolean("screenOn", true);
                link.postJson("/v1/haven/key", new JSONObject().put("key", on ? "sleep" : "wakeup"));
                main.post(() -> {
                    screenOn = !on;
                    remoteHint("");
                });
            } catch (Link.Error e) {
                main.post(() -> remoteHint(e.code));
            } catch (Exception e) {
                String mac = getSharedPreferences("tv", MODE_PRIVATE).getString("mac", "");
                boolean sent = wakeOnLan(mac, t.host);
                main.post(() -> {
                    if (remoteLine == null) return;
                    remoteLine.setText(sent ? "Waking the TV… from a full shutdown it takes about 40 seconds"
                            : "Can't reach the TV, and it hasn't told this phone its wake address yet");
                    if (sent) main.postDelayed(() -> refresh(false), 45_000);
                });
            }
        });
    }

    /** Magic packet (6 x FF, then the MAC 16 times) to the LAN broadcast, a few times. Blocking. */
    private static boolean wakeOnLan(String mac, String host) {
        if (mac == null || !mac.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
        byte[] m = new byte[6];
        String[] parts = mac.split(":");
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, pkt, 6 + i * 6, 6);
        String subnet = host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") ? host.substring(0, host.lastIndexOf('.')) + ".255" : null;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            String[] targets = subnet == null ? new String[]{"255.255.255.255"} : new String[]{subnet, "255.255.255.255"};
            for (int round = 0; round < 3; round++) {
                for (String to : targets) {
                    for (int port : new int[]{9, 7}) {
                        s.send(new java.net.DatagramPacket(pkt, pkt.length, java.net.InetAddress.getByName(to), port));
                    }
                }
                Thread.sleep(300);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- small helpers -------------------------------------------------------------------------

    interface Job {
        void run() throws Exception;
    }

    interface Fail {
        void on(Exception e);
    }

    private void work(Job j, Fail f) {
        bg.execute(() -> {
            try {
                j.run();
            } catch (Exception e) {
                main.post(() -> f.on(e));
            }
        });
    }

    private String phoneName() {
        String n = null;
        try {
            n = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = android.os.Build.MODEL;
        n = n.replaceAll("[^\\x20-\\x7E]", "").trim();
        return n.length() > 40 ? n.substring(0, 40) : n.isEmpty() ? "Phone" : n;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String duration(long s) {
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + (d == 1 ? " day " : " days ") + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    private static String gb(long mb) {
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024f);
    }

    private void clear() {
        page.removeAllViews();
    }

    private void header(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView em = new ImageView(this);
        em.setImageResource(R.drawable.emblem);
        h.addView(em, new LinearLayout.LayoutParams(dp(110), dp(110)));
        TextView brand = text("Holmes Haven", 30, GOLD, true);
        brand.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        brand.setGravity(Gravity.CENTER);
        brand.setShadowLayer(dp(6), 0, 0, 0x998A2BE2);
        h.addView(own(brand));
        page.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        space(14);
        TextView t = text(title, 22, CREAM, true);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        space(10);
    }

    private void steps(String[] s) {
        LinearLayout c = card();
        int[] colours = {AURORA, COSMIC, LEAF, GOLD};
        for (int i = 0; i < s.length; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            TextView n = new TextView(this);
            n.setText(String.valueOf(i + 1));
            n.setTextColor(Color.WHITE);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            n.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(colours[i % colours.length]);
            n.setBackground(dot);
            r.addView(n, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView t = new TextView(this);
            t.setText(s[i]);
            t.setTextColor(CREAM);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setPadding(dp(12), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            c.addView(r);
        }
        page.addView(c);
        space(14);
    }

    private void row(String k, String v, int colour) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextColor(MUTED);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        r.addView(a, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView b = new TextView(this);
        b.setText(v);
        b.setTextColor(colour);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        r.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRows.addView(r);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x66FFC94D);
        c.setBackground(g);
        return c;
    }

    /** Adds to the page and returns it. */
    private TextView text(String s, float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        page.addView(t);
        return t;
    }

    private TextView body(String s) {
        return text(s, 16, MOON, false);
    }

    private TextView small(String s) {
        return text(s, 14, MUTED, false);
    }

    private void toastLine(TextView t, String s) {
        t.setText(s);
        t.setTextColor(GOLD);
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x66E2E8F0);
        e.setTextColor(CREAM);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33000000);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(2), 0x998A2BE2);
        e.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        page.addView(e, lp);
        return e;
    }

    /** A big, bold, colourful button (easy to see), added to the page. */
    private Button button(String s, int from, int to) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setMinHeight(dp(56));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        page.addView(b);
        return b;
    }

    private Button flat(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(MUTED);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setBackgroundColor(Color.TRANSPARENT);
        page.addView(b);
        return b;
    }

    /** Take a view the helpers already put on the page, to place it somewhere else. */
    private static <T extends View> T own(T v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    private void space(int dpH) {
        View v = new View(this);
        page.addView(v, new LinearLayout.LayoutParams(1, dp(dpH)));
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
