package polroom.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * Canonical SHA-256 fingerprints.  Conventions (Stokes order, rotation sign)
 * and every referenced source version are part of the fingerprint, so reusing
 * old data under new rules always produces a new identity.
 */
public final class Fingerprint {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(Object object) {
        try {
            Object sorted = sort(object);
            byte[] bytes = CANONICAL.writeValueAsBytes(sorted);
            return sha256(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sort(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            TreeMap<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sort(v)));
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : iterable) {
                out.add(sort(item));
            }
            return out;
        }
        if (value instanceof double[] doubles) {
            java.util.List<Double> out = new java.util.ArrayList<>();
            for (double v : doubles) {
                out.add(v);
            }
            return out;
        }
        if (value instanceof double[][] doubles2) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (double[] row : doubles2) {
                out.add(sort(row));
            }
            return out;
        }
        if (value instanceof Object[] objects) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : objects) {
                out.add(sort(item));
            }
            return out;
        }
        return value;
    }

    /** Fingerprint of a derivation basis: input versions + conventions + rule version. */
    public static String derivation(String ruleVersion, java.util.List<String> sourceVersions,
                                    String stokesOrder, String rotationConvention,
                                    java.util.Map<String, Object> parameters) {
        java.util.Map<String, Object> basis = new TreeMap<>();
        basis.put("ruleVersion", ruleVersion);
        basis.put("sourceVersions", new java.util.ArrayList<>(sourceVersions.stream()
                .sorted().toList()));
        basis.put("stokesOrder", stokesOrder);
        basis.put("rotationConvention", rotationConvention);
        basis.put("parameters", new TreeMap<>(parameters));
        return of(basis);
    }

    private Fingerprint() {
    }
}
