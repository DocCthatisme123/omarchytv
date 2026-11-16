package com.holmeshaven.companion;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The phone side of Omarchy Link v1 (Marco's PROTOCOL.md): SPAKE2 pairing with the code on the TV, then every
 * request signed with HMAC-SHA256 and every answer's X-OTV-Sig checked, so nobody on the Wi-Fi can drive the TV
 * or fake its answers. Blocking calls: run them off the UI thread.
 */
final class Link {
    static final int PORT = 47800;

    /**
     * The home Wi-Fi, once Android has it. Samsung phones move apps onto mobile data when they think the Wi-Fi is
     * weak ("Switch to mobile data"), and the TV can't be reached that way: so every call goes over Wi-Fi itself.
     */
    private static volatile android.net.Network wifi;

    static void useWifi(android.content.Context c) {
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return;
        android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build();
        try {
            cm.registerNetworkCallback(req, new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) { wifi = n; }
                @Override public void onLost(android.net.Network n) { if (n.equals(wifi)) wifi = null; }
            });
        } catch (Exception ignored) {
        }
    }

    /** Send a UDP socket (Wake-on-LAN) over the Wi-Fi too. */
    static void bind(java.net.DatagramSocket s) {
        android.net.Network n = wifi;
        if (n == null) return;
        try {
            n.bindSocket(s);
        } catch (Exception ignored) {
        }
    }

    /** An answer that isn't 2xx, with the TV's error code ("pairing_closed", "bad_code", ...). */
    static final class Error extends IOException {
        final int status;
        final String code;
        final JSONObject body;

        Error(int status, String code, String msg, JSONObject body) {
            super(msg == null || msg.isEmpty() ? code : msg);
            this.status = status;
            this.code = code;
            this.body = body;
        }
    }

    static final class Reply {
        int status;
        byte[] body;
        String header(String name) {
            return headers.optString(name.toLowerCase(java.util.Locale.ROOT), "");
        }
        final JSONObject headers = new JSONObject();

        JSONObject json() throws IOException {
            try {
                return new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IOException("bad answer from the TV");
            }
        }
    }

    final String host;
    final String pcId;
    final byte[] token;

    Link(String host, String pcId, byte[] token) {
        this.host = host;
        this.pcId = pcId;
        this.token = token;
    }

    // ---- unauthenticated ---------------------------------------------------------------------

    static JSONObject info(String host) throws IOException {
        Reply r = http(host, "GET", "/v1/info", null, null, 4000);
        return ok(r).json();
    }

    /** Result of a successful pairing. */
    static final class Paired {
        String tvId, tvName;
        byte[] token;
    }

    /** SPAKE2 with the 6-digit code shown on the TV. Throws Error("bad_code" / "pairing_closed" / ...). */
    static Paired pair(String host, String pcId, String phoneName, String code) throws IOException {
        LinkCrypto.PcPairing pc = new LinkCrypto.PcPairing(code, pcId, null);
        JSONObject start = new JSONObject();
        try {
            start.put("protocol", 1).put("pcId", pcId).put("pcName", phoneName).put("t", LinkCrypto.b64u(pc.message()));
        } catch (Exception ignored) {
        }
        JSONObject s = ok(http(host, "POST", "/v1/pair/start", start.toString().getBytes(StandardCharsets.UTF_8), null, 8000)).json();
        String tvId = s.optString("tvId", "");
        LinkCrypto.Keys keys;
        try {
            keys = pc.finish(tvId, LinkCrypto.unb64u(s.optString("s", "")));
        } catch (IllegalArgumentException e) {
            throw new IOException("the TV's pairing answer was not valid");
        }
        JSONObject confirm = new JSONObject();
        try {
            confirm.put("pairingId", s.optString("pairingId")).put("confirm", LinkCrypto.b64u(keys.confirmPc));
        } catch (Exception ignored) {
        }
        JSONObject c = ok(http(host, "POST", "/v1/pair/confirm", confirm.toString().getBytes(StandardCharsets.UTF_8), null, 8000)).json();
        byte[] tvConfirm;
        try {
            tvConfirm = LinkCrypto.unb64u(c.optString("confirm", ""));
        } catch (IllegalArgumentException e) {
            tvConfirm = new byte[0];
        }
        // The TV proves it knew the same code: without this a fake "TV" on the Wi-Fi could pretend to pair.
        if (!LinkCrypto.equal(tvConfirm, keys.confirmTv)) throw new IOException("the TV's key didn't match; not paired");
        Paired p = new Paired();
        p.tvId = tvId;
        p.tvName = s.optString("tvName", "TV");
        p.token = keys.token;
        return p;
    }

    // ---- signed --------------------------------------------------------------------------------

    JSONObject getJson(String path) throws IOException {
        return getJson(path, 6000);
    }

    JSONObject getJson(String path, int timeoutMs) throws IOException {
        return call("GET", path, null, timeoutMs).json();
    }

    JSONObject postJson(String path, JSONObject body) throws IOException {
        return call("POST", path, body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8), 6000).json();
    }

    byte[] getBytes(String path, int timeoutMs) throws IOException {
        return call("GET", path, null, timeoutMs).body;
    }

    private Reply call(String method, String path, byte[] body, int timeoutMs) throws IOException {
        byte[] b = body == null ? new byte[0] : body;
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        String sig = LinkCrypto.signRequest(token, method, path, ts, nonce, b);
        String auth = "OTV1 pc=" + pcId + ",ts=" + ts + ",nonce=" + nonce + ",sig=" + sig;
        Reply r = http(host, method, path, body, auth, timeoutMs);
        // Only trust answers signed with our shared key.
        String got = r.header("X-OTV-Sig");
        String want = LinkCrypto.signResponse(token, nonce, r.status, r.body);
        if (!LinkCrypto.equal(LinkCrypto.utf8(got), LinkCrypto.utf8(want))) {
            if (r.status == 401) throw error(r);   // unknown phone (forgotten on the TV): unsigned 401
            throw new IOException("the answer wasn't signed by our TV");
        }
        return ok(r);
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private static Reply ok(Reply r) throws IOException {
        if (r.status >= 200 && r.status < 300) return r;
        throw error(r);
    }

    private static Error error(Reply r) {
        JSONObject j = null;
        try {
            j = r.json();
        } catch (IOException ignored) {
        }
        String code = j == null ? "http_" + r.status : j.optString("error", "http_" + r.status);
        String msg = j == null ? "" : j.optString("message", "");
        return new Error(r.status, code, msg, j);
    }

    private static Reply http(String host, String method, String path, byte[] body, String auth, int timeoutMs) throws IOException {
        // A phone whose Wi-Fi has been dozing often loses the first connection attempt: nothing has been sent yet
        // then, so trying again is safe (never a double key press).
        for (int attempt = 0; ; attempt++) {
            try {
                return http1(host, method, path, body, auth, timeoutMs);
            } catch (NotConnected e) {
                if (attempt >= 2) throw e.cause;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException i) {
                    throw e.cause;
                }
            }
        }
    }

    private static final class NotConnected extends Exception {
        final IOException cause;

        NotConnected(IOException cause) {
            this.cause = cause;
        }
    }

    private static Reply http1(String host, String method, String path, byte[] body, String auth, int timeoutMs) throws IOException, NotConnected {
        URL u = new URL("http", host, PORT, path);
        android.net.Network n = wifi;
        HttpURLConnection c = (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection());
        try {
            c.setConnectTimeout(Math.min(timeoutMs, 3000));
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            try {
                c.connect();
            } catch (IOException e) {
                throw new NotConnected(e);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body);
                }
            }
            Reply r = new Reply();
            r.status = c.getResponseCode();
            for (String k : c.getHeaderFields().keySet()) {
                if (k == null) continue;
                try {
                    r.headers.put(k.toLowerCase(java.util.Locale.ROOT), c.getHeaderField(k));
                } catch (Exception ignored) {
                }
            }
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? new byte[0] : readAll(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
