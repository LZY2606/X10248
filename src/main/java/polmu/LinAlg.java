package polmu;

/** Minimal dense linear algebra: solve, eigen (Jacobi), condition number. */
public final class LinAlg {
    private LinAlg() {}

    public static double[][] zeros(int r, int c) { return new double[r][c]; }

    public static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
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
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    /** Gauss-Jordan inverse with partial pivoting. Throws if singular beyond tol. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-14)
                throw new ArithmeticException("singular matrix");
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

    /** Jacobi eigenvalue decomposition of a symmetric matrix. Returns eigenvalues ascending. */
    public static double[] jacobiEigenvalues(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aIn[i], 0, a[i], 0, n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-18) continue;
                    double app = a[p][p], aqq = a[q][q];
                    double theta = 0.5 * Math.atan2(2 * apq, aqq - app);
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
        java.util.Arrays.sort(ev);
        return ev;
    }

    /** Condition number (2-norm) of a (possibly rectangular) matrix via eigen of A^T A. */
    public static double conditionNumber(double[][] a) {
        double[][] ata = mul(transpose(a), a);
        double[] ev = jacobiEigenvalues(ata);
        double max = Math.sqrt(Math.max(ev[ev.length - 1], 0));
        double min = Math.sqrt(Math.max(ev[0], 0));
        if (min == 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    /** Rank estimate via singular values above tolerance. */
    public static int rank(double[][] a, double tol) {
        double[] ev = jacobiEigenvalues(mul(transpose(a), a));
        int r = 0;
        for (double e : ev) if (Math.sqrt(Math.max(e, 0)) > tol) r++;
        return r;
    }
}
