package org.polarroom.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encoding conventions and deterministic fingerprints.
 *
 * <p>Both the Stokes component ordering on import and the rotation sign
 * convention (positive sense of an azimuth increment) are part of the
 * interpretation fingerprint. Reinterpreting the same bytes under different
 * conventions therefore produces a different, traceable candidate rather than
 * silently changing the numbers.
 */
public final class Conventions {

    public static final List<String> CANONICAL_ORDER = Arrays.asList("S0", "S1", "S2", "S3");

    private Conventions() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stable identity for a derived object. The fingerprint folds in:
     * rule version and parameters, input blob versions, conventions
     * (Stokes order + rotation sign), candidate key and algorithm version.
     */
    public static String fingerprint(String algorithmVersion,
                                     String ruleVersion,
                                     Map<String, Object> parameters,
                                     Map<String, String> inputVersionByMaterial,
                                     String stokesOrder,
                                     int rotationSign,
                                     String candidateKey) {
        Map<String, Object> bag = new LinkedHashMap<>();
        bag.put("algorithm", algorithmVersion);
        bag.put("ruleVersion", ruleVersion);
        bag.put("parameters", new LinkedHashMap<>(parameters));
        Map<String, String> sorted = new LinkedHashMap<>();
        inputVersionByMaterial.keySet().stream().sorted()
                .forEach(k -> sorted.put(k, inputVersionByMaterial.get(k)));
        bag.put("inputs", sorted);
        bag.put("stokesOrder", stokesOrder);
        bag.put("rotationSign", rotationSign);
        bag.put("candidate", candidateKey);
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(bag);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return "fp_" + sha256Hex(json.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    /**
     * Permutation from a recorded header order (e.g. S0,S2,S1,S3) to canonical.
     */
    public static int[] orderPermutation(List<String> header) {
        int[] perm = new int[4];
        for (int i = 0; i < 4; i++) {
            int idx = header.indexOf(CANONICAL_ORDER.get(i));
            if (idx < 0) {
                throw new IllegalArgumentException(
                        "Stokes header missing component " + CANONICAL_ORDER.get(i) + ": " + header);
            }
            perm[i] = idx;
        }
        return perm;
    }

    /** Apply a recorded Stokes vector plus rotation sign to canonical order. */
    public static double[] toCanonical(double[] recorded, int[] permutation, int rotationSign) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            double v = recorded[permutation[i]];
            // S1/S2 (linear components) flip under reversed rotation sense.
            if (rotationSign < 0 && (i == 1 || i == 2)) {
                v = -v;
            }
            out[i] = v;
        }
        return out;
    }

    /**
     * Parse a scalar with a retained unit. Values that cannot be converted stay
     * at their original position with a diagnostic; they are never dropped.
     */
    public static Models.UnitValue parseUnitValue(String raw, String defaultUnit) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return new Models.UnitValue(t, Double.NaN, defaultUnit, false, "empty_value");
        }
        String numberPart;
        String unit;
        int sp = firstWhitespace(t);
        if (sp > 0) {
            numberPart = t.substring(0, sp);
            unit = t.substring(sp + 1).trim();
        } else {
            int cut = numericPrefix(t);
            numberPart = t.substring(0, cut);
            unit = cut < t.length() ? t.substring(cut) : defaultUnit;
        }
        try {
            double v = Double.parseDouble(numberPart);
            if (unit.isEmpty()) {
                unit = defaultUnit;
            }
            return new Models.UnitValue(t, v, unit, true, null);
        } catch (NumberFormatException e) {
            return new Models.UnitValue(t, Double.NaN,
                    unit == null ? defaultUnit : unit, false, "unparseable_numeric");
        }
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int numericPrefix(String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        boolean dot = false;
        boolean e = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !dot && !e) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && !e && i > 0) {
                e = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }
}
