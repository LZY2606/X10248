package com.polws.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable identity helpers: SHA-256 over canonical JSON, prefixed with a kind tag. */
public final class Hashing {
    private static final ObjectMapper CANON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Hashing() {
    }

    public static String sha256Hex(String utf8) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(utf8.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String stableId(String kind, Object payload) {
        try {
            String canon = CANON.writeValueAsString(payload);
            String full = sha256Hex(canon);
            return kind + "_" + full.substring(0, 16);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot canonicalize payload for " + kind, e);
        }
    }

    public static String shortFingerprint(Object payload) {
        try {
            return sha256Hex(CANON.writeValueAsString(payload)).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
