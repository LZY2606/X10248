package pol.linalg;

/** Minimal dense linear algebra with explicit singularity guards. */
public final class Linear {
    private Linear() {}

    public static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1.0;
        return m;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        if (a[0].length != k) throw new IllegalArgumentException("dim mismatch");
        double[][] r = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double aip = a[i][p];
                if (aip == 0) continue;
                for (int j = 0; j < m; j++) r[i][j] += aip * b[p][j];
            }
        return r;
    }

    public static double[] mulVec(double[][] a, double[] x) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    public static double[][] transpose(double[][] a) {
        double[][] r = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[j][i] = a[i][j];
        return r;
    }

    public static final class SingularException extends RuntimeException {
        public SingularException(String msg) { super(msg); }
    }

    /** Gauss-Jordan inverse with partial pivoting; throws SingularException below pivot tolerance. */
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

    /** Jacobi eigenvalues (ascending) of a symmetric matrix. */
    public static double[] eigenvaluesSymmetric(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i] = a[i].clone();
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += m[p][q] * m[p][q];
            if (off < 1e-30) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                double apq = m[p][q];
                if (Math.abs(apq) < 1e-18) continue;
                double app = m[p][p], aqq = m[q][q];
                double theta = 0.5 * Math.atan2(2 * apq, aqq - app);
                double c = Math.cos(theta), s = Math.sin(theta);
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
            }
        }
        double[] ev = new double[n];
        for (int i = 0; i < n; i++) ev[i] = m[i][i];
        java.util.Arrays.sort(ev);
        return ev;
    }

    public static double frobenius(double[][] a) {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return Math.sqrt(s);
    }
}
