package com.polws.model;

import java.util.Objects;

/**
 * A physical quantity carried in its original representation.
 * valueRaw is never silently converted; conversion happens only in the derived layer
 * ({@link com.polws.domain.Units#toSi(Q)}). Unconvertible values stay in place with a diagnostic.
 */
public final class Q {
    public final double value;
    public final String unit;
    /** coordinate/frame basis label, e.g. "lab", "psg-mount", or null for unitless scalars */
    public final String basis;

    public Q(double value, String unit, String basis) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("quantity value must be finite: " + value);
        }
        this.value = value;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.basis = basis;
    }

    public static Q of(double value, String unit) {
        return new Q(value, unit, null);
    }

    public static Q basis(double value, String unit, String basis) {
        return new Q(value, unit, basis);
    }

    @Override
    public String toString() {
        return value + " " + unit + (basis == null ? "" : " (" + basis + ")");
    }
}
