package polarlab.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Derivation rule configuration. The Stokes component order and the rotation
 * sign convention are part of the derivation fingerprint, so changing either
 * produces a distinct derived identity instead of silently replacing results.
 */
public final class RuleConfig {
    public final String ruleVersion;
    public final String stokesOrder;        // e.g. "IQUV" or "IVUQ"
    public final String rotationConvention; // "CCW_POSITIVE" or "CW_POSITIVE"
    public final double condThreshold;      // design matrices above this are NOT inverted
    public final double[] ridgeLambdas;     // candidate regularisation strengths

    public RuleConfig(String ruleVersion, String stokesOrder, String rotationConvention,
                      double condThreshold, double[] ridgeLambdas) {
        this.ruleVersion = ruleVersion;
        this.stokesOrder = stokesOrder;
        this.rotationConvention = rotationConvention;
        this.condThreshold = condThreshold;
        this.ridgeLambdas = ridgeLambdas;
    }

    public static RuleConfig baseline() {
        return new RuleConfig("rules-1.0", "IQUV", "CCW_POSITIVE", 1e6,
                new double[]{0.0, 1e-6, 1e-4});
    }

    /** Permutation of the three reduced Stokes indices (1..3) implied by stokesOrder. */
    public int[] stokesPermutation() {
        // stokesOrder is a 4-char string starting with 'I'; remaining chars permute Q,U,V.
        int[] perm = new int[4];
        perm[0] = 0;
        String rest = stokesOrder.substring(1);
        String canonical = "QUV";
        for (int i = 0; i < 3; i++) perm[i + 1] = 1 + canonical.indexOf(rest.charAt(i));
        return perm;
    }

    /** Sign applied to the U,V components of rotated states under this convention. */
    public double rotationSign() {
        return "CCW_POSITIVE".equals(rotationConvention) ? 1.0 : -1.0;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"ruleVersion\":\"").append(ruleVersion).append("\",");
        sb.append("\"stokesOrder\":\"").append(stokesOrder).append("\",");
        sb.append("\"rotationConvention\":\"").append(rotationConvention).append("\",");
        sb.append("\"condThreshold\":").append(condThreshold).append(",");
        sb.append("\"ridgeLambdas\":[");
        for (int i = 0; i < ridgeLambdas.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ridgeLambdas[i]);
        }
        return sb.append("]}").toString();
    }

    public static RuleConfig fromJson(String json) {
        // Tiny tolerant parser sufficient for our own serialised form.
        String rv = grab(json, "ruleVersion");
        String so = grab(json, "stokesOrder");
        String rc = grab(json, "rotationConvention");
        double ct = Double.parseDouble(grabNum(json, "condThreshold"));
        String arr = json.replaceAll(".*\"ridgeLambdas\":\\[", "").replaceAll("\\].*", "");
        String[] parts = arr.isEmpty() ? new String[0] : arr.split(",");
        double[] lam = new double[parts.length];
        for (int i = 0; i < parts.length; i++) lam[i] = Double.parseDouble(parts[i].trim());
        return new RuleConfig(rv, so, rc, ct, lam);
    }

    private static String grab(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":\"([^\"]*)\"")
                .matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing key " + key);
        return m.group(1);
    }

    private static String grabNum(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":([0-9eE+.-]+)")
                .matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing key " + key);
        return m.group(1);
    }

    /**
     * Derivation fingerprint: binds rule version, Stokes order, rotation
     * convention and the exact content hashes of every pinned input artifact.
     */
    public String fingerprint(List<String> inputSha256) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("ruleVersion=" + ruleVersion + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(("stokesOrder=" + stokesOrder + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(("rotationConvention=" + rotationConvention + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(("condThreshold=" + condThreshold + "\n").getBytes(StandardCharsets.UTF_8));
            List<String> sorted = inputSha256.stream().sorted().toList();
            for (String h : sorted) md.update(("in=" + h + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder("fp-");
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
