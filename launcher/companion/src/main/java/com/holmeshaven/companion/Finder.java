package com.holmeshaven.companion;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.util.ArrayDeque;

/**
 * Finds TVs on the home Wi-Fi: the launcher advertises _omarchytv._tcp over mDNS (TXT id, name, pair).
 * Calls back on the UI thread with each TV it resolves. Stop it when the screen goes away.
 */
final class Finder {
    interface Found {
        void tv(String name, String host, String tvId, boolean pairingOpen);
    }

    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener listener;
    private Found found;

    Finder(Context c) {
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock = w == null ? null : w.createMulticastLock("holmes-haven-find");
    }

    void start(Found f) {
        stop();
        found = f;
        if (nsd == null) return;
        if (lock != null) lock.acquire();
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                main.post(() -> {
                    queue.add(s);
                    next();
                });
            }
        };
        try {
            nsd.discoverServices("_omarchytv._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception ignored) {
            listener = null;
        }
    }

    /** NsdManager resolves one service at a time. */
    private void next() {
        if (resolving || queue.isEmpty() || listener == null) return;
        resolving = true;
        @SuppressWarnings("deprecation") NsdManager.ResolveListener rl = new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo s, int e) {
                main.post(() -> { resolving = false; next(); });
            }
            @Override public void onServiceResolved(NsdServiceInfo s) {
                main.post(() -> {
                    resolving = false;
                    if (found != null && s.getHost() instanceof Inet4Address) {
                        String id = txt(s, "id"), name = txt(s, "name");
                        found.tv(name.isEmpty() ? s.getServiceName() : name, s.getHost().getHostAddress(), id, "1".equals(txt(s, "pair")));
                    }
                    next();
                });
            }
        };
        try {
            nsd.resolveService(queue.poll(), rl);
        } catch (Exception e) {
            resolving = false;
        }
    }

    private static String txt(NsdServiceInfo s, String k) {
        byte[] v = s.getAttributes() == null ? null : s.getAttributes().get(k);
        return v == null ? "" : new String(v, java.nio.charset.StandardCharsets.UTF_8);
    }

    void stop() {
        if (listener != null && nsd != null) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        listener = null;
        queue.clear();
        resolving = false;
        found = null;
        if (lock != null && lock.isHeld()) lock.release();
    }
}
