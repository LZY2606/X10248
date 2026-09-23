package org.polarreview.optics;

/**
 * Cloude coherency matrix H = (1/4) \u03a3_{k,l} M_{kl} (\u03c3_k \u2297 \u03c3_l*), built
 * numerically from the Pauli basis represented as explicit complex pairs.
 *
 * <p>A depolarizing Mueller matrix is physically realizable iff H is Hermitian
 * positive semidefinite. The reverse mapping is the orthogonality relation
 * {@code Tr[(sigma_k \u2297 sigma_l*)^H (sigma_a \u2297 sigma_b*)] = 4 delta}, so
 * {@code M_kl = Tr(E_kl^H H)}.
 */
public final class Cloude {

    // Pauli matrices as {re,im} 2x2.
    public static final double[][][] P0 = {{{1, 0}, {0, 1}}, {{0, 0}, {0, 0}}};
    public static final double[][][] P1 = {{{1, 0}, {0, -1}}, {{0, 0}, {0, 0}}};
    public static final double[][][] P2 = {{{0, 1}, {1, 0}}, {{0, 0}, {0, 0}}};
    public static final double[][][] P3 = {{{0, 0}, {0, 0}}, {{0, 1}, {-1, 0}}};
    public static final double[][][][] PAULI = {P0, P1, P2, P3};

    private Cloude() {}

    /** Returns {Hreal, Himag}. */
    public static double[][][] coherency(double[][] m) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int k = 0; k < 4; k++) {
            for (int l = 0; l < 4; l++) {
                double[][][] e = kronConj(PAULI[k], PAULI[l]);
                double f = 0.25 * m[k][l];
                for (int r = 0; r < 4; r++)
                    for (int c = 0; c < 4; c++) {
                        re[r][c] += f * e[0][r][c];
                        im[r][c] += f * e[1][r][c];
                    }
            }
        }
        return new double[][][] {re, im};
    }

    /** Inverse: M_kl = Tr(E_kl^H H). */
    public static double[][] mueller(double[][] re, double[][] im) {
        double[][] m = new double[4][4];
        // Tr(E^H H) = sum_{i,j} conj(E_{ji}) H_{ji}
        for (int k = 0; k < 4; k++) {
            for (int l = 0; l < 4; l++) {
                double[][][] e = kronConj(PAULI[k], PAULI[l]);
                double s = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        double conjEji_r = e[0][j][i];
                        double conjEji_i = -e[1][j][i];
                        double hji_r = re[j][i];
                        double hji_i = im[j][i];
                        s += conjEji_r * hji_r - conjEji_i * hji_i;
                    }
                }
                m[k][l] = s;
            }
        }
        return m;
    }

    /** Complex Kronecker of a with conjugate(b), flattened from 2x2 blocks to 4x4. */
    private static double[][][] kronConj(double[][][] a, double[][][] b) {
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                double ar = a[0][i][j], ai = a[1][i][j];
                for (int u = 0; u < 2; u++) {
                    for (int v = 0; v < 2; v++) {
                        double br = b[0][u][v];
                        double bi = -b[1][u][v]; // conjugate
                        int R = 2 * i + u;
                        int C = 2 * j + v;
                        re[R][C] = ar * br - ai * bi;
                        im[R][C] = ar * bi + ai * br;
                    }
                }
            }
        }
        return new double[][][] {re, im};
    }
}
