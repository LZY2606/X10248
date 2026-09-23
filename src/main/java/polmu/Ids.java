package polmu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Ids {
    private Ids() {}

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    public static String sourceId(String kind, int version, byte[] raw) {
        return "src_" + sha256(kind + ":" + version + ":").substring(0, 8) + sha256(raw).substring(0, 24);
    }

    public static String eventId(String branchId, int seq, String payloadJson) {
        return "evt_" + sha256(branchId + ":" + seq + ":" + payloadJson).substring(0, 24);
    }

    public static String derivedId(String fingerprint, String kind, String subject) {
        return "drv_" + sha256(fingerprint + "|" + kind + "|" + subject).substring(0, 24);
    }

    public static String branchId(String name, String parentEventId, long nonce) {
        return "br_" + sha256(name + "|" + parentEventId + "|" + nonce).substring(0, 16);
    }

    public static String shortId(String id) {
        return id == null ? "" : (id.length() <= 12 ? id : id.substring(0, 12));
    }
}
