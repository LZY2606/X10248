package com.polarlab;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import org.apache.commons.math3.linear.SingularMatrixException;

/** Minimal 4x4 linear-algebra support used by the Mueller workbench. */
final class Mat {
    private Mat() {}

    static RealMatrix m(double[][] a) {
        return new Array2DRowRealMatrix(a, true);
    }

    static RealMatrix zeros(int r, int c) {
        return new Array2DRowRealMatrix(r, c);
    }

    static RealMatrix identity(int n) {
        return MatrixUtils.createRealIdentityMatrix(n);
    }

    /** SVD record: singular values descending, rank at tolerance, condition (largest/smallest). */
    record Svd(double[] s, int rank, double cond, RealMatrix u, RealMatrix v) {
        double smallestPositive() { return s[rank - 1]; }
        double largest() { return s[0]; }
        RealMatrix nullspace(int dim) { return v.getSubMatrix(0, 3, rank, rank + dim - 1); }
    }

    static Svd svd(RealMatrix a, double tol) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] sv = svd.getSingularValues();
        double threshold = Math.max(a.getRowDimension(), a.getColumnDimension()) * sv[0] * tol;
        int rank = 0;
        for (double v : sv) {
            if (v > threshold) {
                rank++;
            }
        }
        double cond = sv[sv.length - 1] == 0 ? Double.POSITIVE_INFINITY : sv[0] / sv[sv.length - 1];
        return new Svd(sv, rank, cond, svd.getU(), svd.getV());
    }

    static RealMatrix transpose(RealMatrix a) {
        return a.transpose();
    }

    static RealMatrix mul(RealMatrix a, RealMatrix b) {
        return a.multiply(b);
    }

    static double[] mul(RealMatrix a, double[] x) {
        return a.operate(x);
    }

    /**
     * Weighted least squares. Only invoked when X has full column rank; callers guard rank.
     * Returns [x, Cov(x)] packed together.
     */
    record Wls(double[] x, RealMatrix cov) {}

    static Wls wlsFullRank(RealMatrix x, double[] y, RealMatrix w) {
        RealMatrix xt = x.transpose();
        RealMatrix xtw = xt.multiply(w);
        RealMatrix a = xtw.multiply(x);
        RealMatrix b = xtw.multiply(new Array2DRowRealMatrix(y));
        // a is SPD when X full rank and W SPD: Cholesky path through solve throws otherwise.
        RealMatrix aInv = inverseSpd(a);
        RealMatrix xm = aInv.multiply(b);
        // Under GLS with W = Cov(y)^{-1}, Cov(x_hat) = (X^T W X)^{-1}.
        return new Wls(xm.getColumn(0), aInv);
    }

    /** Inverse that fails loudly instead of "succeeding" on (near) singular matrices. */
    static RealMatrix inverseSpd(RealMatrix a) {
        try {
            return new org.apache.commons.math3.linear.CholeskyDecomposition(a).getSolver().getInverse();
        } catch (SingularMatrixException e) {
            throw new IllegalArgumentException("matrix is singular; refusing to invert", e);
        }
    }

    static RealMatrix inverseFullRank(RealMatrix a) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        double tol = Math.max(a.getRowDimension(), a.getColumnDimension()) * s[0] * 1e-12;
        if (s[s.length - 1] <= tol) {
            throw new IllegalArgumentException("matrix is rank deficient; refusing to invert");
        }
        return svd.getSolver().getInverse();
    }

    /** Moore-Penrose pseudoinverse is allowed ONLY to report the minimum-norm point, never as "the" inverse. */
    static RealMatrix pseudoInverse(RealMatrix a) {
        return new org.apache.commons.math3.linear.SingularValueDecomposition(a).getSolver().getInverse();
    }

    /** Eigenvalues of a Hermitian complex matrix via the doubled real 8x8 symmetric form. */
    static double[] eigHermitian(Complex[][] h) {
        int n = h.length;
        RealMatrix big = zeros(2 * n, 2 * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Complex z = h[i][j];
                big.setEntry(i, j, z.getReal());
                big.setEntry(n + i, n + j, z.getReal());
                big.setEntry(i, n + j, -z.getImaginary());
                big.setEntry(n + i, j, z.getImaginary());
            }
        }
        double[] all = new EigenDecomposition(big).getRealEigenvalues();
        java.util.Arrays.sort(all);
        // The doubled real form of a Hermitian matrix repeats every eigenvalue twice.
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = all[2 * i + 1];
        }
        return out;
    }
}
