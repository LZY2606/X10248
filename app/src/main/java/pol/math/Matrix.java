package pol.math;

import java.util.Arrays;

/** Dense real matrix with Jacobi eigendecomposition based SVD and linear algebra. */
public final class Matrix {
    public final int rows, cols;
    public final double[][] a;

    public Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows][cols];
    }

    public Matrix(double[][] data) {
        this.rows = data.length;
        this.cols = data[0].length;
        this.a = new double[rows][cols];
        for (int i = 0; i < rows; i++) this.a[i] = Arrays.copyOf(data[i], cols);
    }

    public static Matrix identity(int n) {
        Matrix m = new Matrix(n, n);
        for (int i = 0; i < n; i++) m.a[i][i] = 1;
        return m;
    }

    public static Matrix zeros(int r, int c) { return new Matrix(r, c); }

    public double get(int i, int j) { return a[i][j]; }
    public void set(int i, int j, double v) { a[i][j] = v; }

    public Matrix copy() { return new Matrix(a); }

    public Matrix transpose() {
        Matrix r = new Matrix(cols, rows);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.a[j][i] = a[i][j];
        return r;
    }

    public Matrix mul(Matrix b) {
        if (cols != b.rows) throw new IllegalArgumentException("shape mismatch");
        Matrix r = new Matrix(rows, b.cols);
        for (int i = 0; i < rows; i++)
            for (int k = 0; k < cols; k++) {
                double aik = a[i][k];
                if (aik == 0) continue;
                for (int j = 0; j < b.cols; j++) r.a[i][j] += aik * b.a[k][j];
            }
        return r;
    }

    public double[] mulVec(double[] x) {
        double[] r = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0;
            for (int j = 0; j < cols; j++) s += a[i][j] * x[j];
            r[i] = s;
        }
        return r;
    }

    public Matrix t() { return transpose(); }

    /** Frobenius norm. */
    public double frob() {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return Math.sqrt(s);
    }

    /** Symmetric eigendecomposition via cyclic Jacobi. eigenvectors are columns of V (A = V diag(w) V^T). */
    public static JacobiEigen symmetricEigen(Matrix A, double tol) {
        int n = A.rows;
        Matrix V = identity(n);
        double[][] m = A.copy().a;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += m[p][q] * m[p][q];
            if (off < tol) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = m[p][q];
                    if (Math.abs(apq) < 1e-18) continue;
                    double app = m[p][p], aqq = m[q][q];
                    double tau = (aqq - app) / (2 * apq);
                    double t;
                    if (tau >= 0) t = 1.0 / (tau + Math.sqrt(1 + tau * tau));
                    else t = -1.0 / (-tau + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    m[p][p] = app - t * apq;
                    m[q][q] = aqq + t * apq;
                    m[p][q] = 0;
                    m[q][p] = 0;
                    for (int k = 0; k < n; k++) {
                        if (k == p || k == q) continue;
                        double akp = m[k][p], akq = m[k][q];
                        m[k][p] = c * akp - s * akq;
                        m[p][k] = m[k][p];
                        m[k][q] = s * akp + c * akq;
                        m[q][k] = m[k][q];
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = V.a[k][p], vkq = V.a[k][q];
                        V.a[k][p] = c * vkp - s * vkq;
                        V.a[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = m[i][i];
        // sort descending
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; idx[i] = i, i++) ;
        Arrays.sort(idx, (x, y) -> Double.compare(w[y], w[x]));
        double[] ws = new double[n];
        Matrix Vs = new Matrix(n, n);
        for (int i = 0; i < n; i++) {
            ws[i] = w[idx[i]];
            for (int k = 0; k < n; k++) Vs.a[k][i] = V.a[k][idx[i]];
        }
        return new JacobiEigen(ws, Vs);
    }

    public static final class JacobiEigen {
        public final double[] values;
        public final Matrix vectors;
        public JacobiEigen(double[] values, Matrix vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /** SVD: A = U diag(s) Vt. */
    public static SVD svd(Matrix A) {
        // B = A^T A, eigenvectors = V, singular values sorted desc.
        Matrix AtA = A.transpose().mul(A);
        JacobiEigen eig = symmetricEigen(AtA, 1e-20);
        int n = A.cols, m = A.rows;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = Math.sqrt(Math.max(0, eig.values[i]));
        Matrix V = eig.vectors;
        Matrix U = new Matrix(m, n);
        for (int i = 0; i < n; i++) {
            if (s[i] > 1e-12) {
                double[] col = new double[n];
                for (int k = 0; k < n; k++) col[k] = V.a[k][i];
                double[] u = A.mulVec(col);
                for (int k = 0; k < m; k++) U.a[k][i] = u[k] / s[i];
            } else {
                // fill null column orthogonally (best effort)
                double[] e = new double[m];
                e[i % m] = 1;
                for (int k = 0; k < m; k++) U.a[k][i] = e[k];
            }
        }
        return new SVD(U, s, V.transpose());
    }

    public static final class SVD {
        public final Matrix U, Vt;
        public final double[] s;
        public SVD(Matrix U, double[] s, Matrix Vt) {
            this.U = U;
            this.s = s;
            this.Vt = Vt;
        }
        public double conditionNumber() {
            double max = 0, min = Double.POSITIVE_INFINITY;
            for (double v : s) {
                if (v < 1e-14) continue;
                max = Math.max(max, v);
                min = Math.min(min, v);
            }
            if (Double.isInfinite(min)) return Double.POSITIVE_INFINITY;
            return max / min;
        }
        public int rank(double tol) {
            double max = 0;
            for (double v : s) max = Math.max(max, v);
            int r = 0;
            for (double v : s) if (v > tol * Math.max(1, max)) r++;
            return r;
        }
        public int nullity(double tol) { return s.length - rank(tol); }
    }

    /** Gauss-Jordan inverse. */
    public Matrix inverse() {
        if (rows != cols) throw new IllegalArgumentException("not square");
        int n = rows;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            double pv = m[pivot][col];
            if (Math.abs(pv) < 1e-14) throw new SingularMatrixException("singular at column " + col);
            double[] tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp;
            for (int j = 0; j < 2 * n; j++) m[col][j] /= pv;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        Matrix inv = new Matrix(n, n);
        for (int i = 0; i < n; i++)
            System.arraycopy(m[i], n, inv.a[i], 0, n);
        return inv;
    }

    /** Solve symmetric positive definite system via Cholesky (used by normal equations). */
    public static double[] solveSPD(double[][] A, double[] b) {
        int n = A.length;
        double[][] L = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = A[i][j];
                for (int k = 0; k < j; k++) sum -= L[i][k] * L[j][k];
                if (i == j) {
                    if (sum <= 1e-14) throw new SingularMatrixException("SPD not positive definite at " + i);
                    L[i][j] = Math.sqrt(sum);
                } else L[i][j] = sum / L[j][j];
            }
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[i];
            for (int k = 0; k < i; k++) sum -= L[i][k] * y[k];
            y[i] = sum / L[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = y[i];
            for (int k = i + 1; k < n; k++) sum -= L[k][i] * x[k];
            x[i] = sum / L[i][i];
        }
        return x;
    }

    public static final class SingularMatrixException extends RuntimeException {
        public SingularMatrixException(String m) { super(m); }
    }
}
