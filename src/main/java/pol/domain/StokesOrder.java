package pol.domain;

import java.util.List;
import java.util.Map;

/**
 * Declared Stokes component ordering. The ordering is part of the analysis
 * fingerprint: reinterpreting an (I,Q,U,V) vector as (I,Q,V,U) silently swaps
 * circular and +/-45 degree linear channels.
 */
public record StokesOrder(List<String> components, String label) {
    public static final StokesOrder IQUV = new StokesOrder(List.of("I", "Q", "U", "V"), "IQUV");
    public static final StokesOrder IQVU = new StokesOrder(List.of("I", "Q", "V", "U"), "IQVU");

    public static StokesOrder of(List<String> components) {
        List<String> norm = components == null
                ? List.of("I", "Q", "U", "V")
                : components.stream().map(String::trim).map(String::toUpperCase).toList();
        if (norm.size() != 4 || !norm.containsAll(List.of("I", "Q", "U", "V"))) {
            throw new IllegalArgumentException("Stokes order must contain I,Q,U,V once: " + components);
        }
        return new StokesOrder(List.copyOf(norm), String.join("", norm));
    }

    /** Map a vector declared in {@code from} order into canonical IQUV order. */
    public double[] toIQUV(double[] vector) {
        double[] out = new double[4];
        Map<String, Integer> idx = Map.of(
                "I", 0, "Q", 1, "U", 2, "V", 3);
        for (int i = 0; i < 4; i++) {
            out[idx.get(components.get(i))] = vector[i];
        }
        return out;
    }

    public String fingerprint() {
        return "stokes=" + label;
    }
}
