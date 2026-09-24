package polimueller.linalg;

import java.util.Arrays;

/** Minimal dense real matrix utilities for Mueller calculus (no external deps). */
public final class Matrix {
    private Matrix() {}

    public static double[][] of(int rows, int cols) {
        return new double[rows][cols];
    }

    public static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1.0;
        return m;
    }

    public static double[][] copy(double[][] a) {
        double[][] r = new double[a.length][];
        for (int i = 0; i < a.length; i++) r[i] = a[i].clone();
        return r;
    }

    public static double[][] transpose(double[][] a) {
        double[][] r = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[j][i] = a[i][j];
        return r;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        double[][] r = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int t = 0; t < k; t++) {
                double av = a[i][t];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) r[i][j] += av * b[t][j];
            }
        return r;
    }

    public static double[] mul(double[][] a, double[] x) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    public static double[][] add(double[][] a, double[][] b) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] + b[i][j];
        return r;
    }

    public static double[][] sub(double[][] a, double[][] b) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] - b[i][j];
        return r;
    }

    public static double[][] scale(double[][] a, double s) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] * s;
        return r;
    }

    public static class SingularException extends RuntimeException {
        public SingularException(String msg) { super(msg); }
    }

    /**
     * Gauss-Jordan inverse with partial pivoting. Throws SingularException when a
     * pivot falls below {@code pivotTol}; callers must NOT force inversion of
     * near-singular design normal equations.
     */
    public static double[][] inverse(double[][] a, double pivotTol) {
        int n = a.length;
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(aug[r][col]) > Math.abs(aug[piv][col])) piv = r;
            if (Math.abs(aug[piv][col]) < pivotTol)
                throw new SingularException("pivot " + Math.abs(aug[piv][col]) + " < " + pivotTol + " at col " + col);
            double[] tmp = aug[col]; aug[col] = aug[piv]; aug[piv] = tmp;
            double d = aug[col][col];
            for (int j = 0; j < 2 * n; j++) aug[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = aug[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) aug[r][j] -= f * aug[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aug[i], n, inv[i], 0, n);
        return inv;
    }

    /** Jacobi eigenvalue decomposition of a real symmetric matrix. */
    public static final class Eigen {
        public final double[] values;      // descending
        public final double[][] vectors;   // columns correspond to values
        Eigen(double[] values, double[][] vectors) { this.values = values; this.vectors = vectors; }
    }

    public static Eigen jacobiEigen(double[][] aIn) {
        int n = aIn.length;
        double[][] a = copy(aIn);
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-18) continue;
                    double theta = 0.5 * Math.atan2(2 * a[p][q], a[q][q] - a[p][p]);
                    double c = Math.cos(theta), s = Math.sin(theta);
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
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(a[y][y], a[x][x]));
        double[] vals = new double[n];
        double[][] vecs = new double[n][n];
        for (int i = 0; i < n; i++) {
            vals[i] = a[idx[i]][idx[i]];
            for (int k = 0; k < n; k++) vecs[k][i] = v[k][idx[i]];
        }
        return new Eigen(vals, vecs);
    }

    /** Eigenvalues of a Hermitian matrix given as (re, im) 4x4 pairs, via the 8x8 real embedding. */
    public static double[] hermitianEigenvalues(double[][] re, double[][] im) {
        int n = re.length;
        double[][] big = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                big[i][j] = re[i][j];
                big[i][n + j] = -im[i][j];
                big[n + i][j] = im[i][j];
                big[n + i][n + j] = re[i][j];
            }
        double[] all = jacobiEigen(big).values;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = all[2 * i]; // duplicated pairs, descending
        return r;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        return String.format("%.6g", v);
    }
}
