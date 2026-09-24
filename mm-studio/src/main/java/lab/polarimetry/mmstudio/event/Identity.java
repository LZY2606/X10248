package lab.polarimetry.mmstudio.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * Stable content-based identities and rule fingerprints.
 * Identities are pure functions of content + scope, so export/delete/reimport
 * preserves the same id, ordering and source relations.
 */
public final class Identity {
    private Identity() {}

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String shortHash(String s) {
        return sha256(s).substring(0, 16);
    }

    /** Deterministic identity for a versioned source layer record. */
    public static String sourceId(String kind, String materialId, String version, String rawContent) {
        return "src_" + shortHash(kind + "|" + materialId + "|" + version + "|" + sha256(rawContent));
    }

    /** Deterministic event id: branch + parent + type + canonical payload. */
    public static String eventId(String branchId, String parentEventId, String type, Object payload) {
        TreeMap<String, Object> canon = new TreeMap<>(Json.MAPPER.convertValue(payload, TreeMap.class));
        return "evt_" + shortHash(branchId + "|" + nullSafe(parentEventId) + "|"
                + type + "|" + Json.write(canon));
    }

    public static String artifactId(String materialId, String branchId, String kind,
                                    String candidateId, String basisFingerprint, String depFingerprint) {
        return "art_" + shortHash(materialId + "|" + branchId + "|" + kind + "|"
                + nullSafe(candidateId) + "|" + basisFingerprint + "|" + depFingerprint);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
