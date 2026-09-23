package polar.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeSet;

/**
 * Content fingerprint of a derived object. The rotation convention and the
 * Stokes order are part of the hash input, so a convention flip always
 * produces a different identity even when numbers coincide.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(String stokesOrder, String rotationConvention,
                            int paramsVersion, List<String> deps) {
        TreeSet<String> sorted = new TreeSet<>(deps);
        StringBuilder sb = new StringBuilder();
        sb.append("stokesOrder=").append(stokesOrder)
          .append("|rotationConvention=").append(rotationConvention)
          .append("|paramsVersion=").append(paramsVersion);
        for (String d : sorted) sb.append('|').append(d);
        return "fp-" + sha256(sb.toString()).substring(0, 24);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
