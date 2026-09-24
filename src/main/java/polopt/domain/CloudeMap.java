package polopt.domain;

/**
 * Numerical, basis-safe conversion between a real Mueller matrix M and its
 * Hermitian 4x4 Cloude coherency matrix H.
 *
 * Using normalized Pauli matrices sigma_0..sigma_3 (Tr sigma_a sigma_b = delta):
 * vec_col(X) = sum_k t_k vec(sigma_k), and
 *   H = 1/4 sum_{i,j} M_ij (sigma_j (x) sigma_i)            expressed on t.
 * The operator is first built in the canonical vec basis (HH,HV,VH,VV), then
 * conjugated by the unitary basis-change U into the Pauli basis. This avoids
 * hand-derived sign/coefficient errors and is round-trip checked in tests.
 */
public final class CloudeMap {

    private CloudeMap() {
    }

    // Pauli matrices stored as complex 2x2 entries [re][im].
    private static final double[][][][] PAULI = buildPauli();
    private static final double[][][] U = buildBasisChange();

    private static double[][][][] buildPauli() {
        double c = 1.0 / Math.sqrt(2.0);
        double[][][][] p = new double[4][2][2][2];
        p[0][0][0] = new double[] { c, 0 };
        p[0][1][1] = new double[] { c, 0 };
        p[1][0][0] = new double[] { c, 0 };
        p[1][1][1] = new double[] { -c, 0 };
        p[2][0][1] = new double[] { c, 0 };
        p[2][1][0] = new double[] { c, 0 };
        p[3][0][1] = new double[] { 0, -c };
        p[3][1][0] = new double[] { 0, c };
        return p;
    }

    private static double[][][] buildBasisChange() {
        // U rows = canonical vec(HH,HV,VH,VV), columns = vec(sigma_k).
        double[][][] u = new double[4][4][2];
        for (int k = 0; k < 4; k++) {
            for (int a = 0; a < 4; a++) {
                int row = a / 2;
                int col = a % 2;
                u[a][k] = PAULI[k][row][col].clone();
            }
        }
        return u;
    }

    private static double[] cMul(double[] a, double[] b) {
        return new double[] { a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0] };
    }

    /** Canonical-basis element (r,s) of A (x) B with A,B complex 2x2. */
    private static double[] kronElement(double[][][] a, double[][][] b, int r, int s) {
        double[] av = a[r / 2][s / 2];
        double[] bv = b[r % 2][s % 2];
        return cMul(av, bv);
    }

    private static double[][][] conjTranspose(double[][][] u) {
        double[][][] r = new double[4][4][2];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                r[j][i] = new double[] { u[i][j][0], -u[i][j][1] };
            }
        }
        return r;
    }

    private static double[][][] multiplyComplex(double[][][] a, double[][][] b) {
        double[][][] r = new double[4][4][2];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double re = 0;
                double im = 0;
                for (int t = 0; t < 4; t++) {
                    double[] p = cMul(a[i][t], b[t][j]);
                    re += p[0];
                    im += p[1];
                }
                r[i][j] = new double[] { re, im };
            }
        }
        return r;
    }

    /** sigma_j (x) sigma_i in the Pauli basis. */
    private static double[][][] operatorPauliBasis(int i, int j) {
        double[][][] t = new double[4][4][2];
        for (int r = 0; r < 4; r++) {
            for (int s = 0; s < 4; s++) {
                t[r][s] = kronElement(PAULI[j], PAULI[i], r, s);
            }
        }
        double[][][] uh = conjTranspose(U);
        return multiplyComplex(multiplyComplex(uh, t), U);
    }

    public static double[][] fromMueller(double[][] m) {
        double[][][] h = new double[4][4][2];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double[][][] op = operatorPauliBasis(i, j);
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) {
                        double f = 0.25 * m[i][j];
                        h[a][b][0] += f * op[a][b][0];
                        h[a][b][1] += f * op[a][b][1];
                    }
                }
            }
        }
        double[][] real = new double[4][4];
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                if (Math.abs(h[a][b][1]) > 1e-10) {
                    throw new IllegalStateException(
                            "non-real Cloude entry at [" + a + "][" + b + "] imag=" + h[a][b][1]);
                }
                real[a][b] = h[a][b][0];
            }
        }
        return real;
    }

    public static double[][] toMueller(double[][] h) {
        // Invert via M_ij = Tr(sigma_j^T X sigma_i^T...) using the same operators:
        // H = 1/4 sum M_ij O_ij  =>  M_ij = Tr(O_ij^dagger H)  (orthonormal operators).
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double[][][] op = operatorPauliBasis(i, j);
                double v = 0.0;
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) {
                        // Tr(O^dagger H): O^dagger[a][b] = conj(O[b][a])
                        double or = op[b][a][0]; // imag zero by symmetry here
                        v += or * h[a][b];
                    }
                }
                m[i][j] = v;
            }
        }
        return m;
    }
}
