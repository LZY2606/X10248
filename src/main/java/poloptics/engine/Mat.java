package poloptics.engine;

/** Minimal dense linear algebra: 4x4-ish real matrices plus SVD of tall design matrices. */
public final class Mat {
    private Mat() {}

    public static double[][] zeros(int r, int c) { return new double[r][c]; }

    public static double[][] transpose(double[][] a) {
        int r = a.length, c = a[0].length;
        double[][] t = new double[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int r = a.length, k = b.length, c = b[0].length;
        double[][] o = new double[r][c];
        for (int i = 0; i < r; i++)
            for (int j = 0; j < c; j++) {
                double s = 0;
                for (int p = 0; p < k; p++) s += a[i][p] * b[p][j];
                o[i][j] = s;
            }
        return o;
    }

    public static double[] mul(double[][] a, double[] x) {
        int r = a.length, k = x.length;
        double[] o = new double[r];
        for (int i = 0; i < r; i++) {
            double s = 0;
            for (int j = 0; j < k; j++) s += a[i][j] * x[j];
            o[i] = s;
        }
        return o;
    }

    public static double[][] identity(int n) {
        double[][] i = new double[n][n];
        for (int k = 0; k < n; k++) i[k][k] = 1;
        return i;
    }

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
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            double d = m[col][col];
            if (Math.abs(d) < 1e-14) throw new ArithmeticException("singular matrix");
            for (int j = 0; j < 2 * n; j++) m[col][j] /= d;
            for (int r = 0; r < n; r++) if (r != col) {
                double f = m[r][col];
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, inv[i], 0, n);
        return inv;
    }

    /** Symmetric positive definite 4x4 square root via Jacobi eigendecomposition. */
    public static double[][] symmetricSqrt(double[][] h) {
        int n = h.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(h[i], 0, a[i], 0, n);
        double[][] v = identity(n);
        double[] d = new double[n];
        jacobi(a, v, d);
        for (int i = 0; i < n; i++) d[i] = Math.sqrt(Math.max(0, d[i]));
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) s[i][j] += v[i][k] * d[k] * v[j][k];
        return s;
    }

    private static void jacobi(double[][] a, double[][] v, double[] d) {
        int n = a.length;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                if (Math.abs(a[p][q]) < 1e-18) continue;
                double tau = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                double c = 1 / Math.sqrt(1 + t * t), s = t * c;
                double app = a[p][p], aqq = a[q][q], apq = a[p][q];
                a[p][p] = app - t * apq;
                a[q][q] = aqq + t * apq;
                a[p][q] = a[q][p] = 0;
                for (int k = 0; k < n; k++) if (k != p && k != q) {
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = a[p][k] = c * akp - s * akq;
                    a[k][q] = a[q][k] = s * akp + c * akq;
                }
                for (int k = 0; k < n; k++) {
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq;
                    v[k][q] = s * vkp + c * vkq;
                }
            }
        }
        for (int i = 0; i < n; i++) d[i] = a[i][i];
    }

    public static double trace(double[][] a) {
        double t = 0;
        for (int i = 0; i < a.length; i++) t += a[i][i];
        return t;
    }

    /**
     * Economy SVD via one-sided Jacobi orthogonalization.
     * Returns {U (m x n), sigma (length n), Vt (n x n)} for m >= n.
     */
    public static Svd svd(double[][] xm) {
        int m = xm.length, n = xm[0].length;
        double[][] v = identity(n);
        double[][] work = new double[m][n];
        for (int i = 0; i < m; i++) System.arraycopy(xm[i], 0, work[i], 0, n);
        for (int sweep = 0; sweep < 80; sweep++) {
            boolean changed = false;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                double alpha = 0, beta = 0, gamma = 0;
                for (int i = 0; i < m; i++) {
                    alpha += work[i][p] * work[i][p];
                    beta += work[i][q] * work[i][q];
                    gamma += work[i][p] * work[i][q];
                }
                if (Math.abs(gamma) < 1e-15 * Math.sqrt(alpha * beta) + 1e-30) continue;
                changed = true;
                double zeta = (beta - alpha) / (2 * gamma);
                double t = (zeta >= 0 ? 1 : -1) / (Math.abs(zeta) + Math.sqrt(1 + zeta * zeta));
                double c = 1 / Math.sqrt(1 + t * t), s = t * c;
                for (int i = 0; i < m; i++) {
                    double a = work[i][p], b = work[i][q];
                    work[i][p] = c * a - s * b;
                    work[i][q] = s * a + c * b;
                }
                for (int i = 0; i < n; i++) {
                    double a = v[i][p], b = v[i][q];
                    v[i][p] = c * a - s * b;
                    v[i][q] = s * a + c * b;
                }
            }
            if (!changed) break;
        }
        double[] sigma = new double[n];
        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < m; i++) norm += work[i][j] * work[i][j];
            sigma[j] = Math.sqrt(norm);
        }
        // bubble sort descending
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++)
            if (sigma[b] > sigma[a]) {
                double ts = sigma[a]; sigma[a] = sigma[b]; sigma[b] = ts;
                for (int i = 0; i < m; i++) { double t = work[i][a]; work[i][a] = work[i][b]; work[i][b] = t; }
                for (int i = 0; i < n; i++) { double t = v[i][a]; v[i][a] = v[i][b]; v[i][b] = t; }
            }
        double[][] u = new double[m][n];
        for (int j = 0; j < n; j++) {
            double s = sigma[j] > 1e-300 ? sigma[j] : 1;
            for (int i = 0; i < m; i++) u[i][j] = work[i][j] / s;
        }
        double[][] vt = transpose(v);
        return new Svd(u, sigma, vt);
    }

    public static final class Svd {
        public final double[][] u;
        public final double[] sigma;
        public final double[][] vt;
        Svd(double[][] u, double[] sigma, double[][] vt) {
            this.u = u; this.sigma = sigma; this.vt = vt;
        }
    }
}
