package pms.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Stable identities: namespace UUIDv5 (SHA-1 based, RFC 4122 style).
 * Re-export / delete-DB-reimport reproduces the same id for the same logical object.
 */
public final class Id {
    public static final UUID NAMESPACE = UUID.fromString("6f3a1c2e-9b47-5d8a-a2f1-7c6e04b9d358");

    private Id() {}

    public static UUID v5(UUID namespace, String name) {
        byte[] ns = uuidBytes(namespace);
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[ns.length + nb.length];
        System.arraycopy(ns, 0, data, 0, ns.length);
        System.arraycopy(nb, 0, data, ns.length, nb.length);
        byte[] hash = sha1(data);
        hash[6] &= 0x0f;
        hash[6] |= 0x50;
        hash[8] &= 0x3f;
        hash[8] |= (byte) 0x80;
        return fromBytes(hash);
    }

    public static UUID v5(String name) { return v5(NAMESPACE, name); }

    /** Stable id of a single displayed value (element, condition number, ...). */
    public static String valueId(String prefix, String... pathParts) {
        return prefix + ":" + v5(prefix + "|" + String.join("|", pathParts));
    }

    public static String sha256Hex(String content) {
        byte[] hash = sha256(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String shortSha256(String content) {
        return sha256Hex(content).substring(0, 16);
    }

    private static byte[] sha1(byte[] data) {
        try { return MessageDigest.getInstance("SHA-1").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] uuidBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) out[i] = (byte) (msb >>> 8 * (7 - i));
        for (int i = 8; i < 16; i++) out[i] = (byte) (lsb >>> 8 * (7 - i));
        return out;
    }

    private static UUID fromBytes(byte[] b) {
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xff);
        return new UUID(msb, lsb);
    }
}
