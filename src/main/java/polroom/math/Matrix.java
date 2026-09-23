package polroom.math;

import java.util.Arrays;

/**
 * Small dense linear-algebra toolkit used for Mueller estimation.
 * Only operations actually needed by the polarization room are provided;
 * the SVD is a one-sided Jacobi implementation (no native deps).
 */
public final class Matrix {
    private Matrix() {}

    public record SVD(double[][] u, double[] s, double[][] vt) {
        public double[][] v() { return transpose(vt); }
        public int rank(double tol) {
            int r = 0;
            for (double v : s) { if (v > tol) r++; }
            return r;
        }
        public double conditionNumber() {
            double smax = s[0];
            double smin = 0;
            for (double v : s) { if (v > 0) smin = v; }
            return smin == 0 ? Double.POSITIVE_INFINITY : smax / smin;
        }
    }

    public static double[][] zeros(int r, int c) {
        double[][] a = new double[r][];
        for (int i = 0; i < r; i++) a[i] = new double[c];
        return a;
    }

    public static double[][] identity(int n) {
        double[][] a = zeros(n, n);
        for (int i = 0; i < n; i++) a[i][i] = 1.0;
        return a;
    }

    public static double[][] copy(double[][] a) {
        double[][] b = new double[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    public static int rows(double[][] a) { return a.length; }
    public static int cols(double[][] a) { return a[0].length; }

    public static double[][] transpose(double[][] a) {
        double[][] t = zeros(cols(a), rows(a));
        for (int i = 0; i < rows(a); i++)
            for (int j = 0; j < cols(a); j++)
                t[j][i] = a[i][j];
        return t;
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        int n = rows(a), m = cols(b), k = cols(a);
        double[][] c = zeros(n, m);
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double aip = a[i][p];
                if (aip == 0.0) continue;
                for (int j = 0; j < m; j++) c[i][j] += aip * b[p][j];
            }
        return c;
    }

    public static double[] multiply(double[][] a, double[] x) {
        int n = rows(a), k = cols(a);
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < k; j++) s += a[i][j] * x[j];
            y[i] = s;
        }
        return y;
    }

    public static double[][] scale(double[][] a, double f) {
        double[][] b = copy(a);
        for (double[] row : b) for (int j = 0; j < row.length; j++) row[j] *= f;
        return b;
    }

    /** Kronecker product a (kron) b, row-major flattening. */
    public static double[][] kron(double[][] a, double[][] b) {
        int ar = rows(a), ac = cols(a), br = rows(b), bc = cols(b);
        double[][] c = zeros(ar * br, ac * bc);
        for (int i = 0; i < ar; i++)
            for (int j = 0; j < ac; j++) {
                double v = a[i][j];
                for (int p = 0; p < br; p++)
                    for (int q = 0; q < bc; q++)
                        c[i * br + p][j * bc + q] = v * b[p][q];
            }
        return c;
    }

    public static double[][] fromFlat(double[] m) {
        double[][] a = new double[4][4];
        for (int i = 0; i < 16; i++) a[i / 4][i % 4] = m[i];
        return a;
    }

    public static double[] toFlat(double[][] m) {
        double[] f = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) f[i * 4 + j] = m[i][j];
        return f;
    }

    /**
     * Singular value decomposition by one-sided Jacobi rotations.
     * A = U * diag(s) * Vt, with s descending and non-negative.
     */
    public static SVD svd(double[][] matrix) {
        double[][] a = copy(matrix);
        int m = rows(a), n = cols(a);
        double[][] u = a;
        double[][] v = identity(n);
        double[] s = new double[n];

        int maxSweeps = 60;
        double eps = 1e-14;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double alpha = 0, beta = 0, gamma = 0;
                    for (int i = 0; i < m; i++) {
                        alpha += u[i][p] * u[i][p];
                        beta  += u[i][q] * u[i][q];
                        gamma += u[i][p] * u[i][q];
                    }
                    if (Math.abs(gamma) <= eps * Math.sqrt(alpha * beta)) continue;
                    double zeta = (beta - alpha) / (2.0 * gamma);
                    double t = Math.signum(zeta == 0 ? 1 : zeta)
                            / (Math.abs(zeta) + Math.sqrt(1.0 + zeta * zeta));
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double d = t * c;
                    off += Math.abs(gamma);
                    for (int i = 0; i < m; i++) {
                        double up = u[i][p], uq = u[i][q];
                        u[i][p] = c * up - d * uq;
                        u[i][q] = d * up + c * uq;
                    }
                    for (int i = 0; i < n; i++) {
                        double vp = v[i][p], vq = v[i][q];
                        v[i][p] = c * vp - d * vq;
                        v[i][q] = d * vp + c * vq;
                    }
                }
            }
            if (off == 0) break;
        }
        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < m; i++) norm += u[i][j] * u[i][j];
            s[j] = Math.sqrt(norm);
            if (s[j] > 1e-300) {
                double inv = 1.0 / s[j];
                for (int i = 0; i < m; i++) u[i][j] *= inv;
            }
        }
        // bubble-sort columns by descending singular value
        for (int j = 0; j < n - 1; j++) {
            for (int k = 0; k < n - 1 - j; k++) {
                if (s[k] < s[k + 1]) {
                    double ts = s[k]; s[k] = s[k + 1]; s[k + 1] = ts;
                    for (int i = 0; i < m; i++) {
                        double tv = u[i][k]; u[i][k] = u[i][k + 1]; u[i][k + 1] = tv;
                    }
                    for (int i = 0; i < n; i++) {
                        double tv = v[i][k]; v[i][k] = v[i][k + 1]; v[i][k + 1] = tv;
                    }
                }
            }
        }
        return new SVD(u, s, transpose(v));
    }

    /** Eigen-decomposition of a real symmetric matrix via Jacobi rotations. */
    public record SymmetricEigen(double[] values, double[][] vectors) {}

    public static SymmetricEigen jacobiEigen(double[][] mat) {
        int n = mat.length;
        double[][] a = copy(mat);
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (Math.sqrt(2 * off) < 1e-13) break;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double tau = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(tau == 0 ? 1 : tau)
                            / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double ap = a[p][k], aq = a[q][k];
                        a[p][k] = c * ap - s * aq;
                        a[q][k] = s * ap + c * aq;
                    }
                    for (int k = 0; k < n; k++) {
                        double ap = a[k][p], aq = a[k][q];
                        a[k][p] = c * ap - s * aq;
                        a[k][q] = s * ap + c * aq;
                    }
                    for (int k = 0; k < n; k++) {
                        double vp = v[k][p], vq = v[k][q];
                        v[k][p] = c * vp - s * vq;
                        v[k][q] = s * vp + c * vq;
                    }
                }
            }
        }
        double[] eig = new double[n];
        for (int i = 0; i < n; i++) eig[i] = a[i][i];
        for (int i = 0; i < n - 1; i++)
            for (int j = 0; j < n - 1 - i; j++)
                if (eig[j] < eig[j + 1]) {
                    double t = eig[j]; eig[j] = eig[j + 1]; eig[j + 1] = t;
                    for (int k = 0; k < n; k++) {
                        double t2 = v[k][j]; v[k][j] = v[k][j + 1]; v[k][j + 1] = t2;
                    }
                }
        return new SymmetricEigen(eig, v);
    }

    public static double[][] inverse3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2];
        double d = m[1][0], e = m[1][1], f = m[1][2];
        double g = m[2][0], h = m[2][1], i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        double inv = 1.0 / det;
        double[][] r = zeros(3, 3);
        r[0][0] = (e * i - f * h) * inv;
        r[0][1] = (c * h - b * i) * inv;
        r[0][2] = (b * f - c * e) * inv;
        r[1][0] = (f * g - d * i) * inv;
        r[1][1] = (a * i - c * g) * inv;
        r[1][2] = (c * d - a * f) * inv;
        r[2][0] = (d * h - e * g) * inv;
        r[2][1] = (b * g - a * h) * inv;
        r[2][2] = (a * e - b * d) * inv;
        return r;
    }

    public static double determinant3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
             - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
             + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    public static double frobenius(double[][] a) {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return Math.sqrt(s);
    }

    public static double[][] subtract(double[][] a, double[][] b) {
        double[][] c = zeros(rows(a), cols(a));
        for (int i = 0; i < rows(a); i++)
            for (int j = 0; j < cols(a); j++) c[i][j] = a[i][j] - b[i][j];
        return c;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        if (Math.abs(v) >= 1e4 || (Math.abs(v) < 1e-3 && v != 0))
            return String.format("%.3e", v);
        return String.format("%.4f", v);
    }
}
