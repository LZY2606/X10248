package pol.optics;

import java.util.Arrays;
import java.util.List;

/**
 * Declared Stokes component ordering. The canonical internal order is [I,Q,U,V].
 * Imported files may declare other orders; the permutation is parsed at the boundary,
 * kept in the source record, and included in the configuration fingerprint.
 */
public record StokesOrder(List<String> declared) {

    public static final List<String> CANONICAL = List.of("I", "Q", "U", "V");

    public StokesOrder {
        if (declared == null || declared.size() != 4 ||
                declared.stream().distinct().count() != 4 ||
                !declared.containsAll(CANONICAL)) {
            throw new IllegalArgumentException("Stokes order must be a permutation of I,Q,U,V: " + declared);
        }
        declared = List.copyOf(declared);
    }

    public static StokesOrder canonical() {
        return new StokesOrder(CANONICAL);
    }

    public static StokesOrder parse(String raw) {
        if (raw == null || raw.isBlank()) return canonical();
        String[] parts = raw.replace("S0", "I").replace("S1", "Q")
                .replace("S2", "U").replace("S3", "V").split("[,/>|\\s]+");
        return new StokesOrder(Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList());
    }

    /** Index mapping: canonical position for the k-th declared component. declared[?] */
    public int[] toCanonicalPermutation() {
        int[] perm = new int[4];
        for (int k = 0; k < 4; k++) perm[k] = CANONICAL.indexOf(declared.get(k));
        return perm;
    }

    public double[] toCanonical(double[] declaredValues) {
        int[] perm = toCanonicalPermutation();
        double[] out = new double[4];
        for (int k = 0; k < 4; k++) out[perm[k]] = declaredValues[k];
        return out;
    }

    public String signature() {
        return String.join("", declared);
    }
}
