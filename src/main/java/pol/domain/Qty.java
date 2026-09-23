package pol.domain;

import java.util.Objects;

/**
 * A measured or derived scalar carrying its original representation, a unit or
 * coordinate basis, and an optional numeric normalization. Values that cannot
 * be converted are retained in place with a diagnostic instead of dropped.
 */
public final class Qty {
    public final String raw;
    public final String unit;
    public final double numeric;
    public final boolean convertible;
    public final String diagnostic;

    private Qty(String raw, String unit, double numeric, boolean convertible, String diagnostic) {
        this.raw = raw;
        this.unit = unit;
        this.numeric = numeric;
        this.convertible = convertible;
        this.diagnostic = diagnostic;
    }

    public static Qty of(String raw, String unit, double value) {
        return new Qty(raw, unit, value, true, null);
    }

    public static Qty rawOnly(String raw, String unit, String diagnostic) {
        return new Qty(raw, unit, Double.NaN, false, diagnostic);
    }

    public static Qty dimless(double value) {
        return new Qty(format(value), "1", value, true, null);
    }

    public static Qty counts(double value) {
        return new Qty(format(value), "count", value, true, null);
    }

    public static Qty nm(double value) {
        return new Qty(format(value), "nm", value, true, null);
    }

    public static String format(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0) {
            return "0";
        }
        double a = Math.abs(v);
        if (a >= 1e4 || a < 1e-3) {
            return String.format("%.6e", v);
        }
        return String.format("%.6g", v);
    }

    @Override
    public String toString() {
        return convertible ? raw + " " + unit : raw + " " + unit + " (unconverted: " + diagnostic + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Qty)) {
            return false;
        }
        Qty qty = (Qty) o;
        return Objects.equals(raw, qty.raw) && Objects.equals(unit, qty.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, unit);
    }
}
