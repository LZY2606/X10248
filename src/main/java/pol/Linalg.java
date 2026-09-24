package pol;

/** Minimal dense linear algebra: enough for 16x16 Mueller estimation. */
public final class Linalg {
    private Linalg() {}

    public static double[][] zeros(int r, int c) { return new double[r][c]; }

    public static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1.0;
        return m;
    }

    public static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
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

    public static double[] mulVec(double[][] a, double[] x) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) out[i] += a[i][j] * x[j];
        return out;
    }

    /** Inverse via Gauss-Jordan with partial pivoting. Throws SingularException if pivot too small. */
    public static double[][] inverse(double[][] a) {
        int n = a.length;
        double[][] aug = new double[n][2 * n];
        double maxAbs = 0;
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
            for (int j = 0; j < n; j++) maxAbs = Math.max(maxAbs, Math.abs(a[i][j]));
        }
        double tol = maxAbs * 1e-14;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(aug[r][col]) > Math.abs(aug[piv][col])) piv = r;
            if (Math.abs(aug[piv][col]) <= tol) throw new SingularException("pivot " + col + " ~ 0");
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

    public static class SingularException extends RuntimeException {
        public SingularException(String msg) { super(msg); }
    }

    /** Jacobi eigenvalue for symmetric matrices. Returns eigenvalues ascending; vectors in columns of outV if non-null. */
    public static double[] jacobiEigen(double[][] a, double[][] outV) {
        int n = a.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += m[p][q] * m[p][q];
            if (off < 1e-24) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
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
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = m[i][i];
        // sort ascending, permute columns of v
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, java.util.Comparator.comparingDouble(i -> w[i]));
        double[] sorted = new double[n];
        double[][] vs = new double[n][n];
        for (int i = 0; i < n; i++) {
            sorted[i] = w[idx[i]];
            for (int r = 0; r < n; r++) vs[r][i] = v[r][idx[i]];
        }
        if (outV != null) {
            for (int r = 0; r < n; r++) System.arraycopy(vs[r], 0, outV[r], 0, n);
        }
        return sorted;
    }

    /** 2-norm condition number estimate via eigen of A^T A. */
    public static double conditionNumber(double[][] a) {
        double[][] ata = mul(transpose(a), a);
        double[] w = jacobiEigen(ata, null);
        double max = 0, min = Double.POSITIVE_INFINITY;
        for (double v : w) {
            max = Math.max(max, v);
            if (v > 0) min = Math.min(min, v); else min = Math.min(min, 0);
        }
        if (min <= 0) return Double.POSITIVE_INFINITY;
        return Math.sqrt(max / min);
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        return String.format(java.util.Locale.ROOT, "%.6g", v);
    }
}
