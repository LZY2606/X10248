package org.polarroom.core;

import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal linear-algebra layer used by the Mueller estimation pipeline.
 *
 * <p>The implementation deliberately keeps SVD-based decisions explicit: rank
 * is never silently rounded away, and callers receive the singular values so
 * that near-singular designs can be flagged instead of force-inverted.
 */
public final class LinAlg {

    public static final double EPS = 1e-12;

    private LinAlg() {
    }

    public static double[][] identity(int n) {
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++) {
            r[i][i] = 1.0;
        }
        return r;
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        return MatrixUtils.createRealMatrix(a).multiply(MatrixUtils.createRealMatrix(b)).getData();
    }

    public static double[] multiply(double[][] a, double[] x) {
        return MatrixUtils.createRealMatrix(a).operate(x);
    }

    public static double[][] transpose(double[][] a) {
        return MatrixUtils.createRealMatrix(a).transpose().getData();
    }

    public static double[][] inverse(double[][] a) {
        return MatrixUtils.inverse(MatrixUtils.createRealMatrix(a)).getData();
    }

    /** Solve A x = b for symmetric positive definite A via Cholesky. */
    public static double[] solveSpd(double[][] a, double[] b) {
        CholeskyDecomposition chol =
                new CholeskyDecomposition(MatrixUtils.createRealMatrix(a), EPS, EPS);
        return chol.getSolver()
                .solve(MatrixUtils.createRealVector(b)).toArray();
    }

    public static double[][] inverseSpd(double[][] a) {
        CholeskyDecomposition chol =
                new CholeskyDecomposition(MatrixUtils.createRealMatrix(a), EPS, EPS);
        return chol.getSolver().getInverse().getData();
    }

    /** Full SVD result with explicit singular spectrum. */
    public static final class Svd {
        public final double[][] u;
        public final double[] s;
        public final double[][] vt;

        public Svd(double[][] u, double[] s, double[][] vt) {
            this.u = u;
            this.s = s;
            this.vt = vt;
        }

        /** Numerical rank using a relative threshold on singular values. */
        public int rank(double relTol) {
            double smax = s[0];
            int r = 0;
            for (double v : s) {
                if (v > relTol * smax) {
                    r++;
                }
            }
            return r;
        }

        public double condition() {
            double smin = s[s.length - 1];
            if (smin <= 0.0) {
                return Double.POSITIVE_INFINITY;
            }
            return s[0] / smin;
        }

        /** Columns of V corresponding to zero (below threshold) singular values. */
        public List<double[]> nullspace(double relTol) {
            List<double[]> n = new ArrayList<>();
            double[][] v = transpose(vt);
            for (int i = 0; i < s.length; i++) {
                if (s[i] <= relTol * s[0]) {
                    n.add(column(v, i));
                }
            }
            return n;
        }
    }

    public static Svd svd(double[][] a) {
        SingularValueDecomposition d =
                new SingularValueDecomposition(MatrixUtils.createRealMatrix(a));
        return new Svd(d.getU().getData(), d.getSingularValues(), d.getVT().getData());
    }

    private static double[] column(double[][] m, int j) {
        double[] c = new double[m.length];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i][j];
        }
        return c;
    }

    /**
     * Eigenvalues of a Hermitian matrix supplied as real and imaginary parts.
     *
     * <p>The 4x4 Hermitian H is embedded into the 8x8 real symmetric matrix
     * [[Re H, -Im H], [Im H, Re H]], whose spectrum contains every eigenvalue of
     * H twice; the four distinct values are returned in ascending order.
     */
    public static double[] eigHermitian(double[][] re, double[][] im) {
        int n = re.length;
        RealMatrix big = MatrixUtils.createRealMatrix(2 * n, 2 * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                big.setEntry(i, j, re[i][j]);
                big.setEntry(n + i, n + j, re[i][j]);
                big.setEntry(i, n + j, -im[i][j]);
                big.setEntry(n + i, j, im[i][j]);
            }
        }
        double[] all = new EigenDecomposition(big).getRealEigenvalues();
        java.util.Arrays.sort(all);
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            out[k] = all[2 * k + 1];
        }
        return out;
    }

    /**
     * Polar decomposition of a 3x3 matrix: m = O * P with O orthogonal and
     * P symmetric positive semi-definite (SVD based).
     */
    public static void polarDecomposition3(double[][] m, double[][] oOut, double[][] pOut) {
        Svd svd = svd(m);
        double[][] v = transpose(svd.vt);
        double[][] o = multiply(svd.u, svd.vt);
        if (determinant3(o) < 0) {
            // Reflection case: flip the smallest singular direction so O is a rotation.
            for (int i = 0; i < 3; i++) {
                v[i][2] = -v[i][2];
                double[] row = svd.u[i];
                row[2] = -row[2];
            }
            o = multiply(svd.u, transpose(v));
        }
        double[][] s = new double[3][3];
        for (int i = 0; i < 3; i++) {
            s[i][i] = svd.s[i];
        }
        double[][] p = multiply(multiply(v, s), transpose(v));
        copy3(o, oOut);
        copy3(p, pOut);
    }

    private static void copy3(double[][] src, double[][] dst) {
        for (int i = 0; i < 3; i++) {
            System.arraycopy(src[i], 0, dst[i], 0, 3);
        }
    }

    public static double determinant3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
