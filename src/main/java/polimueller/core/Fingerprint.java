package polimueller.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content fingerprint of a derivation. Stokes order and rotation convention
 * always participate, together with the versions of every source and the
 * rule-set version, so a convention change silently reusing old results is
 * impossible.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(Conventions.ConventionSet conventions,
                            Map<String, Integer> sourceVersions,
                            String ruleVersion) {
        TreeMap<String, Integer> sorted = new TreeMap<>(sourceVersions);
        StringBuilder sb = new StringBuilder();
        sb.append("conv=").append(conventions.key()).append(';');
        sb.append("rules=").append(ruleVersion).append(';');
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sha256(sb.toString());
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
