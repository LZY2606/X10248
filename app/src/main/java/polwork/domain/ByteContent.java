package polwork.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Immutable raw imported bytes with content hash. Never rewritten in place. */
public final class ByteContent {
    public final byte[] bytes;
    public final String mediaType;
    public final String sha256;

    public ByteContent(byte[] bytes, String mediaType) {
        this.bytes = bytes;
        this.mediaType = mediaType;
        this.sha256 = sha256(bytes);
    }

    public static ByteContent utf8(String text) {
        return new ByteContent(text.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    public String utf8String() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
