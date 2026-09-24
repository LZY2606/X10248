package polmu.linalg;

/** Minimal dense linear algebra: symmetric Jacobi eigensolver, LU solve, condition numbers. */
public final class Lin {
    private Lin() {}

    public static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1.0;
        return m;
    }

    public static double[][] transpose(double[][] a) {
        int r = a.length, c = a[0].length;
        double[][] t = new double[c][r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) t[j][i] = a[i][j];
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

    public static double[] mul(double[][] a, double[] x) {
        int r = a.length, c = a[0].length;
        double[] out = new double[r];
        for (int i = 0; i < r; i++) for (int j = 0; j < c; j++) out[i] += a[i][j] * x[j];
        return out;
    }

    /** Solve A x = b by Gaussian elimination with partial pivoting. Throws if singular. */
    public static double[] solve(double[][] a, double[] b) {
        int n = a.length;
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
                for (int j = col; j <= n; j++) m[r][j] -= f * m[col][j];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] inv = new double[n][n];
        for (int j = 0; j < n; j++) {
            double[] e = new double[n];
            e[j] = 1.0;
            double[] col = solve(a, e);
            for (int i = 0; i < n; i++) inv[i][j] = col[i];
        }
        return inv;
    }

    /** Jacobi eigenvalues of a real symmetric matrix, ascending order. */
    public static double[] eigenvaluesSymmetric(double[][] aIn) {
        int n = aIn.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(aIn[i], 0, a[i], 0, n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                double apq = a[p][q];
                if (Math.abs(apq) < 1e-300) continue;
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
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = a[i][i];
        java.util.Arrays.sort(d);
        return d;
    }

    /** Condition number (2-norm) of a symmetric positive semi-definite matrix. */
    public static double conditionNumberSym(double[][] a) {
        double[] ev = eigenvaluesSymmetric(a);
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (double v : ev) { min = Math.min(min, Math.abs(v)); max = Math.max(max, Math.abs(v)); }
        if (min == 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }
}
