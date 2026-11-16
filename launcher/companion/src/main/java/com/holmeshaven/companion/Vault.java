package com.holmeshaven.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The paired TV: its address, ids and the pairing token. The token is sealed with an AES key that lives in the
 * Android Keystore and never leaves it, so the prefs file alone is useless (and backups are off anyway).
 */
final class Vault {
    private static final String KEY = "holmes-haven-token", PREFS = "tv";

    static final class Tv {
        String host, tvId, name, pcId;
        byte[] token;
    }

    private Vault() {
    }

    /** This phone's id on the TV (8-64 letters/digits, made once). */
    static String phoneId(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("pcId", null);
        if (id == null) {
            id = "hh" + LinkCrypto.hex(LinkCrypto.randomBytes(12));
            p.edit().putString("pcId", id).apply();
        }
        return id;
    }

    static Tv load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String sealed = p.getString("token", null), iv = p.getString("iv", null);
        if (sealed == null || iv == null) return null;
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, LinkCrypto.unb64u(iv)));
            Tv t = new Tv();
            t.token = ci.doFinal(LinkCrypto.unb64u(sealed));
            t.host = p.getString("host", "");
            t.tvId = p.getString("tvId", "");
            t.name = p.getString("name", "TV");
            t.pcId = phoneId(c);
            return t;
        } catch (Exception e) {
            return null;   // key gone (phone reset its lock screen, app data restored elsewhere): pair again
        }
    }

    static void save(Context c, String host, Link.Paired paired) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] sealed = ci.doFinal(paired.token);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", host).putString("tvId", paired.tvId).putString("name", paired.tvName)
                .putString("iv", LinkCrypto.b64u(ci.getIV())).putString("token", LinkCrypto.b64u(sealed)).apply();
    }

    /** The TV moved to another address (found again by its id). */
    static void moveTo(Context c, String host) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host", host).apply();
    }

    static void forget(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("host").remove("tvId").remove("name").remove("iv").remove("token").apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
}
