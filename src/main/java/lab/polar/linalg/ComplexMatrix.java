package lab.polar.linalg;

/** Small dense complex matrix (row-major) with Hermitian eigen support via realification. */
public final class ComplexMatrix {
    public final int rows;
    public final int cols;
    final double[][] re;
    final double[][] im;

    public ComplexMatrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.re = new double[rows][cols];
        this.im = new double[rows][cols];
    }

    public static ComplexMatrix real(double[][] a) {
        ComplexMatrix m = new ComplexMatrix(a.length, a[0].length);
        for (int i = 0; i < a.length; i++)
            System.arraycopy(a[i], 0, m.re[i], 0, a[i].length);
        return m;
    }

    public void set(int r, int c, double real, double imag) {
        re[r][c] = real;
        im[r][c] = imag;
    }

    public double real(int r, int c) { return re[r][c]; }
    public double imag(int r, int c) { return im[r][c]; }

    public ComplexMatrix copy() {
        ComplexMatrix m = new ComplexMatrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            System.arraycopy(re[i], 0, m.re[i], 0, cols);
            System.arraycopy(im[i], 0, m.im[i], 0, cols);
        }
        return m;
    }

    public ComplexMatrix plus(ComplexMatrix o) {
        ComplexMatrix m = new ComplexMatrix(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                m.re[i][j] = re[i][j] + o.re[i][j];
                m.im[i][j] = im[i][j] + o.im[i][j];
            }
        return m;
    }

    public ComplexMatrix scale(double sr, double si) {
        ComplexMatrix m = new ComplexMatrix(rows, cols);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                m.re[i][j] = re[i][j] * sr - im[i][j] * si;
                m.im[i][j] = re[i][j] * si + im[i][j] * sr;
            }
        return m;
    }

    /**
     * Eigen-decomposition of a Hermitian matrix via the 2n x 2n real symmetric block form
     * [[Re H, -Im H],[Im H, Re H]]. Each eigenvalue of H occurs twice; vectors are deduplicated.
     */
    public Linear.HermitianEigen eigen() {
        int n = rows;
        double[][] big = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                big[i][j] = re[i][j];
                big[i][j + n] = -im[i][j];
                big[i + n][j] = im[i][j];
                big[i + n][j + n] = re[i][j];
            }
        Linear.SymmetricEigen se = Linear.symmetricEigen(big);
        double[] vals = new double[n];
        ComplexMatrix vecs = new ComplexMatrix(n, n);
        boolean[] used = new boolean[2 * n];
        int found = 0;
        for (int k = 0; k < 2 * n && found < n; k++) {
            if (used[k]) continue;
            vals[found] = se.values()[k];
            for (int l = k + 1; l < 2 * n; l++) {
                if (!used[l] && Math.abs(se.values()[l] - se.values()[k]) < 1e-9 * Math.max(1, Math.abs(se.values()[k]))) {
                    used[l] = true;
                    break;
                }
            }
            for (int r = 0; r < n; r++) {
                vecs.re[r][found] = se.vectors()[r][k];
                vecs.im[r][found] = se.vectors()[r + n][k];
            }
            used[k] = true;
            found++;
        }
        // re-sort descending (pairs already ordered, be safe)
        Integer[] idx = new Integer[found];
        for (int i = 0; i < found; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(vals[b], vals[a]));
        double[] sv = new double[n];
        ComplexMatrix svm = new ComplexMatrix(n, n);
        for (int i = 0; i < found; i++) {
            sv[i] = vals[idx[i]];
            for (int r = 0; r < n; r++) {
                svm.re[r][i] = vecs.re[r][idx[i]];
                svm.im[r][i] = vecs.im[r][idx[i]];
            }
        }
        return new Linear.HermitianEigen(sv, svm);
    }
}
