package polopt.domain;

import java.util.List;

/**
 * Units and coordinate-basis bookkeeping. Raw sources keep their original
 * representation; the derivation layer is the only place normalization occurs.
 */
public final class Units {

    public record Q(double value, String unit, String basis, boolean convertible, Double valueSi) {
    }

    private Units() {
    }

    private static double factorToSi(String unit) {
        return switch (unit) {
            case "nm" -> 1.0;
            case "um" -> 1000.0;
            case "A" -> 0.1;
            case "deg" -> Math.PI / 180.0;
            case "rad" -> 1.0;
            case "count", "counts", "adu" -> 1.0;
            case "dimensionless" -> 1.0;
            default -> Double.NaN;
        };
    }

    /** A value that cannot be converted is kept at its original location with a diagnostic. */
    public static Q parse(Object raw, String unit, String basis) {
        if (raw instanceof Number n) {
            double f = factorToSi(unit);
            if (!Double.isFinite(f)) {
                return new Q(n.doubleValue(), unit, basis, false, null);
            }
            return new Q(n.doubleValue(), unit, basis, true, n.doubleValue() * f);
        }
        return new Q(Double.NaN, unit, basis, false, null);
    }

    public static String describe(Q q) {
        if (!q.convertible) {
            return "UNCONVERTIBLE value=" + q.value + " unit=" + q.unit + " basis=" + q.basis;
        }
        return q.value + " " + q.unit + " (SI " + q.valueSi + ", basis=" + q.basis + ")";
    }

    public static List<String> knownUnits() {
        return List.of("nm", "um", "A", "deg", "rad", "count", "counts", "adu", "dimensionless");
    }
}
