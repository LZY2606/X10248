package pol.domain;

import org.ejml.simple.SimpleMatrix;

/**
 * Cloude coherency-matrix realization test.
 *
 * Convention used throughout the room (stated explicitly so it is auditable):
 * Jones Pauli basis G_mu = sigma_mu / sqrt(2) with
 *   sigma0 = I, sigma1 = diag(1,-1), sigma2 = [[0,-i],[i,0]], sigma3 = [[0,1],[1,0]]
 * Mueller elements and coherency entries are related by
 *   M_{mu nu} = 1/2 tr(sigma_mu J sigma_nu J†)
 *   T_{ij} = <k_i k_j*>, k_i = tr(G_i J).
 * The real linear map T -> vec(M) is built numerically from these definitions
 * rather than transcribed, so a sign slip in a paper table cannot sneak in.
 */
public final class Cloude {

    /** 16 real coefficients parameterising the 4x4 Hermitian coherency matrix. */
    public static final class Coherency {
        public final double[] realParams;
        public final double[][] eigRe;
        public final double minEigenvalue;
        public final boolean physicallyRealizable;

        Coherency(double[] p, double[] eig, double min, boolean psd) {
            this.realParams = p;
            this.eigRe = new double[][] {eig};
            this.minEigenvalue = min;
            this.physicallyRealizable = psd;
        }

        public double[] eigenvalues() {
            return eigRe[0];
        }
    }

    /** Real 16x16 matrix mapping the 16 Hermitian parameters to row-major vec(M). */
    public static final SimpleMatrix K = buildMap();

    private static final Complex2[][] SIGMA = buildSigma();

    private static Complex2[][] buildSigma() {
        Complex2[][] s = new Complex2[4][];
        s[0] = new Complex2[] {new Complex2(1, 0), new Complex2(0, 0),
                new Complex2(0, 0), new Complex2(1, 0)};
        s[1] = new Complex2[] {new Complex2(1, 0), new Complex2(0, 0),
                new Complex2(0, 0), new Complex2(-1, 0)};
        s[2] = new Complex2[] {new Complex2(0, 0), new Complex2(0, -1),
                new Complex2(0, 1), new Complex2(0, 0)};
        s[3] = new Complex2[] {new Complex2(0, 0), new Complex2(1, 0),
                new Complex2(1, 0), new Complex2(0, 0)};
        return s;
    }

    private static Complex2 sig(int mu, int r, int c) {
        return SIGMA[mu][r * 2 + c];
    }

    private static Complex2 g(int basis, int r, int c) {
        return sig(basis, r, c).scale(1.0 / Math.sqrt(2.0));
    }

    /** Hermitian basis matrices for T: real diagonal, E_ij+E_ji, i(E_ij-E_ji). */
    private static Complex2 hBasis(int q, int r, int c) {
        if (q < 4) {
            return (r == q && c == q) ? new Complex2(1, 0) : Complex2.ZERO;
        }
        int t = q - 4;
        if (t < 6) {
            int[] pair = pairOf(t);
            int i = pair[0];
            int j = pair[1];
            if (r == i && c == j) {
                return new Complex2(1, 0);
            }
            if (r == j && c == i) {
                return new Complex2(1, 0);
            }
            return Complex2.ZERO;
        }
        int u = t - 6;
        int[] pair = pairOf(u);
        int i = pair[0];
        int j = pair[1];
        if (r == i && c == j) {
            return new Complex2(0, 1);
        }
        if (r == j && c == i) {
            return new Complex2(0, -1);
        }
        return Complex2.ZERO;
    }

    private static int[] pairOf(int t) {
        int[][] pairs = {{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}};
        return pairs[t];
    }

    /**
     * Mueller image of one Hermitian coherency basis matrix H_q:
     *   M_q[mu,nu] = 1/2 sum_ij H_q[i,j] tr(sigma_mu G_i sigma_nu G_j)
     * (G_j appears directly because the Pauli matrices are Hermitian).
     */
    private static double muellerImage(int q, int mu, int nu) {
        double re = 0;
        double im = 0;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Complex2 hij = hBasis(q, i, j);
                Complex2 tr = trace4(mu, i, nu, j);
                Complex2 term = tr.mult(hij).scale(0.5);
                re += term.re;
                im += term.im;
            }
        }
        if (Math.abs(im) > 1e-9) {
            throw new IllegalStateException("non-real Mueller image q=" + q + " im=" + im);
        }
        return re;
    }

    /** tr(sigma_mu G_i sigma_nu G_j) for 2x2 Pauli/G matrices. */
    private static Complex2 trace4(int mu, int i, int nu, int j) {
        Complex2 acc = Complex2.ZERO;
        for (int r = 0; r < 2; r++) {
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    for (int c = 0; c < 2; c++) {
                        acc = acc.plus(sig(mu, r, a)
                                .mult(g(i, a, b))
                                .mult(sig(nu, b, c))
                                .mult(g(j, c, r)));
                    }
                }
            }
        }
        return acc;
    }

    private static SimpleMatrix buildMap() {
        SimpleMatrix k = new SimpleMatrix(16, 16);
        for (int q = 0; q < 16; q++) {
            for (int mu = 0; mu < 4; mu++) {
                for (int nu = 0; nu < 4; nu++) {
                    k.set(mu * 4 + nu, q, muellerImage(q, mu, nu));
                }
            }
        }
        return k;
    }

    /** Convert row-major 4x4 Mueller into coherency parameters and eigenvalues. */
    public static Coherency analyze(double[][] mueller) {
        SimpleMatrix mvec = new SimpleMatrix(16, 1);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                mvec.set(i * 4 + j, mueller[i][j]);
            }
        }
        SimpleMatrix tvec = Linalg.invert(K).mult(mvec);
        double[] params = new double[16];
        for (int q = 0; q < 16; q++) {
            params[q] = tvec.get(q);
        }

        SimpleMatrix big = new SimpleMatrix(8, 8);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                Complex2 t = tEntry(params, i, j);
                big.set(i, j, t.re);
                big.set(i, 4 + j, -t.im);
                big.set(4 + i, j, t.im);
                big.set(4 + i, 4 + j, t.re);
            }
        }
        Linalg.SimpleEigen eig = Linalg.symmetricEigen(big);
        double[] four = new double[4];
        System.arraycopy(eig.values, 0, four, 0, 4);
        double min = four[0];
        boolean psd = min >= -1e-7;
        return new Coherency(params, four, min, psd);
    }

    private static Complex2 tEntry(double[] p, int i, int j) {
        Complex2 acc = Complex2.ZERO;
        for (int q = 0; q < 16; q++) {
            acc = acc.plus(hBasis(q, i, j).scale(p[q]));
        }
        return acc;
    }

    public static double entropy(double[] eig) {
        double h = 0;
        double sum = 0;
        for (double v : eig) {
            sum += Math.max(v, 0);
        }
        for (double v : eig) {
            if (v > 1e-15) {
                double p = v / sum;
                h -= p * (Math.log(p) / Math.log(2));
            }
        }
        return h;
    }
}
