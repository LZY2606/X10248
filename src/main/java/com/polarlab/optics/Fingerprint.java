package com.polarlab.optics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic identity fingerprint for an interpretation. Rotation sign convention and
 * Stokes order are explicit inputs, so the same raw bytes reinterpreted under a different
 * convention produce a different fingerprint rather than silently replacing the old result.
 */
public final class Fingerprint {

    private Fingerprint() {}

    public static String sha256Hex(String canonicalText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Canonical text combines:
     *  - ordered source version refs (kind:versionId:contentHash)
     *  - convention fingerprint (Stokes order + rotation sign)
     *  - rule set (estimator threshold, algorithm versions)
     *  - parameters (sorted key=value)
     */
    public static String canonical(Iterable<Map.Entry<String, String>> orderedSourceRefs,
                                   BasisConvention convention,
                                   String ruleSetId,
                                   Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("sources:\n");
        for (Map.Entry<String, String> e : orderedSourceRefs) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        sb.append("convention:").append(convention.name())
                .append("|order=").append(java.util.Arrays.toString(convention.order()))
                .append("|rotationSign=").append(convention.rotationSign()).append('\n');
        sb.append("rules:").append(ruleSetId).append('\n');
        sb.append("params:\n");
        TreeMap<String, String> sorted = new TreeMap<>(params);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    public static String of(Iterable<Map.Entry<String, String>> refs, BasisConvention conv,
                            String rules, Map<String, String> params) {
        return sha256Hex(canonical(refs, conv, rules, params));
    }
}
