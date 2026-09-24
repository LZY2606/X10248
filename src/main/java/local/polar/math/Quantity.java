package local.polar.math;

import java.util.Objects;

/**
 * A value that always travels with its original textual representation, unit,
 * coordinate basis and a conversion diagnostic.
 *
 * <p>Import rule: the original representation is never discarded. Only the
 * derived layer normalizes units; values that cannot be converted stay where
 * they are and carry a diagnostic instead of being silently dropped.
 */
public final class Quantity {

    private final String raw;
    private final String unit;
    private final String coordinateBasis;
    private final Double siValue;
    private final String diagnostic;

    private Quantity(String raw, String unit, String coordinateBasis,
                     Double siValue, String diagnostic) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.unit = unit == null ? "" : unit;
        this.coordinateBasis = coordinateBasis == null ? "" : coordinateBasis;
        this.siValue = siValue;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static Quantity of(String raw, String unit, String coordinateBasis) {
        Parsed p = parse(raw);
        if (p == null) {
            return new Quantity(raw, unit, coordinateBasis, null,
                    "UNPARSEABLE: value kept at original position, not normalized");
        }
        double v = p.value;
        if (unit == null || unit.isBlank()) {
            return new Quantity(raw, "", coordinateBasis, v,
                    p.uncertainty == null ? "" : "uncertainty:" + p.uncertainty);
        }
        Double factor = Units.toSi(unit);
        if (factor == null) {
            return new Quantity(raw, unit, coordinateBasis, null,
                    "UNKNOWN_UNIT:" + unit + "; kept raw, no normalization applied");
        }
        return new Quantity(raw, unit, coordinateBasis, v * factor,
                p.uncertainty == null ? "" : "uncertainty:" + (p.uncertainty * factor)
                        + ";si-unit:" + Units.siName(unit));
    }

    public static Quantity rawOnly(String raw, String diagnostic) {
        return new Quantity(raw, "", "", null, diagnostic);
    }

    public String raw() {
        return raw;
    }

    public String unit() {
        return unit;
    }

    public String coordinateBasis() {
        return coordinateBasis;
    }

    public Double siValue() {
        return siValue;
    }

    public boolean convertible() {
        return siValue != null;
    }

    public String diagnostic() {
        return diagnostic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity q)) return false;
        return raw.equals(q.raw) && unit.equals(q.unit)
                && coordinateBasis.equals(q.coordinateBasis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, unit, coordinateBasis);
    }

    @Override
    public String toString() {
        return raw + (unit.isEmpty() ? "" : " " + unit)
                + (coordinateBasis.isEmpty() ? "" : " [" + coordinateBasis + "]")
                + (siValue == null ? " (unconverted)" : "");
    }

    record Parsed(double value, Double uncertainty) {
    }

    private static Parsed parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        int plusMinus = t.indexOf('±');
        if (plusMinus >= 0) {
            Double v = num(t.substring(0, plusMinus).trim());
            Double u = num(t.substring(plusMinus + 1).trim());
            if (v == null || u == null) return null;
            return new Parsed(v, u);
        }
        Double v = num(t);
        return v == null ? null : new Parsed(v, null);
    }

    private static Double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
