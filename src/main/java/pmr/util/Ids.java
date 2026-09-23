package pmr.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content hashing that produces the stable identifiers shown in the review UI. */
public final class Ids {
    private Ids() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String shortHash(String input) {
        return sha256Hex(input).substring(0, 12);
    }

    public static String hashId(String prefix, String canonicalPayload) {
        return prefix + "_" + shortHash(prefix + "\n" + canonicalPayload);
    }
}
