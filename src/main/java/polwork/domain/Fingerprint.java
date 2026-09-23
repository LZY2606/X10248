package polwork.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable identity hashes. The fingerprint binds (a) the exact source bytes
 * of every referenced raw version, (b) the ruleset version and thresholds
 * and (c) the rotation/Stokes convention, so reinterpretation under a new
 * convention or rule version never silently reuses an old result.
 */
public final class Fingerprint {

    public static final String RULESET_VERSION = "polwork-ruleset-1.0.0";

    private static final ObjectMapper ORDERLY = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Fingerprint() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Utf8(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String contentFingerprint(Map<String, ?> facts, Convention convention) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("ruleset", RULESET_VERSION);
            canonical.put("convention", convention.fingerprintToken());
            canonical.put("facts", facts);
            byte[] json = ORDERLY.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(canonical);
            return sha256Hex(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
