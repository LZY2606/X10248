package pmx;

import java.util.Arrays;

/**
 * Small self-contained real/complex linear algebra used by the Mueller pipeline.
 * Matrices are row-major double[m*n]. No external numerical dependencies on purpose.
 */
public final class LinAlg {
    private LinAlg() {}

    // ---------- basic real matrix helpers ----------

    public static double[][] mat(int r, int c) { return new double[r][c]; }

    public static double[] mv(double[][] a, double[] x) {
        int m = a.length, n = a[0].length;
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += a[i][j] * x[j];
            y[i] = s;
        }
        return y;
    }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    public static double[][] transpose(double[][] a) {
        int m = a.length, n = a[0].length;
        double[][] t = mat(n, m);
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int m = a.length, k = a[0].length, n = b[0].length;
        double[][] c = mat(m, n);
        for (int i = 0; i < m; i++)
            for (int p = 0; p < k; p++) {
                double aip = a[i][p];
                for (int j = 0; j < n; j++) c[i][j] += aip * b[p][j];
            }
        return c;
    }

    public static double[][] identity(int n) {
        double[][] i = mat(n, n);
        for (int j = 0; j < n; j++) i[j][j] = 1;
        return i;
    }

    /** Gauss-Jordan inverse of a square matrix; throws SingularException. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-14) throw new SingularException("inverse: zero pivot at " + col);
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            double d = m[col][col];
            for (int j = 0; j < 2 * n; j++) m[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] inv = mat(n, n);
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, inv[i], 0, n);
        return inv;
    }

    public static final class SingularException extends RuntimeException {
        public SingularException(String m) { super(m); }
    }

    // ---------- symmetric eigendecomposition (cyclic Jacobi) ----------

    public static final class SymEig {
        public final double[] lambda;
        public final double[][] v; // columns are eigenvectors
        SymEig(double[] lambda, double[][] v) { this.lambda = lambda; this.v = v; }
    }

    /** Eigenvectors/values of a real symmetric matrix, eigenvalues ascending. */
    public static SymEig symEig(double[][] s0) {
        int n = s0.length;
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++) s[i] = s0[i].clone();
        double[][] v = identity(n);
        double tol = 1e-14;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int q = p + 1; q < n; q++) off += s[p][q] * s[p][q];
            if (off < tol * tol) break;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(s[p][q]) < 1e-16) continue;
                    double tau = (s[q][q] - s[p][p]) / (2 * s[p][q]);
                    double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t), si = t * c;
                    for (int k = 0; k < n; k++) {
                        double sp = s[p][k], sq = s[q][k];
                        s[p][k] = c * sp - si * sq;
                        s[q][k] = si * sp + c * sq;
                    }
                    for (int k = 0; k < n; k++) {
                        double sp = s[k][p], sq = s[k][q];
                        s[k][p] = c * sp - si * sq;
                        s[k][q] = si * sp + c * sq;
                    }
                    for (int k = 0; k < n; k++) {
                        double vp = v[k][p], vq = v[k][q];
                        v[k][p] = c * vp - si * vq;
                        v[k][q] = si * vp + c * vq;
                    }
                }
            }
        }
        double[] lam = new double[n];
        for (int i = 0; i < n; i++) lam[i] = s[i][i];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(lam[a], lam[b]));
        double[] lo = new double[n];
        double[][] vo = mat(n, n);
        for (int i = 0; i < n; i++) {
            lo[i] = lam[idx[i]];
            for (int k = 0; k < n; k++) vo[k][i] = v[k][idx[i]];
        }
        return new SymEig(lo, vo);
    }

    /** X = S^{-1/2} for a symmetric positive (semi-)definite S, using eigen clipping. */
    public static double[][] symInvSqrt(double[][] s) {
        SymEig e = symEig(s);
        int n = s.length;
        double[][] x = mat(n, n);
        for (int k = 0; k < n; k++) {
            double l = Math.max(e.lambda[k], 1e-30);
            double f = 1 / Math.sqrt(l);
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) x[i][j] += f * e.v[i][k] * e.v[j][k];
        }
        return x;
    }

    // ---------- Cholesky (for covariance whitening) ----------

    public static double[][] cholesky(double[][] a) {
        int n = a.length;
        double[][] l = mat(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double s = a[i][j];
                for (int k = 0; k < j; k++) s -= l[i][k] * l[j][k];
                if (i == j) {
                    if (s <= 0) throw new SingularException("cholesky: non-positive at " + i);
                    l[i][j] = Math.sqrt(s);
                } else l[i][j] = s / l[j][j];
            }
        }
        return l;
    }

    /** Solve L x = b for lower triangular L. */
    public static double[] solveL(double[][] l, double[] b) {
        int n = b.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= l[i][k] * x[k];
            x[i] = s / l[i][i];
        }
        return x;
    }

    // ---------- SVD via one-sided Jacobi on A^T A ----------

    public static final class SVD {
        public final double[] s;      // singular values, descending
        public final double[][] u;    // columns
        public final double[][] v;    // columns
        public final double smax, smin;
        public final double cond;
        public final int rank;
        SVD(double[] s, double[][] u, double[][] v, int rank) {
            this.s = s; this.u = u; this.v = v; this.rank = rank;
            this.smax = s[0];
            double mn = 0;
            for (double x : s) mn = Math.min(mn == 0 ? x : mn, x);
            this.smin = s[s.length - 1];
            this.cond = smin > 0 ? smax / smin : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * Thin SVD of an m x n matrix (n &lt;= 16 here). Rank threshold is relative
     * to the largest singular value; near-singular cases stay visible through cond.
     */
    public static SVD svd(double[][] a, double relTol) {
        int m = a.length, n = a[0].length;
        double[][] ata = mat(n, n);
        for (int i = 0; i < m; i++)
            for (int p = 0; p < n; p++)
                for (int q = 0; q < n; q++) ata[p][q] += a[i][p] * a[i][q];
        SymEig e = symEig(ata); // ascending
        double[] s = new double[n];
        double[][] v = mat(n, n);
        for (int k = 0; k < n; k++) {
            s[k] = Math.sqrt(Math.max(e.lambda[n - 1 - k], 0));
            for (int i = 0; i < n; i++) v[i][k] = e.v[i][n - 1 - k];
        }
        double[][] u = mat(m, n);
        int rank = 0;
        for (int k = 0; k < n; k++) {
            if (s[k] > relTol * s[0]) {
                rank++;
                double[] av = new double[m];
                for (int i = 0; i < m; i++)
                    for (int j = 0; j < n; j++) av[i] += a[i][j] * v[j][k];
                for (int i = 0; i < m; i++) u[i][k] = av[i] / s[k];
            }
        }
        return new SVD(s, u, v, rank);
    }

    // ---------- minimal complex support (for Hermitian eigenproblems) ----------

    public static final class C {
        public final double re, im;
        public C(double re, double im) { this.re = re; this.im = im; }
        public static C r(double x) { return new C(x, 0); }
        public C add(C o) { return new C(re + o.re, im + o.im); }
        public C mul(C o) { return new C(re * o.re - im * o.im, re * o.im + im * o.re); }
        public C conj() { return new C(re, -im); }
        public double abs2() { return re * re + im * im; }
    }

    /** Eigenvalues of a Hermitian matrix h (n x n, stored C[][]), ascending. */
    public static double[] hermEig(C[][] h) {
        int n = h.length;
        double[][] b = mat(2 * n, 2 * n);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                b[i][j] = h[i][j].re;
                b[n + i][n + j] = h[i][j].re;
                b[i][n + j] = -h[i][j].im;
                b[n + i][j] = h[i][j].im;
            }
        SymEig e = symEig(b);
        // Real-block expansion doubles each complex eigenvalue; take one of each pair.
        double[] out = new double[n];
        for (int k = 0; k < n; k++) out[k] = e.lambda[2 * k];
        return out;
    }

    public static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    public static double normFro(double[][] a) {
        double s = 0;
        for (double[] row : a) for (double x : row) s += x * x;
        return Math.sqrt(s);
    }

    public static String fmt(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        if (x == 0) return "0";
        double ax = Math.abs(x);
        if (ax >= 1e4 || ax < 1e-3) return String.format("%.3e", x);
        return String.format("%.4f", x);
    }
}
