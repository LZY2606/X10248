package polbench.derive;

import polbench.lin.M;

import java.util.*;

/**
 * Polarization primitives. Stokes order and rotation sign are explicit
 * conventions that enter every fingerprint; they are never implicit globals.
 */
public final class Optics {
    private Optics() {}

    public static final List<String> STOKES_IQUV = List.of("I", "Q", "U", "V");
    public static final List<String> STOKES_IVUQ = List.of("I", "V", "U", "Q");

    public record Convention(String stokesOrder, String rotationSign) {
        public String fingerprint() { return "stokes=" + stokesOrder + ";rot=" + rotationSign; }
        public static Convention standard() { return new Convention("I,Q,U,V", "+right-handed"); }
    }

    /** Permutation of Mueller rows/columns for a Stokes order convention, relative to IQUV. */
    public static int[] permutation(String order) {
        List<String> canon = STOKES_IQUV;
        String[] tokens = order.replace(" ", "").split(",");
        int[] p = new int[4];
        for (int i = 0; i < 4; i++)
            p[i] = canon.indexOf(tokens[i]);
        return p;
    }

    public static double[][] reorder(double[][] m, String order) {
        if (order == null || order.replace(" ", "").equals("I,Q,U,V")) return m;
        int[] p = permutation(order);
        double[][] out = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                out[i][j] = m[p[i]][p[j]];
        return out;
    }

    /** Linear retarder Mueller matrix; sign = +1 standard, -1 flipped convention. */
    public static double[][] retarder(double thetaRad, double deltaRad, int sign) {
        double c = Math.cos(2 * thetaRad), s = sign * Math.sin(2 * thetaRad);
        double cd = Math.cos(deltaRad), sd = Math.sin(deltaRad);
        double[][] m = new double[4][4];
        m[0][0] = 1;
        m[1][1] = c * c + s * s * cd;
        m[1][2] = c * s * (1 - cd);
        m[1][3] = s * sd;
        m[2][1] = c * s * (1 - cd);
        m[2][2] = s * s + c * c * cd;
        m[2][3] = -c * sd;
        m[3][1] = -s * sd;
        m[3][2] = c * sd;
        m[3][3] = cd;
        return m;
    }

    public static double[][] linearPolarizer(double thetaRad) {
        double c = Math.cos(2 * thetaRad), s = Math.sin(2 * thetaRad);
        double half = 0.5;
        double[][] m = new double[4][4];
        m[0][0] = half; m[0][1] = half * c; m[0][2] = half * s; m[0][3] = 0;
        m[1][0] = half * c; m[1][1] = half * c * c; m[1][2] = half * s * c; m[1][3] = 0;
        m[2][0] = half * s; m[2][1] = half * s * c; m[2][2] = half * s * s; m[2][3] = 0;
        m[3] = new double[4];
        return m;
    }

    /** Stokes 4-vector for a pure generator state, built from instrument encoding. */
    public static double[] generatorState(String stateName, Map<String, double[]> table) {
        double[] v = table.get(stateName);
        if (v == null) throw new IllegalArgumentException("unknown generator state " + stateName);
        return v.clone();
    }

    /** Analyzer projection row = first row of analyzer Mueller matrix (polarimeter convention). */
    public static double[] analyzerRow(String stateName, Map<String, double[]> table) {
        double[] v = table.get(stateName);
        if (v == null) throw new IllegalArgumentException("unknown analyzer state " + stateName);
        return v.clone();
    }

    /**
     * Measurement design row: kron(g^T, a) with canonical ordering
     * [m00,m01,...,m03,m10,...] (row-major flattened Mueller).
     */
    public static double[] designRow(double[] analyzer, double[] generator) {
        double[] w = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                w[i * 4 + j] = analyzer[i] * generator[j];
        return w;
    }

    public static double[][] unflatten(double[] x) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++)
            System.arraycopy(x, i * 4, m[i], 0, 4);
        return m;
    }

    public static double[] flatten(double[][] m) {
        double[] x = new double[16];
        for (int i = 0; i < 4; i++)
            System.arraycopy(m[i], 0, x, i * 4, 4);
        return x;
    }

    /** Predicted dark-subtracted intensity a M g. */
    public static double predicted(double[] analyzer, double[][] m, double[] generator) {
        return M.dot(analyzer, M.mul(m, generator));
    }
}
