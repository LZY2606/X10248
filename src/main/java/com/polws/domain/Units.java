package com.polws.domain;

import com.polws.model.Q;

import java.util.Map;

/**
 * Unit / coordinate-basis registry used only by the derived layer.
 * Raw rows keep their original representation forever; conversion failures are
 * returned as diagnostics rather than causing the value to be dropped.
 */
public final class Units {
    private Units() {
    }

    public static final class Conversion {
        public final Q original;
        public final Q si;
        public final String diagnostic;

        private Conversion(Q original, Q si, String diagnostic) {
            this.original = original;
            this.si = si;
            this.diagnostic = diagnostic;
        }

        public boolean ok() {
            return diagnostic == null;
        }
    }

    // multiplicative factors to SI
    private static final Map<String, Double> FACTORS = Map.of(
            "nm", 1e-9,
            "um", 1e-6,
            "µm", 1e-6,
            "m", 1.0,
            "deg", Math.PI / 180.0,
            "rad", 1.0,
            "counts", 1.0,
            "adu", 1.0,
            "s", 1.0,
            "ms", 1e-3,
            "1", 1.0,
            "dimensionless", 1.0
    );

    private static final Map<String, String> SI_NAME = Map.of(
            "nm", "m", "um", "m", "µm", "m", "m", "m",
            "deg", "rad", "rad", "rad",
            "counts", "counts", "adu", "adu",
            "s", "s", "ms", "s",
            "1", "1", "dimensionless", "1"
    );

    public static boolean isKnown(String unit) {
        return FACTORS.containsKey(unit);
    }

    public static Conversion toSi(Q q) {
        Double f = FACTORS.get(q.unit);
        if (f == null) {
            return new Conversion(q, q, "UNCONVERTIBLE_UNIT: no SI conversion registered for '" + q.unit
                    + "'; value retained in original position and excluded from normalized derivation");
        }
        return new Conversion(q, new Q(q.value * f, SI_NAME.getOrDefault(q.unit, q.unit), q.basis), null);
    }
}
