package polmu.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/** Stable fingerprint over conventions, parameter versions and source identities. */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(TreeMap<String, String> parts) {
        StringBuilder sb = new StringBuilder();
        for (var e : parts.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.substring(0, 24);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
