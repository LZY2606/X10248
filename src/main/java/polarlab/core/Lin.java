package polarlab.core;

/** Minimal dense linear algebra for Mueller estimation (no external deps). */
public final class Lin {
    private Lin() {}

    /** Kronecker product of two vectors, row-major: kron(a,g)[i*g.length+j] = a[i]*g[j]. */
    public static double[] kron(double[] a, double[] g) {
        double[] out = new double[a.length * g.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < g.length; j++)
                out[i * g.length + j] = a[i] * g[j];
        return out;
    }

    public static double[][] transpose(double[][] m) {
        int r = m.length, c = m[0].length;
        double[][] t = new double[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) t[j][i] = m[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int r = a.length, k = b.length, c = b[0].length;
        double[][] out = new double[r][c];
        for (int i = 0; i < r; i++)
            for (int p = 0; p < k; p++) {
                double av = a[i][p];
                if (av == 0) continue;
                for (int j = 0; j < c; j++) out[i][j] += av * b[p][j];
            }
        return out;
    }

    /** Solve A x = b for symmetric positive definite A via Gaussian elimination with pivoting. */
    public static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-300) throw new ArithmeticException("singular matrix");
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col] / m[col][col];
                if (f == 0) continue;
                for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    /** Inverse of a square matrix via Gauss-Jordan. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] inv = new double[n][n];
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-300) throw new ArithmeticException("singular matrix");
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            double d = m[col][col];
            for (int c = 0; c < 2 * n; c++) m[col][c] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                if (f == 0) continue;
                for (int c = 0; c < 2 * n; c++) m[r][c] -= f * m[col][c];
            }
        }
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, inv[i], 0, n);
        return inv;
    }

    /** Eigenvalues of a real symmetric matrix, ascending, via cyclic Jacobi rotations. */
    public static double[] jacobiEigenvalues(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aIn[i], 0, a[i], 0, n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-30) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
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
        }
        double[] ev = new double[n];
        for (int i = 0; i < n; i++) ev[i] = a[i][i];
        java.util.Arrays.sort(ev);
        return ev;
    }

    /** Condition number estimate of a design matrix W: sqrt(lmax/lmin of W^T W). */
    public static double conditionNumber(double[][] w) {
        double[][] wt = transpose(w);
        double[][] wtw = mul(wt, w);
        double[] ev = jacobiEigenvalues(wtw);
        double lmin = ev[0], lmax = ev[ev.length - 1];
        if (lmin <= 1e-300) return Double.POSITIVE_INFINITY;
        return Math.sqrt(lmax / lmin);
    }
}
