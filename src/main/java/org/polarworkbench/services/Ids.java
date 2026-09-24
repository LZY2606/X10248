package org.polarworkbench.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.StringJoiner;

public final class Ids {
    private Ids() {
    }

    public static String stable(String... parts) {
        StringJoiner joiner = new StringJoiner("|");
        for (String part : parts) {
            joiner.add(part == null ? "" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joiner.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.substring(0, 24);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create stable identity", ex);
        }
    }
}
