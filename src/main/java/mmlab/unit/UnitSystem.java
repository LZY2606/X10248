package mmlab.unit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal offline unit registry. Values that cannot be converted are left in
 * place together with a {@link ConvertDiagnostic} instead of being dropped.
 */
public final class UnitSystem {

    public static final class Converted {
        public final double siValue;
        public final ConvertDiagnostic diagnostic;

        Converted(double siValue, ConvertDiagnostic diagnostic) {
            this.siValue = siValue;
            this.diagnostic = diagnostic;
        }

        public boolean failed() {
            return diagnostic != null;
        }
    }

    /** dimension -> unit -> linear factor to SI base unit */
    private final Map<String, Map<String, Double>> factors = new HashMap<>();

    public UnitSystem() {
        register("intensity", "count", 1.0);
        register("intensity", "adu", 1.0);
        register("intensity", "W/m^2", 1.0);
        register("intensity", "W/m2", 1.0);
        register("intensity", "normalized", 1.0);
        register("angle", "rad", 1.0);
        register("angle", "deg", Math.PI / 180.0);
        register("angle", "degree", Math.PI / 180.0);
        register("angle", "turn", 2.0 * Math.PI);
        register("wavelength", "m", 1.0);
        register("wavelength", "nm", 1e-9);
        register("wavelength", "um", 1e-6);
        register("wavelength", "µm", 1e-6);
    }

    public void register(String dimension, String unit, double siFactor) {
        factors.computeIfAbsent(dimension, d -> new HashMap<>())
                .put(normalize(unit), siFactor);
    }

    public boolean knows(String dimension, String unit) {
        Map<String, Double> m = factors.get(dimension);
        return m != null && m.containsKey(normalize(unit));
    }

    public Converted toSi(String dimension, String field, Qty q) {
        Map<String, Double> m = factors.get(dimension);
        String u = normalize(q.unit);
        if (m == null || !m.containsKey(u)) {
            return new Converted(q.value, new ConvertDiagnostic(
                    field, q.unit, "SI:" + dimension,
                    "unknown unit '" + q.unit + "' for dimension " + dimension));
        }
        return new Converted(q.value * m.get(u), null);
    }

    private static String normalize(String unit) {
        return unit.trim().toLowerCase(Locale.ROOT).replace("°", "deg");
    }
}
