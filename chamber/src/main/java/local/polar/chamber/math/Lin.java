package local.polar.chamber.math;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;
import org.ejml.simple.SimpleMatrix;

import java.util.Locale;

/**
 * Small dense-linear-algebra toolkit for Mueller estimation.
 *
 * <p>Everything is built on an explicit SVD so that near-singular design matrices are
 * never blindly inverted: callers receive singular values, rank and null-space vectors
 * and must decide what to do with them.
 */
public final class Lin {

    private Lin() {
    }

    public static double[][] copy(double[][] a) {
        double[][] out = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i].clone();
        }
        return out;
    }

    public static SimpleMatrix m(double[][] a) {
        return new SimpleMatrix(new DMatrixRMaj(copy(a)));
    }

    public static double[][] arr(SimpleMatrix a) {
        double[][] out = new double[a.numRows()][a.numCols()];
        for (int i = 0; i < a.numRows(); i++) {
            for (int j = 0; j < a.numCols(); j++) {
                out[i][j] = a.get(i, j);
            }
        }
        return out;
    }

    /** Row-major 4x4 matrix from a length-16 vector obtained by stacking rows. */
    public static double[][] vecToM(double[] v) {
        if (v.length != 16) {
            throw new IllegalArgumentException("expected 16 coefficients, got " + v.length);
        }
        double[][] m = new double[4][4];
        for (int r = 0; r < 4; r++) {
            System.arraycopy(v, r * 4, m[r], 0, 4);
        }
        return m;
    }

    public static double[] mToVec(double[][] m) {
        double[] v = new double[16];
        for (int r = 0; r < 4; r++) {
            System.arraycopy(m[r], 0, v, r * 4, 4);
        }
        return v;
    }

    public static double det4(double[][] a) {
        return CommonOps_DDRM.det(new DMatrixRMaj(a));
    }

    public static double frob(double[][] a) {
        double s = 0;
        for (double[] row : a) {
            for (double x : row) {
                s += x * x;
            }
        }
        return Math.sqrt(s);
    }

    public static double[][] residualMatrix(double[][] a, double[][] b) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[i].length; j++) {
                r[i][j] = a[i][j] - b[i][j];
            }
        }
        return r;
    }

    /** Result of an SVD-based linear fit; nullSpace is empty when the design has full column rank. */
    public static final class FitResult {
        public final double[] solution;
        public final double[] singularValues;
        public final int rank;
        public final double cond;
        public final double[][] nullSpace;
        public final double[][] covariance;
        public final double residualNorm;
        public final boolean rankDeficient;

        public FitResult(double[] solution, double[] singularValues, int rank, double cond,
                         double[][] nullSpace, double[][] covariance, double residualNorm,
                         boolean rankDeficient) {
            this.solution = solution;
            this.singularValues = singularValues;
            this.rank = rank;
            this.cond = cond;
            this.nullSpace = nullSpace;
            this.covariance = covariance;
            this.residualNorm = residualNorm;
            this.rankDeficient = rankDeficient;
        }
    }

    /**
     * Solve A x = b by truncated SVD.
     *
     * @param relTol singular value below relTol * sMax is treated as zero (no forced inverse)
     * @param covarianceSigma2 measurement covariance (cov of b); null uses sigma^2 * I
     * @param sigma2 residual variance estimate used when propagating covariance
     */
    public static FitResult svdSolve(double[][] aIn, double[] b, double relTol,
                                     double[][] covarianceSigma2, double sigma2) {
        SimpleMatrix a = m(aIn);
        SimpleMatrix bb = new SimpleMatrix(b.length, 1);
        for (int i = 0; i < b.length; i++) {
            bb.set(i, 0, b[i]);
        }
        SingularValueDecomposition<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(a.numRows(), a.numCols(), true, true, false);
        if (!svd.decompose(a.getMatrix())) {
            throw new IllegalStateException("SVD decomposition failed");
        }
        double[] sv = svd.getSingularValues();
        double sMax = sv[0];
        double sMin = sv[sv.length - 1];
        double cond = sMin > 0 ? sMax / sMin : Double.POSITIVE_INFINITY;
        double cutoff = relTol * sMax;
        int rank = 0;
        for (double v : sv) {
            if (v > cutoff) {
                rank++;
            }
        }
        boolean rankDeficient = rank < a.numCols();

        SimpleMatrix u = SimpleMatrix.wrap(svd.getU(null, false));
        SimpleMatrix v = SimpleMatrix.wrap(svd.getV(null, false));

        double[] x = new double[a.numCols()];
        double[][] cov = new double[a.numCols()][a.numCols()];
        if (covarianceSigma2 == null) {
            // Cov(x) = sigma^2 * V diag(1/s_i^2 for retained, else 0) V^T
            for (int k = 0; k < rank; k++) {
                double inv = 1.0 / sv[k];
                for (int i = 0; i < a.numCols(); i++) {
                    for (int j = 0; j < a.numCols(); j++) {
                        cov[i][j] += sigma2 * v.get(i, k) * v.get(j, k) * inv * inv;
                    }
                }
            }
            for (int k = 0; k < rank; k++) {
                double alpha = u.extractVector(false, k).dot(bb) / sv[k];
                for (int i = 0; i < a.numCols(); i++) {
                    x[i] += v.get(i, k) * alpha;
                }
            }
        } else {
            // Weighted / Gauss-Markov solution: whiten using symmetric square root W with
            // W C W^T = I, then solve W A x = W b and propagate Cov = (A^T C^-1 A)^+ .
            SimpleMatrix w = whitening(covarianceSigma2);
            return svdSolve(arr(w.mult(a)), arr1(w.mult(bb)), relTol, null, sigma2);
        }

        double[][] nullspace = new double[a.numCols() - rank][];
        for (int k = rank; k < a.numCols(); k++) {
            double[] z = new double[a.numCols()];
            for (int i = 0; i < a.numCols(); i++) {
                z[i] = v.get(i, k);
            }
            nullspace[k - rank] = z;
        }
        double[] resid = new double[b.length];
        for (int i = 0; i < b.length; i++) {
            double pred = 0;
            for (int j = 0; j < a.numCols(); j++) {
                pred += aIn[i][j] * x[j];
            }
            resid[i] = b[i] - pred;
        }
        double rn = 0;
        for (double r : resid) {
            rn += r * r;
        }
        return new FitResult(x, sv, rank, cond, nullspace, cov, Math.sqrt(rn), rankDeficient);
    }

    private static double[] arr1(SimpleMatrix col) {
        double[] out = new double[col.numRows()];
        for (int i = 0; i < out.length; i++) {
            out[i] = col.get(i, 0);
        }
        return out;
    }

    /** Symmetric inverse square root via eigendecomposition (C is symmetric positive definite). */
    public static SimpleMatrix whitening(double[][] c) {
        SimpleMatrix cm = m(c);
        var eig = cm.eig();
        SimpleMatrix d = new SimpleMatrix(cm.numRows(), cm.numCols());
        SimpleMatrix eigVec = new SimpleMatrix(cm.numRows(), cm.numCols());
        for (int i = 0; i < cm.numRows(); i++) {
            double lambda = eig.getEigenvalue(i).getReal();
            if (lambda <= 0) {
                throw new IllegalArgumentException(
                        "measurement covariance must be positive definite; eigenvalue "
                                + String.format(Locale.ROOT, "%.3g", lambda));
            }
            d.set(i, i, 1.0 / Math.sqrt(lambda));
            org.ejml.data.Complex_F64 ev = eig.getEigenVector(i);
            for (int j = 0; j < cm.numRows(); j++) {
                eigVec.set(j, i, ev == null ? (i == j ? 1.0 : 0.0) : ev.getReal());
            }
        }
        return eigVec.mult(d).mult(eigVec.transpose());
    }

    /** Inverts a known well-conditioned small matrix; callers must prove conditioning first. */
    public static double[][] invert(double[][] a) {
        SimpleMatrix x = m(a).invert();
        return arr(x);
    }
}
