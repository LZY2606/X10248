package com.polarlab.optics;

import java.util.Arrays;

/**
 * Stokes component order and rotation sign convention. Both enter the analysis fingerprint:
 * reinterpreting the same raw bytes under another convention creates a new derived lineage,
 * it never silently mutates the previous one.
 *
 * @param order permutation p where result[i] = canonical[p[i]]; canonical = [I,Q,U,V]
 * @param rotationSign +1 means positive physical angles rotate Q toward U (counterclockwise
 *                     looking into the beam); -1 mirrors that convention.
 */
public record BasisConvention(String name, int[] order, int rotationSign) {

    public static final String[] CANONICAL = {"I", "Q", "U", "V"};

    public static BasisConvention iquvCcw() {
        return new BasisConvention("IQUV+CCW", new int[]{0, 1, 2, 3}, +1);
    }

    public static BasisConvention ivuqCw() {
        // deliberately different Stokes order and sign: output [I,V,U,Q] under CW
        return new BasisConvention("IVUQ+CW", new int[]{0, 3, 2, 1}, -1);
    }

    public static BasisConvention iquvCw() {
        return new BasisConvention("IQUV+CW", new int[]{0, 1, 2, 3}, -1);
    }

    /** Reorder a canonical I,Q,U,V vector into this convention's coordinate basis. */
    public double[] fromCanonical(double[] canonical) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = canonical[order[i]];
        return out;
    }

    /** Apply the sign convention to a physical angle, returning the convention's theta. */
    public double signedAngle(double physicalRadians) {
        return rotationSign * physicalRadians;
    }

    /** Inverse of order: canonical index -> position in this basis. */
    public int[] inverseOrder() {
        int[] inv = new int[4];
        for (int i = 0; i < 4; i++) inv[order[i]] = i;
        return inv;
    }

    public String[] labels() {
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = CANONICAL[order[i]];
        return out;
    }

    @Override
    public String toString() {
        return name + Arrays.toString(labels()) + " sign=" + rotationSign;
    }
}
