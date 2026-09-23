package polar.domain;

import java.util.Arrays;

/**
 * Small self-contained linear-algebra helpers for 4x4 polarimetry and the
 * (up to) 16x16 design matrix. No external BLAS: the workspace is offline.
 */
public final class Mat {
    private Mat() {}

    public static double[][] zeros(int r, int c) {
        double[][] m = new double[r][];
        for (int i = 0; i < r; i++) m[i] = new double[c];
        return m;
    }

    public static double[][] copy(double[][] a) {
        double[][] b = new double[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    public static double[][] transpose(double[][] a) {
        double[][] t = zeros(a[0].length, a.length);
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        double[][] c = zeros(a.length, b[0].length);
        for (int i = 0; i < a.length; i++)
            for (int k = 0; k < b.length; k++) {
                double aik = a[i][k];
                for (int j = 0; j < b[0].length; j++) c[i][j] += aik * b[k][j];
            }
        return c;
    }

    public static double[] mul(double[][] a, double[] x) {
        double[] y = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) y[i] += a[i][j] * x[j];
        return y;
    }

    /** Solves A x = b for symmetric positive (semi-)definite A with a pivot floor. */
    public static double[] solveSPD(double[][] a, double[] b, double pivotFloor) {
        int n = a.length;
        double[][] lu = copy(a);
        double[] y = b.clone();
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        for (int k = 0; k < n; k++) {
            int p = k;
            for (int i = k + 1; i < n; i++)
                if (Math.abs(lu[i][k]) > Math.abs(lu[p][k])) p = i;
            if (Math.abs(lu[p][k]) <= pivotFloor) return null;
            if (p != k) {
                double[] tr = lu[p]; lu[p] = lu[k]; lu[k] = tr;
                double td = y[p]; y[p] = y[k]; y[k] = td;
            }
            for (int i = k + 1; i < n; i++) {
                lu[i][k] /= lu[k][k];
                for (int j = k + 1; j < n; j++) lu[i][j] -= lu[i][k] * lu[k][j];
                y[i] -= lu[i][k] * y[k];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = y[i];
            for (int j = i + 1; j < n; j++) s -= lu[i][j] * x[j];
            x[i] = s / lu[i][i];
        }
        return x;
    }

    public static double[][] inverseSPD(double[][] a, double pivotFloor) {
        int n = a.length;
        double[][] inv = zeros(n, n);
        for (int j = 0; j < n; j++) {
            double[] e = new double[n];
            e[j] = 1.0;
            double[] col = solveSPD(a, e, pivotFloor);
            if (col == null) return null;
            for (int i = 0; i < n; i++) inv[i][j] = col[i];
        }
        return inv;
    }

    /**
     * Eigenvalues/eigenvectors of a real symmetric matrix by cyclic Jacobi
     * rotations. Sufficient for the 4x4, 8x8 and 16x16 matrices used here.
     */
    public static JacobiResult jacobi(double[][] src) {
        int n = src.length;
        double[][] a = copy(src);
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-18) continue;
                    double app = a[p][p], aqq = a[q][q];
                    double tau = (aqq - app) / (2 * apq);
                    double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t), s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] eig = new double[n];
        for (int i = 0; i < n; i++) eig[i] = a[i][i];
        return new JacobiResult(eig, v);
    }

    public static double[][] identity(int n) {
        double[][] m = zeros(n, n);
        for (int i = 0; i < n; i++) m[i][i] = 1;
        return m;
    }

    /** Symmetric positive square root of a symmetric matrix via Jacobi. */
    public static double[][] sqrtSymmetric(double[][] s) {
        JacobiResult r = jacobi(s);
        int n = s.length;
        double[][] diag = zeros(n, n);
        for (int i = 0; i < n; i++) diag[i][i] = Math.sqrt(Math.max(0, r.eigenvalues[i]));
        return mul(mul(r.vectors, diag), transpose(r.vectors));
    }

    public static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    public static double[][] inv4(double[][] m) {
        double[][] inv = zeros(4, 4);
        double[] flat = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) flat[i * 4 + j] = m[i][j];
        for (int col = 0; col < 4; col++) {
            double[] b = new double[4];
            b[col] = 1;
            double[] x = solveSPD(new double[][]{
                    row(m, 0), row(m, 1), row(m, 2), row(m, 3)}, b, 1e-18);
            // solveSPD does generic Gaussian elimination with partial pivoting;
            // a 4x4 Mueller factor is always invertible for our cases.
            if (x == null) throw new ArithmeticException("singular 4x4 factor");
            for (int i = 0; i < 4; i++) inv[i][col] = x[i];
        }
        return inv;
    }

    private static double[] row(double[][] m, int i) { return m[i]; }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Math.abs(v) < 1e-12) return "0";
        return String.format(java.util.Locale.ROOT, "%.5g", v);
    }

    public static double[] flattenRowMajor(double[][] m) {
        double[] f = new double[m.length * m[0].length];
        for (int i = 0; i < m.length; i++)
            System.arraycopy(m[i], 0, f, i * m[0].length, m[0].length);
        return f;
    }

    public static double norm(double[] v) {
        double s = 0;
        for (double d : v) s += d * d;
        return Math.sqrt(s);
    }

    public static double trace(double[][] m) {
        double t = 0;
        for (int i = 0; i < Math.min(m.length, m[0].length); i++) t += m[i][i];
        return t;
    }

    public static void requireFinite(double[][] m) {
        for (double[] row : m)
            for (double v : row)
                if (!Double.isFinite(v)) throw new ArithmeticException("non-finite matrix value");
    }

    public static final class JacobiResult {
        public final double[] eigenvalues;
        public final double[][] vectors;
        JacobiResult(double[] e, double[][] v) { eigenvalues = e; vectors = v; }
    }
}
