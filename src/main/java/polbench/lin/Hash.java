package polbench.lin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hash {
    private Hash() {}

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortId(String kind, String canonical) {
        return kind + "_" + sha256(canonical).substring(0, 16);
    }
}
