package polarmueller.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content fingerprint for derivations. The rotation convention and the
 * Stokes ordering are always part of the fingerprint, together with the
 * exact source identities (id@version#hash) and rule parameters.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public record SourceRef(String id, int version, String contentHash) {
        public String token() { return id + "@" + version + "#" + contentHash; }
    }

    public static String compute(String ruleVersion,
                                 String stokesOrder,
                                 String rotationConvention,
                                 List<SourceRef> inputs,
                                 Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule=").append(ruleVersion).append('\n');
        sb.append("stokes_order=").append(stokesOrder).append('\n');
        sb.append("rotation_convention=").append(rotationConvention).append('\n');
        inputs.stream().map(SourceRef::token).sorted().forEach(t -> sb.append("in=").append(t).append('\n'));
        new TreeMap<>(params).forEach((k, v) -> sb.append("param.").append(k).append('=').append(v).append('\n'));
        return sha256(sb.toString());
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

    public static String shortId(String fingerprint) {
        return fingerprint.substring(0, 16);
    }
}
