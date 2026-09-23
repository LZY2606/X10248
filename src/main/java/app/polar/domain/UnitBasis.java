package app.polar.domain;

import java.util.Map;

/**
 * Minimal explicit unit table. We intentionally do not guess unknown units:
 * a value that cannot be converted stays at its origin with a diagnostic.
 */
public final class UnitBasis {
    private static final Map<String, Double> TO_RAD = Map.of(
            "rad", 1.0,
            "deg", Math.PI / 180.0,
            "mrad", 1.0e-3);
    private static final Map<String, Double> TO_NM = Map.of(
            "nm", 1.0,
            "um", 1000.0,
            "µm", 1000.0);

    private UnitBasis() {
    }

    public static Double convert(double value, String from, String to) {
        if (from == null || from.equals(to) || "1".equals(from) && "1".equals(to)) {
            return value;
        }
        Double f = TO_RAD.get(from);
        Double t = TO_RAD.get(to);
        if (f != null && t != null) {
            return value * f / t;
        }
        f = TO_NM.get(from);
        t = TO_NM.get(to);
        if (f != null && t != null) {
            return value * f / t;
        }
        return null;
    }
}
