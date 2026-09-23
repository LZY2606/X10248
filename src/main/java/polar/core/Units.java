package polar.core;

import java.util.Map;
import java.util.Optional;

/**
 * Quantities carry a unit and a coordinate basis. Raw representations are
 * preserved at import; normalization happens only in the derived layer.
 * Values that cannot be converted stay in place with a diagnostic - they are
 * never dropped to make a plot work.
 */
public final class Units {
    private Units() {}

    public record Quantity(double value, String unit, String basis) {}

    public record Conversion(Quantity quantity, String diagnostic) {
        public boolean ok() { return diagnostic == null; }
    }

    private static final Map<String, Double> TO_BASE = Map.of(
            "nm", 1.0,
            "um", 1000.0,
            "angstrom", 0.1,
            "deg", 1.0,
            "rad", 180.0 / Math.PI,
            "counts", 1.0,
            "kcounts", 1000.0);

    private static final Map<String, String> DIMENSION = Map.of(
            "nm", "length", "um", "length", "angstrom", "length",
            "deg", "angle", "rad", "angle",
            "counts", "intensity", "kcounts", "intensity");

    /** Convert to {@code targetUnit}; unconvertible values stay raw with a diagnostic. */
    public static Conversion convert(Quantity q, String targetUnit) {
        String fromDim = DIMENSION.get(q.unit());
        String toDim = DIMENSION.get(targetUnit);
        if (fromDim == null || toDim == null || !fromDim.equals(toDim)) {
            return new Conversion(q, "cannot convert unit '" + q.unit()
                    + "' to '" + targetUnit + "'; value left in raw position");
        }
        double base = q.value() * TO_BASE.get(q.unit());
        double target = base / TO_BASE.get(targetUnit);
        return new Conversion(new Quantity(target, targetUnit, q.basis()), null);
    }

    public static Optional<Double> toBaseUnit(Quantity q) {
        if (!TO_BASE.containsKey(q.unit())) return Optional.empty();
        return Optional.of(q.value() * TO_BASE.get(q.unit()));
    }
}
