package polar.domain;

import java.util.Arrays;

/** Minimal dense linear algebra: symmetric Jacobi eigensolver, products, inverses. */
public final class Linalg {
    private Linalg() {}

    public static double[][] transpose(double[][] a) {
        int n = a.length, m = a[0].length;
        double[][] t = new double[m][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        double[][] r = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double av = a[i][p];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) r[i][j] += av * b[p][j];
            }
        return r;
    }

    public static double[] mul(double[][] a, double[] x) {
        int n = a.length, k = x.length;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) for (int j = 0; j < k; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    /** Eigendecomposition of a real symmetric matrix (Jacobi rotations). */
    public static final class Eigen {
        public final double[] values;   // ascending
        public final double[][] vectors; // columns match values
        Eigen(double[] v, double[][] vec) { values = v; vectors = vec; }
    }

    public static Eigen eigenSym(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1.0;
        for (int sweep = 0; sweep < 200; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += m[p][q] * m[p][q];
            if (off < 1e-28) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                double apq = m[p][q];
                if (Math.abs(apq) < 1e-300) continue;
                double app = m[p][p], aqq = m[q][q];
                double tau = (aqq - app) / (2.0 * apq);
                double t = (tau >= 0 ? 1.0 : -1.0) / (Math.abs(tau) + Math.sqrt(1.0 + tau * tau));
                double c = 1.0 / Math.sqrt(1.0 + t * t), s = t * c;
                for (int k = 0; k < n; k++) {
                    double mkp = m[k][p], mkq = m[k][q];
                    m[k][p] = c * mkp - s * mkq;
                    m[k][q] = s * mkp + c * mkq;
                }
                for (int k = 0; k < n; k++) {
                    double mpk = m[p][k], mqk = m[q][k];
                    m[p][k] = c * mpk - s * mqk;
                    m[q][k] = s * mpk + c * mqk;
                }
                for (int k = 0; k < n; k++) {
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = c * vkp - s * vkq;
                    v[k][q] = s * vkp + c * vkq;
                }
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(m[x][x], m[y][y]));
        double[] vals = new double[n];
        double[][] vecs = new double[n][n];
        for (int i = 0; i < n; i++) {
            vals[i] = m[order[i]][order[i]];
            for (int k = 0; k < n; k++) vecs[k][i] = v[k][order[i]];
        }
        return new Eigen(vals, vecs);
    }

    /** Condition number (2-norm) of a symmetric PSD matrix; Infinity when (near-)singular. */
    public static double condSym(double[] eigenvaluesAscending) {
        double max = 0, min = Double.POSITIVE_INFINITY;
        for (double e : eigenvaluesAscending) {
            max = Math.max(max, Math.abs(e));
            if (Math.abs(e) > 1e-14 * Math.max(1.0, max)) min = Math.min(min, Math.abs(e));
        }
        if (min == Double.POSITIVE_INFINITY || min == 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    /** Inverse of a symmetric matrix via eigendecomposition; throws on (near-)singularity. */
    public static double[][] inverseSym(double[][] a, double rankTol) {
        Eigen eg = eigenSym(a);
        int n = a.length;
        double max = Math.abs(eg.values[n - 1]);
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            double lam = eg.values[i];
            if (Math.abs(lam) <= rankTol * Math.max(1.0, max))
                throw new ArithmeticException("near-singular matrix, inversion refused");
            double w = 1.0 / lam;
            for (int r = 0; r < n; r++) for (int c = 0; c < n; c++)
                inv[r][c] += w * eg.vectors[r][i] * eg.vectors[c][i];
        }
        return inv;
    }

    /** General inverse via Gauss-Jordan with partial pivoting. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-14) throw new ArithmeticException("singular matrix");
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            double d = m[col][col];
            for (int j = 0; j < 2 * n; j++) m[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, inv[i], 0, n);
        return inv;
    }

    public static double frobenius(double[][] a) {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return Math.sqrt(s);
    }
}
