package polmu;

/** Minimal dense linear algebra for small symmetric/general systems (no external deps). */
public final class LinAlg {
    private LinAlg() {}

    public static final class Singular extends RuntimeException {
        public Singular(String msg) { super(msg); }
    }

    public static double[][] transpose(double[][] a) {
        int n = a.length, m = a[0].length;
        double[][] t = new double[m][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        double[][] c = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double av = a[i][p];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) c[i][j] += av * b[p][j];
            }
        return c;
    }

    public static double[] mulVec(double[][] a, double[] x) {
        double[] y = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) y[i] += a[i][j] * x[j];
        return y;
    }

    /** Gauss-Jordan inverse with partial pivoting. Throws Singular when pivot is degenerate. */
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
            if (Math.abs(aug[piv][col]) < 1e-14)
                throw new Singular("degenerate pivot at column " + col);
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

    /** Eigenvalues of a real symmetric matrix via cyclic Jacobi sweeps. */
    public static double[] jacobiEigenvalues(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aIn[i], 0, a[i], 0, n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                if (Math.abs(a[p][q]) < 1e-300) continue;
                double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                double c = 1 / Math.sqrt(t * t + 1), s = t * c;
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
        java.util.Arrays.sort(ev);
        return ev;
    }
}
