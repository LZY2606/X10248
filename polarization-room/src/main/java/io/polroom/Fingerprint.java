package io.polroom;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable fingerprints bind a derived result to:
 *  - the exact byte hashes of every source version it consumed,
 *  - the interpretation conventions (Stokes order, rotation sign),
 *  - the rule-set version used to derive it.
 * Any change anywhere in those inputs changes the fingerprint, which is how
 * "rotation convention and Stokes order enter the fingerprint" is enforced.
 */
public final class Fingerprint {

    public static final String RULE_VERSION = "mueller-rules-1.0.0";

    private Fingerprint() {}

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonicalSha256(Map<String, Object> payload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String canon = mapper.writeValueAsString(new TreeMap<>(payload));
            return sha256(canon.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String compute(String stokesOrder, int rotationSign, Map<String, String> sourceHashes) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("rules", RULE_VERSION);
        payload.put("stokesOrder", stokesOrder);
        payload.put("rotationSign", rotationSign);
        payload.put("sources", new TreeMap<>(sourceHashes));
        return canonicalSha256(payload);
    }
}
