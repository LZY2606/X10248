package polar.core;

import java.util.Arrays;

/** Minimal dense real matrix utilities (no external deps). */
public final class Matrix {
    private Matrix() {}

    public static double[][] multiply(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        double[][] out = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double av = a[i][p];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) out[i][j] += av * b[p][j];
            }
        return out;
    }

    public static double[][] transpose(double[][] a) {
        double[][] out = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) out[j][i] = a[i][j];
        return out;
    }

    public static double[] matVec(double[][] a, double[] x) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) out[i] += a[i][j] * x[j];
        return out;
    }

    /** Gauss-Jordan inverse with partial pivoting. Throws ArithmeticException if singular. */
    public static double[][] inverse(double[][] a) {
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
            if (Math.abs(aug[piv][col]) < 1e-300)
                throw new ArithmeticException("matrix is singular to working precision");
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
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aug[i], n, out[i], 0, n);
        return out;
    }

    /** Jacobi eigenvalues of a real symmetric matrix, descending. */
    public static double[] eigenvaluesSymmetric(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) a[i] = Arrays.copyOf(aIn[i], n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-300) continue;
                    double theta = 0.5 * Math.atan2(2 * apq, a[q][q] - a[p][p]);
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
                }
        }
        double[] ev = new double[n];
        for (int i = 0; i < n; i++) ev[i] = a[i][i];
        Arrays.sort(ev);
        for (int i = 0; i < n / 2; i++) { double t = ev[i]; ev[i] = ev[n - 1 - i]; ev[n - 1 - i] = t; }
        return ev;
    }

    /** Condition number (2-norm) of a symmetric PSD matrix via eigenvalues. */
    public static double conditionNumberSymmetric(double[][] a) {
        double[] ev = eigenvaluesSymmetric(a);
        double max = Math.abs(ev[0]);
        double min = Math.abs(ev[ev.length - 1]);
        if (min < 1e-300) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    public static double[][] identity(int n) {
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) out[i][i] = 1;
        return out;
    }
}
