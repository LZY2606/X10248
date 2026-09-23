package com.polws.domain;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

/**
 * Optical state encoding and design-matrix construction.
 *
 * Rotation sign convention (s = +1 for "POS: counter-clockwise positive", -1 for "NEG")
 * and Stokes order permutation both enter the state fingerprint and the design matrix.
 */
public final class Optics {
    private Optics() {
    }

    /** Stokes component order as a permutation of [0,1,2,3], e.g. S0,S1,S2,S3 or S0,S3,S2,S1. */
    public static int[] parseOrder(String spec) {
        String[] parts = spec.replace("S", "").split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Stokes order must name four components: " + spec);
        }
        int[] perm = new int[4];
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int c = Integer.parseInt(parts[i].trim());
            if (c < 0 || c > 3 || seen[c]) {
                throw new IllegalArgumentException("invalid/duplicate Stokes component: " + spec);
            }
            seen[c] = true;
            perm[i] = c;
        }
        return perm;
    }

    public static int rotationSign(String convention) {
        return switch (convention) {
            case "POS" -> 1;
            case "NEG" -> -1;
            default -> throw new IllegalArgumentException("rotation convention must be POS or NEG: " + convention);
        };
    }

    /**
     * Stokes vector of a polarization state generator element.
     * type: "LP" linear polarizer, "QWP" quarter-wave plate, "HWP" half-wave plate, "RCP","LCP".
     * thetaRad is the already-normalized mount angle; s is the rotation sign convention.
     * Returned in the standard order (S0,S1,S2,S3); callers re-order via perm.
     */
    public static double[] stokesStandard(String type, double thetaRad, int s) {
        double t = s * thetaRad;
        return switch (type) {
            case "LP" -> new double[]{1, Math.cos(2 * t), Math.sin(2 * t), 0};
            case "RCP" -> new double[]{1, 0, 0, 1};
            case "LCP" -> new double[]{1, 0, 0, -1};
            case "QWP" -> qwpOutput(t);
            case "HWP" -> new double[]{1, Math.cos(4 * t), Math.sin(4 * t), 0};
            default -> throw new IllegalArgumentException("unknown optic type: " + type);
        };
    }

    private static double[] qwpOutput(double t) {
        // QWP after fixed horizontal polarizer:
        double c = Math.cos(t);
        double si = Math.sin(t);
        // Mueller QWP applied to [1,1,0,0]
        double c2 = c * c;
        double s2 = si * si;
        double cs = c * si;
        double s1 = c2 - s2;
        double s2v = 2 * cs;
        double s3 = 2 * cs; // standard convention; sign of S3 tied to fast-axis convention
        return new double[]{1, s1, s2v, s3};
    }

    /** Re-order a standard-order Stokes vector according to the permutation. */
    public static double[] permute(double[] standard, int[] perm) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = standard[perm[i]];
        }
        return out;
    }

    /**
     * Design row for one (generator g, analyzer a) measurement pair, instrument-corrected:
     * d = (W_psa S_a) (x) (W_psg S_g), column-major over M.
     */
    public static double[] designRow(double[] sGen, double[] sAna, RealMatrix wPsg, RealMatrix wPsa) {
        double[] g = wPsg == null ? sGen.clone() : wPsg.operate(sGen);
        double[] a = wPsa == null ? sAna.clone() : wPsa.operate(sAna);
        double[] row = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                row[4 * i + j] = a[i] * g[j];
            }
        }
        return row;
    }

    public static RealMatrix identityInstrument() {
        return MatrixUtils.createRealIdentityMatrix(4);
    }
}
