package polopt.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable identity fingerprint of the conventions and parameter versions used
 * for a derivation. Rotation convention and Stokes component order MUST enter
 * the fingerprint, so a change in either produces a different identity instead
 * of silently overwriting prior results.
 */
public record Fingerprint(String algorithm, String hash, Map<String, String> factors) {

    public static Fingerprint of(Map<String, String> factors) {
        Map<String, String> ordered = new LinkedHashMap<>(factors);
        List<String> required = List.of(
                "stokesOrder", "rotationConvention", "estimationRule",
                "estimationRuleVersion", "condThreshold", "covarianceVersion",
                "darkVersion", "referenceVersion", "instrumentVersion",
                "wavelengthVersion");
        for (String key : required) {
            ordered.putIfAbsent(key, "UNSPECIFIED");
        }
        StringBuilder sb = new StringBuilder("mueller-fingerprint|v1\n");
        new ArrayList<>(ordered.keySet()).stream().sorted().forEach(k ->
                sb.append(k).append('=').append(ordered.get(k)).append('\n'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return new Fingerprint("SHA-256", hex.toString(), ordered);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String shortHash() {
        return hash.substring(0, 12);
    }
}
