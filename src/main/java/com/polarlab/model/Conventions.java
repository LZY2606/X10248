package com.polarlab.model;

import java.util.Objects;

/**
 * Rotation sign convention and Stokes ordering. These enter the derivation
 * fingerprint: changing either invalidates derived conclusions and yields a
 * distinct identity rather than silently replacing them.
 */
public final class Conventions {
    public final String stokesOrder; // e.g. "IQUV", "IVUQ"
    public final String rotationSign; // "ccw-positive" | "cw-positive" (analyser/PSG rotation sense)
    public final String angleUnit;    // display/preferred unit "deg" | "rad"

    public Conventions(String stokesOrder, String rotationSign, String angleUnit) {
        if (stokesOrder == null || stokesOrder.length() != 4
                || !stokesOrder.contains("I") || !stokesOrder.contains("Q")
                || !stokesOrder.contains("U") || !stokesOrder.contains("V")) {
            throw new IllegalArgumentException("stokes order must be a permutation of I,Q,U,V");
        }
        this.stokesOrder = stokesOrder;
        this.rotationSign = rotationSign;
        this.angleUnit = angleUnit;
    }

    public static Conventions defaultConventions() {
        return new Conventions("IQUV", "ccw-positive", "deg");
    }

    /** Permutation p[k] = index in IQUV of the k-th stored Stokes component. */
    public int[] fromIquvPermutation() {
        int[] p = new int[4];
        String canonical = "IQUV";
        for (int k = 0; k < 4; k++) {
            p[k] = canonical.indexOf(stokesOrder.charAt(k));
        }
        return p;
    }

    public double rotationSignMultiplier() {
        return "cw-positive".equals(rotationSign) ? -1.0 : 1.0;
    }

    public String fingerprintFragment() {
        return "stokes=" + stokesOrder + ";rot=" + rotationSign + ";ang=" + angleUnit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Conventions)) {
            return false;
        }
        Conventions c = (Conventions) o;
        return Objects.equals(stokesOrder, c.stokesOrder)
                && Objects.equals(rotationSign, c.rotationSign)
                && Objects.equals(angleUnit, c.angleUnit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stokesOrder, rotationSign, angleUnit);
    }
}
