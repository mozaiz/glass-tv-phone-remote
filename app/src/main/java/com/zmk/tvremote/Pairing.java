package com.zmk.tvremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may use the remote. A phone asks for a code, the TV shows 4 digits, and the phone that types them
 * gets a long random token kept in a cookie. A code lasts two minutes and five wrong guesses throw it
 * away. A paired phone can also make Shortcuts keys: the same kind of token, sent as
 * {@code Authorization: Bearer <key>} by Siri Shortcuts or a script, and listed apart from phones.
 */
final class Pairing {
    private static final long PIN_MS = 120_000;
    private static final int MAX_TRIES = 5;
    private static final String PREFS = "pairing", TOKENS = "tokens", KEYS = "keys";

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private String pin;
    private long pinUntil;
    private int tries;

    Pairing(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The code the TV should show now, or null when none is waiting. */
    synchronized String currentPin() {
        if (pin != null && System.currentTimeMillis() > pinUntil) pin = null;
        return pin;
    }

    /** Makes a new code unless one is still showing. */
    synchronized String newPin() {
        if (currentPin() == null) {
            pin = String.format(Locale.US, "%04d", random.nextInt(10_000));
            tries = 0;
        }
        pinUntil = System.currentTimeMillis() + PIN_MS;
        return pin;
    }

    /** The phone's token when {@code guess} is right, else null. */
    synchronized String confirm(String guess) {
        if (currentPin() == null || guess == null) return null;
        if (!pin.equals(guess.trim())) {
            if (++tries >= MAX_TRIES) pin = null;
            return null;
        }
        pin = null;
        return add(TOKENS);
    }

    /** A new key for Siri Shortcuts or scripts. */
    synchronized String newKey() {
        return add(KEYS);
    }

    private String add(String set) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) token.append(String.format(Locale.US, "%02x", x & 0xff));
        Set<String> all = new HashSet<>(prefs.getStringSet(set, new HashSet<>()));
        all.add(token.toString());
        prefs.edit().putStringSet(set, all).apply();
        return token.toString();
    }

    boolean allowed(String token) {
        if (token == null || token.isEmpty()) return false;
        return prefs.getStringSet(TOKENS, new HashSet<>()).contains(token)
                || prefs.getStringSet(KEYS, new HashSet<>()).contains(token);
    }

    int phones() {
        return prefs.getStringSet(TOKENS, new HashSet<>()).size();
    }

    int keys() {
        return prefs.getStringSet(KEYS, new HashSet<>()).size();
    }

    /** Forgets one phone or key, the one asking. */
    synchronized void forget(String token) {
        for (String set : new String[]{TOKENS, KEYS}) {
            Set<String> all = new HashSet<>(prefs.getStringSet(set, new HashSet<>()));
            if (all.remove(token)) prefs.edit().putStringSet(set, all).apply();
        }
    }

    /** Forgets every phone and every Shortcuts key. */
    void forgetAll() {
        prefs.edit().remove(TOKENS).remove(KEYS).apply();
    }
}
