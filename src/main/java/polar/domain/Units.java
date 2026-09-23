package polar.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Unit registry. Raw values are stored verbatim at import; only the derived
 * layer normalizes through this table. Unknown units are NOT convertible and
 * must stay in place with a diagnostic attached.
 */
public final class Units {
    private Units() {}

    public record Conversion(double factor, String canonicalUnit) {}

    private static final Map<String, Conversion> TABLE = Map.of(
            "count",  new Conversion(1.0, "count"),
            "kcount", new Conversion(1000.0, "count"),
            "deg",    new Conversion(Math.PI / 180.0, "rad"),
            "rad",    new Conversion(1.0, "rad"),
            "nm",     new Conversion(1.0, "nm"),
            "um",     new Conversion(1000.0, "nm")
    );

    public static boolean convertible(String unit) {
        return unit != null && TABLE.containsKey(unit);
    }

    /** Value in canonical units, or empty when the unit has no known conversion. */
    public static Optional<Double> toCanonical(double value, String unit) {
        Conversion c = unit == null ? null : TABLE.get(unit);
        return c == null ? Optional.empty() : Optional.of(value * c.factor);
    }

    public static String canonicalUnit(String unit) {
        Conversion c = unit == null ? null : TABLE.get(unit);
        return c == null ? null : c.canonicalUnit;
    }
}
