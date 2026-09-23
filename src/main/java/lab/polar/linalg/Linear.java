package lab.polar.linalg;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.Norm_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;
import org.ejml.simple.SimpleMatrix;

/** Thin linear-algebra facade over EJML with stable 2D-array I/O. */
public final class Linear {
    private Linear() {}

    public static double[][] kron(double[][] a, double[][] b) {
        SimpleMatrix A = new SimpleMatrix(a);
        SimpleMatrix B = new SimpleMatrix(b);
        SimpleMatrix K = A.kron(B);
        return toArrays(K.getMatrix());
    }

    public static double[][] transpose(double[][] a) {
        DMatrixRMaj A = new DMatrixRMaj(a);
        DMatrixRMaj T = new DMatrixRMaj(A.numCols, A.numRows);
        CommonOps_DDRM.transpose(A, T);
        return toArrays(T);
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        return toArrays(SimpleMatrix.wrap(new DMatrixRMaj(a)).mult(new SimpleMatrix(b)).getMatrix());
    }

    public static double[] multiply(double[][] a, double[] x) {
        DMatrixRMaj A = new DMatrixRMaj(a);
        DMatrixRMaj X = DMatrixRMaj.wrap(x.length, 1, x);
        DMatrixRMaj Y = new DMatrixRMaj(A.numRows, 1);
        CommonOps_DDRM.mult(A, X, Y);
        return Y.data.clone();
    }

    public static double[][] inverse(double[][] a) {
        SimpleMatrix A = new SimpleMatrix(a);
        if (CommonOps_DDRM.det(A.getMatrix()) == 0.0) {
            throw new ArithmeticException("Matrix is singular; inverse not available");
        }
        return toArrays(A.invert().getMatrix());
    }

    public static double determinant(double[][] a) {
        return CommonOps_DDRM.det(new DMatrixRMaj(a));
    }

    /** Solve X b = y by SVD; reports rank and condition number of X. */
    public static SvdResult solveSvd(double[][] x, double[] y, double rankTol) {
        DMatrixRMaj X = new DMatrixRMaj(x);
        DMatrixRMaj Y = DMatrixRMaj.wrap(y.length, 1, y);
        SingularValueDecomposition<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(X.numRows, X.numCols, true, true, false);
        if (!svd.decompose(X.copy())) {
            throw new ArithmeticException("SVD decomposition failed");
        }
        int n = Math.min(X.numRows, X.numCols);
        double[] sv = new double[n];
        double smax = 0;
        for (int i = 0; i < n; i++) {
            sv[i] = svd.getSingularValue(i);
            smax = Math.max(smax, sv[i]);
        }
        double tol = rankTol * smax;
        int rank = 0;
        for (double v : sv) if (v > tol) rank++;
        double smin = sv[n - 1] <= 0 ? 0.0 : sv[n - 1];
        double cond = smin <= 0 ? Double.POSITIVE_INFINITY : smax / smin;

        DMatrixRMaj B = new DMatrixRMaj(X.numCols, 1);
        CommonOps_DDRM.solve(X.copy(), Y.copy(), B); // normal-equation style LS for fallback
        // Explicit truncated-SVD pseudoinverse so near-null directions are not inverted.
        B.zero();
        DMatrixRMaj UtY = new DMatrixRMaj(X.numRows, 1);
        DMatrixRMaj U = svd.getU(null, false);
        DMatrixRMaj V = svd.getV(null, false);
        DMatrixRMaj Ut = new DMatrixRMaj(U.numCols, U.numRows);
        CommonOps_DDRM.transpose(U, Ut);
        CommonOps_DDRM.mult(Ut, Y, UtY);
        for (int i = 0; i < n; i++) {
            if (sv[i] <= tol) continue;
            for (int r = 0; r < X.numCols; r++) {
                B.add(r, 0, V.get(r, i) * (UtY.get(i, 0) / sv[i]));
            }
        }
        double[] beta = B.data.clone();

        // Covariance of beta under the truncated subspace: V_r diag(1/s^2) V_r^T
        double[][] cov = new double[X.numCols][X.numCols];
        for (int i = 0; i < rank; i++) {
            for (int r = 0; r < X.numCols; r++) {
                for (int c = 0; c < X.numCols; c++) {
                    cov[r][c] += V.get(r, i) * V.get(c, i) / (sv[i] * sv[i]);
                }
            }
        }
        return new SvdResult(beta, cov, sv, rank, cond, tol);
    }

    /** Generalized LS with covariance C: whiten with C^-1/2 via eigendecomposition. */
    public static SvdResult solveWeightedSvd(double[][] x, double[] y, double[][] covY, double rankTol) {
        int n = y.length;
        double[][] w = inverseSqrt(covY);
        double[][] wx = multiply(w, x);
        double[] wy = multiply(w, y);
        SvdResult r = solveSvd(wx, wy, rankTol);
        // residuals measured in original coordinates
        double[] pred = multiply(x, r.beta);
        double[] resid = new double[n];
        for (int i = 0; i < n; i++) resid[i] = y[i] - pred[i];
        return new SvdResult(r.beta, r.covBeta, r.singularValues, r.rank, r.conditionNumber,
                r.rankTolerance, resid, true);
    }

    /** C^{-1/2} for symmetric positive (semi-)definite C. */
    public static double[][] inverseSqrt(double[][] c) {
        SymmetricEigen e = symmetricEigen(c);
        int n = c.length;
        double[][] v = e.vectors;
        double[][] vt = transpose(v);
        double[][] dInvSqrt = new double[n][n];
        double floor = 1e-12 * (e.values[0]);
        for (int i = 0; i < n; i++) {
            double val = Math.max(e.values[i], Math.max(floor, 0.0));
            dInvSqrt[i][i] = val > 0 ? 1.0 / Math.sqrt(val) : 0.0;
        }
        // V D^-1/2 V^T
        return multiply(multiply(v, dInvSqrt), vt);
    }

    public static double[][] sqrtMatrix(double[][] c) {
        SymmetricEigen e = symmetricEigen(c);
        int n = c.length;
        double[][] v = e.vectors;
        double[][] vt = transpose(v);
        double[][] dSqrt = new double[n][n];
        double floor = 1e-12 * (e.values[0]);
        for (int i = 0; i < n; i++) {
            double val = Math.max(e.values[i], Math.max(floor, 0.0));
            dSqrt[i][i] = Math.sqrt(val);
        }
        return multiply(multiply(v, dSqrt), vt);
    }

    public static SymmetricEigen symmetricEigen(double[][] a) {
        DMatrixRMaj A = new DMatrixRMaj(a);
        EigenDecomposition<DMatrixRMaj> eig = DecompositionFactory_DDRM.eig(A.numRows, true);
        if (!eig.decompose(A.copy())) throw new ArithmeticException("Eigen decomposition failed");
        int n = A.numRows;
        double[] vals = new double[n];
        double[][] vecs = new double[n][n];
        for (int i = 0; i < n; i++) vals[i] = eig.getEigenvalue(i).getReal();
        // selection sort descending
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(vals[q], vals[p]));
        double[] sortedVals = new double[n];
        double[][] sortedVecs = new double[n][n];
        for (int i = 0; i < n; i++) {
            sortedVals[i] = vals[order[i]];
            DMatrixRMaj vec = eig.getEigenVector(order[i]);
            for (int r = 0; r < n; r++) sortedVecs[r][i] = vec == null ? 0.0 : vec.get(r);
        }
        return new SymmetricEigen(sortedVals, sortedVecs);
    }

    /** Eigenvalues of a 4x4 Hermitian matrix supplied as complex entries. */
    public static HermitianEigen hermitianEigen(ComplexMatrix h) {
        return h.eigen();
    }

    public static double norm2(double[] v) {
        return Norm_DDRM.fastNormF2(DMatrixRMaj.wrap(v.length, 1, v));
    }

    static double[][] toArrays(DMatrixRMaj m) {
        double[][] out = new double[m.numRows][m.numCols];
        for (int r = 0; r < m.numRows; r++)
            for (int c = 0; c < m.numCols; c++) out[r][c] = m.unsafe_get(r, c);
        return out;
    }

    public static final class SvdResult {
        public final double[] beta;
        public final double[][] covBeta;
        public final double[] singularValues;
        public final int rank;
        public final double conditionNumber;
        public final double rankTolerance;
        public final double[] residuals;
        public final boolean weighted;

        public SvdResult(double[] beta, double[][] covBeta, double[] singularValues, int rank,
                         double conditionNumber, double rankTolerance) {
            this(beta, covBeta, singularValues, rank, conditionNumber, rankTolerance, new double[0], false);
        }

        public SvdResult(double[] beta, double[][] covBeta, double[] singularValues, int rank,
                         double conditionNumber, double rankTolerance, double[] residuals, boolean weighted) {
            this.beta = beta;
            this.covBeta = covBeta;
            this.singularValues = singularValues;
            this.rank = rank;
            this.conditionNumber = conditionNumber;
            this.rankTolerance = rankTolerance;
            this.residuals = residuals;
            this.weighted = weighted;
        }
    }

    public record SymmetricEigen(double[] values, double[][] vectors) {}

    public record HermitianEigen(double[] values, ComplexMatrix vectors) {}
}
