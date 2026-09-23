package polarmueller.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeSet;

/**
 * Stable fingerprint for derived results. The rotation convention and the
 * Stokes component order are explicit inputs: two results computed under
 * different conventions never share a fingerprint.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(String ruleVersion, String stokesOrder, String rotationConvention,
                            List<String> sourceRefs, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("rule=").append(ruleVersion).append('\n');
        sb.append("stokesOrder=").append(stokesOrder).append('\n');
        sb.append("rotationConvention=").append(rotationConvention).append('\n');
        for (String ref : new TreeSet<>(sourceRefs)) sb.append("src=").append(ref).append('\n');
        sb.append("extra=").append(extra == null ? "" : extra);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
