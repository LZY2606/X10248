package pol.mat;

import org.apache.commons.math3.linear.RealMatrix;

/**
 * Cloude coherency algebra for 4x4 Mueller matrices.
 *
 * <p>Convention (versioned as {@code CLOUDE-1986/PAULI-ORTHONORMAL}):
 * sigma_0 = I2, sigma_1 = diag(1,-1), sigma_2 = [[0,1],[1,0]], sigma_3 = [[0,-i],[i,0]].
 * With E_ab = sigma_a (x) sigma_b^* and column-major ordering,
 * H_ab = 1/4 Tr((sigma_a (x) sigma_b^T) M), and M = sum_ab H_ab E_ab.
 * For a Mueller of a Jones system (J (x) J*) this yields H = v v^dagger with
 * v_a = 1/2 Tr(sigma_a J), hence a single non-zero eigenvalue 1 for M = I4.
 */
public final class Coherency {
    private Coherency() {}

    private static final CM[] SIGMA = {pauli0(), pauli1(), pauli2(), pauli3()};
    private static final CM[][] BASIS = new CM[4][4];
    private static final CM[][] BASIS_DAG = new CM[4][4];

    static {
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                BASIS[a][b] = CM.kron(SIGMA[a], conj(SIGMA[b]));
                BASIS_DAG[a][b] = CM.kron(SIGMA[a], transpose(SIGMA[b]));
            }
        }
    }

    private static CM pauli0() {
        CM c = new CM(2);
        c.re[0][0] = 1;
        c.re[1][1] = 1;
        return c;
    }

    private static CM pauli1() {
        CM c = new CM(2);
        c.re[0][0] = 1;
        c.re[1][1] = -1;
        return c;
    }

    private static CM pauli2() {
        CM c = new CM(2);
        c.re[0][1] = 1;
        c.re[1][0] = 1;
        return c;
    }

    private static CM pauli3() {
        CM c = new CM(2);
        c.im[0][1] = -1;
        c.im[1][0] = 1;
        return c;
    }

    private static CM conj(CM x) {
        CM c = new CM(x.n);
        for (int i = 0; i < x.n; i++) {
            for (int j = 0; j < x.n; j++) {
                c.re[i][j] = x.re[i][j];
                c.im[i][j] = -x.im[i][j];
            }
        }
        return c;
    }

    private static CM transpose(CM x) {
        CM c = new CM(x.n);
        for (int i = 0; i < x.n; i++) {
            for (int j = 0; j < x.n; j++) {
                c.re[i][j] = x.re[j][i];
                c.im[i][j] = x.im[j][i];
            }
        }
        return c;
    }

    public static CM fromMueller(RealMatrix m) {
        CM h = new CM(4);
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                double[] tr = traceProduct(BASIS_DAG[a][b], m);
                h.re[a][b] = 0.25 * tr[0];
                h.im[a][b] = 0.25 * tr[1];
            }
        }
        return h;
    }

    private static double[] traceProduct(CM x, RealMatrix m) {
        double r = 0;
        double im = 0;
        for (int i = 0; i < 4; i++) {
            for (int k = 0; k < 4; k++) {
                r += x.re[i][k] * m.getEntry(k, i);
                im += x.im[i][k] * m.getEntry(k, i);
            }
        }
        return new double[] {r, im};
    }

    public static RealMatrix toMueller(CM h) {
        double[][] a = new double[4][4];
        for (int ai = 0; ai < 4; ai++) {
            for (int b = 0; b < 4; b++) {
                CM e = BASIS[ai][b];
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) {
                        a[r][c] += h.re[ai][b] * e.re[r][c] - h.im[ai][b] * e.im[r][c];
                    }
                }
            }
        }
        return M.of(a);
    }

    public static double[] eigenvalues(RealMatrix m) {
        return CM.eighHermitian(fromMueller(m)).values;
    }
}
