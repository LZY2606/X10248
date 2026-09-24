package pol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Derivation fingerprint. Rotation convention and Stokes order are explicit
 * inputs, so a change of either produces a different fingerprint and therefore
 * a different derived identity.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(Map<String, ?> canonicalInputs) {
        return sha256(canonical(canonicalInputs));
    }

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

    /** Deterministic canonical rendering (sorted keys, no whitespace). */
    @SuppressWarnings("unchecked")
    public static String canonical(Object o) {
        StringBuilder sb = new StringBuilder();
        render(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void render(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(k).append("\":");
                render(m.get(k), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object v : c) {
                if (!first) sb.append(',');
                first = false;
                render(v, sb);
            }
            sb.append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            sb.append('"').append(String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
    }
}
