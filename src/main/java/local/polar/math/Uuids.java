package local.polar.math;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic name-based UUIDs (RFC 4122 version 5, SHA-1 based).
 *
 * Identity rule for the room: an object's id is derived from its stable
 * namespace plus the exact source bytes / canonical content it represents.
 * Re-importing identical content therefore restores the identical id,
 * while any real change produces a different id.
 */
public final class Uuids {

    public static final UUID NAMESPACE_POLAR_ROOM =
            UUID.fromString("a3f1c9d2-6b4e-5a78-9c21-0e34d8b72f10");

    private Uuids() {
    }

    public static UUID v5(UUID namespace, String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(toBytes(namespace));
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (digest[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (digest[i] & 0xff);
            }
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L;
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public static String id(UUID namespace, String name) {
        return v5(namespace, name).toString();
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> 8 * (7 - i));
        }
        for (int i = 8; i < 16; i++) {
            out[i] = (byte) (lsb >>> 8 * (7 - i));
        }
        return out;
    }
}
