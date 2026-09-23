package polarmueller.domain;

/** Minimal dense linear algebra for 4x4 / 16x16 Mueller calculus. */
public final class LA {
    private LA() {}

    public static double[][] mat(int r, int c) { return new double[r][c]; }

    public static double[][] transpose(double[][] a) {
        double[][] t = mat(a[0].length, a.length);
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = b[0].length;
        double[][] r = mat(n, m);
        for (int i = 0; i < n; i++)
            for (int p = 0; p < k; p++) {
                double av = a[i][p];
                if (av == 0) continue;
                for (int j = 0; j < m; j++) r[i][j] += av * b[p][j];
            }
        return r;
    }

    public static double[] mulVec(double[][] a, double[] x) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    public static double[][] identity(int n) {
        double[][] r = mat(n, n);
        for (int i = 0; i < n; i++) r[i][i] = 1.0;
        return r;
    }

    /** Gauss-Jordan with partial pivoting; throws ArithmeticException when (near-)singular. */
    public static double[][] inverse(double[][] a, double pivotEps) {
        int n = a.length;
        double[][] aug = mat(n, 2 * n);
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(aug[r][col]) > Math.abs(aug[piv][col])) piv = r;
            if (Math.abs(aug[piv][col]) < pivotEps)
                throw new ArithmeticException("singular matrix: pivot " + Math.abs(aug[piv][col]) + " at col " + col);
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
        double[][] inv = mat(n, n);
        for (int i = 0; i < n; i++) System.arraycopy(aug[i], n, inv[i], 0, n);
        return inv;
    }

    /** Cyclic Jacobi eigenvalues of a real symmetric matrix, ascending. */
    public static double[] eigSym(double[][] a) {
        return eigSymDecomp(a).values;
    }

    public static EigDecomp eigSymDecomp(double[][] a) {
        int n = a.length;
        double[][] m = mat(n, n);
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        double[][] v = identity(n);
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += m[p][q] * m[p][q];
            if (Math.sqrt(off) < 1e-14) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(m[p][q]) < 1e-300) continue;
                    double theta = 0.5 * Math.atan2(2 * m[p][q], m[q][q] - m[p][p]);
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
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) vals[i] = m[i][i];
        // sort ascending, carrying eigenvectors
        for (int i = 0; i < n - 1; i++)
            for (int j = i + 1; j < n; j++)
                if (vals[j] < vals[i]) {
                    double t = vals[i]; vals[i] = vals[j]; vals[j] = t;
                    for (int k = 0; k < n; k++) { double tv = v[k][i]; v[k][i] = v[k][j]; v[k][j] = tv; }
                }
        return new EigDecomp(vals, v);
    }

    public static final class EigDecomp {
        public final double[] values;      // ascending
        public final double[][] vectors;   // columns
        public EigDecomp(double[] values, double[][] vectors) { this.values = values; this.vectors = vectors; }
    }

    /** Condition number of a symmetric PSD normal-equations matrix: sqrt(lmax/lmin). */
    public static double condSym(double[][] wtw) {
        double[] ev = eigSym(wtw);
        double lmin = ev[0], lmax = ev[ev.length - 1];
        if (lmax <= 0) return Double.POSITIVE_INFINITY;
        if (lmin <= 0) return Double.POSITIVE_INFINITY;
        return Math.sqrt(lmax / lmin);
    }

    /** Symmetric PSD matrix square root via eigendecomposition. */
    public static double[][] sqrtSym(double[][] a) {
        EigDecomp d = eigSymDecomp(a);
        int n = a.length;
        double[][] r = mat(n, n);
        for (int k = 0; k < n; k++) {
            double s = Math.sqrt(Math.max(0, d.values[k]));
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) r[i][j] += s * d.vectors[i][k] * d.vectors[j][k];
        }
        return r;
    }

    public static double trace(double[][] a) {
        double t = 0;
        for (int i = 0; i < a.length; i++) t += a[i][i];
        return t;
    }
}
