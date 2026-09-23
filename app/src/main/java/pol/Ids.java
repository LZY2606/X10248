package pol;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/** Stable, content-addressed identifiers and fingerprints. */
public final class Ids {
    private static final SecureRandom RNG = new SecureRandom();

    private Ids() {}

    public static String hash(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update(p.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0x1f);
            }
            byte[] d = md.digest();
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String displayId(String prefix, String stableHash, int ordinal) {
        return prefix + "-" + stableHash.substring(0, 10) + "-" + ordinal;
    }

    public static String eventId(String branchId, long ordinal) {
        return branchId + ".ev" + ordinal;
    }

    public static String randomToken() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String now() {
        return Instant.now().toString();
    }
}
