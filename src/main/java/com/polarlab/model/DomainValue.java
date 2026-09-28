package com.polarlab.model;

/**
 * A measured/scalar value that keeps its original textual representation and
 * unit/coordinate basis. Normalisation happens only in the derivation layer;
 * values that cannot be converted stay at their origin with a diagnostic.
 */
public final class DomainValue {
    public final String raw;          // exact representation as imported
    public final String unit;         // e.g. "count", "adu", "nm", "deg", "rad", "dimensionless"
    public final Double numeric;      // parsed numeric in raw unit, or null if not parseable
    public final String basis;        // coordinate/reference basis label, or null
    public final String diagnostic;   // non-null when conversion/parse failed

    public DomainValue(String raw, String unit, String basis, String diagnostic) {
        this.raw = raw;
        this.unit = unit;
        this.basis = basis;
        this.diagnostic = diagnostic;
        Double parsed = null;
        if (raw != null) {
            try {
                parsed = Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignore) {
                parsed = null;
            }
        }
        this.numeric = parsed;
    }

    public static DomainValue of(double v, String unit) {
        return new DomainValue(Double.toString(v), unit, null, null);
    }

    public static DomainValue raw(String raw, String unit) {
        return new DomainValue(raw, unit, null, null);
    }

    /** Convert angular value to radians; leaves a diagnostic if the unit is unknown. */
    public Double asRadians() {
        if (numeric == null) {
            return null;
        }
        if ("deg".equals(unit)) {
            return Math.toRadians(numeric);
        }
        if ("rad".equals(unit)) {
            return numeric;
        }
        return null;
    }

    public boolean convertible() {
        return diagnostic == null && numeric != null;
    }
}
