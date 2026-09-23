package org.polarreview.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Deterministic stable identities. Every displayed value, artifact and event
 * gets a UID derived from content or assigned at creation; export/delete/import
 * preserves them verbatim.
 */
public final class Ids {
    private Ids() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String uid(String prefix, String content) {
        return prefix + "_" + sha256(content).substring(0, 16);
    }

    public static String newEid() {
        return "ev_" + Long.toHexString(System.nanoTime()) + "_"
                + Long.toHexString(Instant.now().getNano());
    }
}
