package com.polarlab.math;

import java.util.ArrayList;
import java.util.List;

/**
 * Small-matrix numerical routines implemented explicitly (no external BLAS):
 * one-sided Jacobi SVD, Hermitian eigen-decomposition, Cholesky, Gaussian solve.
 * Deterministic and dependency free so fingerprinted polarimetry results are reproducible.
 */
public final class Linalg {

    public static final class SVD {
        public final double[] s;      // singular values, descending
        public final double[][] u;    // m x k
        public final double[][] vt;   // k x n
        public final int m, n, k;

        SVD(int m, int n, double[] s, double[][] u, double[][] vt) {
            this.m = m;
            this.n = n;
            this.k = s.length;
            this.s = s;
            this.u = u;
            this.vt = vt;
        }

        public double smax() { return s[0]; }

        public double sminPositive(double tol) {
            double cutoff = tol * s[0];
            double min = Double.POSITIVE_INFINITY;
            for (double v : s) if (v > cutoff) min = Math.min(min, v);
            return min;
        }

        public int rank(double tol) {
            double cutoff = tol * s[0];
            int r = 0;
            for (double v : s) if (v > cutoff) r++;
            return r;
        }

        /** Columns of V whose singular value is at/below the cutoff -> right null space. */
        public List<double[]> nullspace(double tol) {
            double cutoff = tol * s[0];
            List<double[]> out = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                if (s[i] <= cutoff) {
                    double[] v = new double[n];
                    for (int j = 0; j < n; j++) v[j] = vt[i][j];
                    out.add(v);
                }
            }
            return out;
        }
    }

    /** One-sided Jacobi SVD. Works for any m x n with n <= ~30 (our designs are 16-wide). */
    public static SVD svd(Mat aIn) {
        int m = aIn.rows, n = aIn.cols;
        double[][] a = aIn.toArray();
        double[][] v = Mat.identity(n).toArray();
        int maxSweeps = 80;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            boolean rotated = false;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double alpha = 0, beta = 0, gamma = 0;
                    for (int i = 0; i < m; i++) {
                        alpha += a[i][p] * a[i][p];
                        beta += a[i][q] * a[i][q];
                        gamma += a[i][p] * a[i][q];
                    }
                    if (alpha == 0 && beta == 0) continue;
                    if (Math.abs(gamma) <= 1e-15 * Math.sqrt(alpha * beta)) continue;
                    double zeta = (alpha - beta) / (2 * gamma);
                    double t = (zeta >= 0 ? 1 : -1) / (Math.abs(zeta) + Math.sqrt(1 + zeta * zeta));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    for (int i = 0; i < m; i++) {
                        double ap = a[i][p], aq = a[i][q];
                        a[i][p] = c * ap - s * aq;
                        a[i][q] = s * ap + c * aq;
                    }
                    for (int i = 0; i < n; i++) {
                        double vp = v[i][p], vq = v[i][q];
                        v[i][p] = c * vp - s * vq;
                        v[i][q] = s * vp + c * vq;
                    }
                    rotated = true;
                }
            }
            if (!rotated) break;
        }
        int k = Math.min(m, n);
        // gather (column norm, column index) pairs and sort by descending singular value
        double[][] pairs = new double[n][2];
        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < m; i++) norm += a[i][j] * a[i][j];
            pairs[j][0] = Math.sqrt(norm);
            pairs[j][1] = j;
        }
        java.util.Arrays.sort(pairs, (x, y) -> Double.compare(y[0], x[0]));
        double[] ss = new double[k];
        double[][] u = new double[m][k];
        double[][] vt = new double[k][n];
        for (int i = 0; i < k; i++) {
            int col = (int) pairs[i][1];
            ss[i] = pairs[i][0];
            for (int r = 0; r < n; r++) vt[i][r] = v[r][col];
            if (ss[i] > 0) for (int r = 0; r < m; r++) u[r][i] = a[r][col] / ss[i];
        }
        return new SVD(m, n, ss, u, vt);
    }

    /** Minimum-norm least squares x for W x = b with full diagnostics. */
    public static final class LSResult {
        public final double[] x;
        public final int rank;
        public final int nullDim;
        public final double cond;          // +Inf when rank deficient
        public final double sminPositive;
        public final List<double[]> nullspace;
        public final double residualNorm;

        LSResult(double[] x, int rank, int nullDim, double cond, double smin,
                 List<double[]> ns, double res) {
            this.x = x;
            this.rank = rank;
            this.nullDim = nullDim;
            this.cond = cond;
            this.sminPositive = smin;
            this.nullspace = ns;
            this.residualNorm = res;
        }
    }

    public static LSResult leastSquares(Mat w, double[] b, double tol) {
        SVD svd = svd(w);
        int rank = svd.rank(tol);
        double[] x = new double[w.cols];
        // x = V S^+ U^T b
        for (int i = 0; i < rank; i++) {
            double utb = 0;
            for (int r = 0; r < w.rows; r++) utb += svd.u[r][i] * b[r];
            double coef = utb / svd.s[i];
            for (int j = 0; j < w.cols; j++) x[j] += coef * svd.vt[i][j];
        }
        double[] wb = w.mul(x);
        double res = 0;
        for (int i = 0; i < b.length; i++) res += (b[i] - wb[i]) * (b[i] - wb[i]);
        double smin = rank == 0 ? Double.POSITIVE_INFINITY : svd.s[rank - 1];
        double cond = rank < w.cols ? Double.POSITIVE_INFINITY : svd.s[0] / svd.s[rank - 1];
        return new LSResult(x, rank, w.cols - rank, cond, smin, svd.nullspace(tol), Math.sqrt(res));
    }

    /** Lower-triangular Cholesky L with C = L L^T. Throws if not positive definite. */
    public static double[][] cholesky(double[][] c) {
        int n = c.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = c[i][j];
                for (int p = 0; p < j; p++) sum -= l[i][p] * l[j][p];
                if (i == j) {
                    if (sum <= 0) throw new IllegalArgumentException("covariance not positive definite at " + i);
                    l[i][j] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    /** Solve L y = b (forward) or L^T y = b (backward). */
    public static double[] triSolve(double[][] l, double[] b, boolean forward) {
        int n = b.length;
        double[] x = new double[n];
        for (int ii = 0; ii < n; ii++) {
            int i = forward ? ii : n - 1 - ii;
            double sum = b[i];
            for (int j = 0; j < n; j++) {
                if (forward && j < i) sum -= l[i][j] * x[j];
                if (!forward && j > i) sum -= l[j][i] * x[j];
            }
            double d = forward ? l[i][i] : l[i][i];
            x[i] = sum / d;
        }
        return x;
    }

    /** Gaussian elimination solve A x = b for a general square matrix. */
    public static double[] solve(Mat aIn, double[] bIn) {
        int n = aIn.rows;
        double[][] a = aIn.toArray();
        double[] b = bIn.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            if (Math.abs(a[piv][col]) < 1e-14) throw new IllegalArgumentException("singular solve");
            double[] tmp = a[piv]; a[piv] = a[col]; a[col] = tmp;
            double tb = b[piv]; b[piv] = b[col]; b[col] = tb;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) a[r][c] -= f * a[col][c];
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = b[i] / a[i][i];
        return x;
    }

    public static Mat inverse(Mat a) {
        int n = a.rows;
        Mat out = new Mat(n, n);
        for (int j = 0; j < n; j++) {
            double[] e = new double[n];
            e[j] = 1;
            double[] col = solve(a, e);
            for (int i = 0; i < n; i++) out.a[i][j] = col[i];
        }
        return out;
    }

    public static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
             - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
             + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
}
