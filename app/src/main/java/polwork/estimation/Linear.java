package polwork.estimation;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.Norm_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;
import org.ejml.dense.row.SingularOps_DDRM;

import java.util.Arrays;

/** Small dense linear-algebra helpers (EJML) with explicit SVD control. */
public final class Linear {

    public static final double SINGULAR_REL_TOL = 1e-10;
    public static final double NEAR_SINGULAR_COND = 1e8;

    private Linear() {}

    public static DMatrixRMaj mat(double[][] a) {
        DMatrixRMaj m = new DMatrixRMaj(a.length, a[0].length);
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[i].length; j++) m.set(i, j, a[i][j]);
        }
        return m;
    }

    public static double[][] arr(DMatrixRMaj m) {
        double[][] a = new double[m.numRows][m.numCols];
        for (int i = 0; i < m.numRows; i++) {
            for (int j = 0; j < m.numCols; j++) a[i][j] = m.get(i, j);
        }
        return a;
    }

    public static DMatrixRMaj vec(double[] v) {
        DMatrixRMaj m = new DMatrixRMaj(v.length, 1);
        for (int i = 0; i < v.length; i++) m.set(i, 0, v[i]);
        return m;
    }

    public static double[] vecArr(DMatrixRMaj m) {
        double[] v = new double[m.numRows];
        for (int i = 0; i < m.numRows; i++) v[i] = m.get(i, 0);
        return v;
    }

    public static DMatrixRMaj kron(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows * b.numRows, a.numCols * b.numCols);
        CommonOps_DDRM.kron(a, b, out);
        return out;
    }

    public static DMatrixRMaj transpose(DMatrixRMaj a) {
        DMatrixRMaj out = new DMatrixRMaj(a.numCols, a.numRows);
        CommonOps_DDRM.transpose(a, out);
        return out;
    }

    public static DMatrixRMaj mult(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows, b.numCols);
        CommonOps_DDRM.mult(a, b, out);
        return out;
    }

    public static DMatrixRMaj invertSPD(DMatrixRMaj a) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows, a.numCols);
        CommonOps_DDRM.invert(a, out);
        return out;
    }

    /** Solves W^{-1/2}-weighted least squares by explicit SVD. */
    public static final class SvdSolution {
        public final double[] xMinNorm;
        public final double[] singularValues;
        public final int rank;
        public final double conditionNumber;
        public final boolean rankDeficient;
        public final boolean nearSingular;
        /** Covariance V S^{-2} V^T (truncated at the numerical rank). */
        public final DMatrixRMaj covariance;

        SvdSolution(double[] x, double[] sv, int rank, double cond, boolean deficient,
                    boolean near, DMatrixRMaj cov) {
            this.xMinNorm = x;
            this.singularValues = sv;
            this.rank = rank;
            this.conditionNumber = cond;
            this.rankDeficient = deficient;
            this.nearSingular = near;
            this.covariance = cov;
        }
    }

    /**
     * Solve A x = b in the minimum-norm least-squares sense via SVD.
     * A near-singular or rank-deficient design is never inverted directly:
     * components below the relative singular-value threshold are excluded and
     * the status is reported so callers can keep the result labelled.
     */
    public static SvdSolution solveWeightedSVD(DMatrixRMaj a, DMatrixRMaj b, DMatrixRMaj wHalfInv) {
        DMatrixRMaj aw = wHalfInv != null ? mult(wHalfInv, a) : a.copy();
        DMatrixRMaj bw = wHalfInv != null ? mult(wHalfInv, b) : b.copy();

        SingularValueDecomposition<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(aw.numRows, aw.numCols, true, true, false);
        if (!svd.decompose(aw)) {
            throw new IllegalStateException("SVD failed");
        }
        DMatrixRMaj u = svd.getU(null, false);
        DMatrixRMaj v = svd.getV(null, true);
        double[] sv = svd.getSingularValues();
        int n = Math.min(aw.numRows, aw.numCols);

        double smax = Arrays.stream(sv, 0, n).max().orElse(0.0);
        double cutoff = SINGULAR_REL_TOL * Math.max(smax, 1.0);
        int rank = 0;
        for (int i = 0; i < n; i++) if (sv[i] > cutoff) rank++;

        double smin = rank > 0 ? sv[rank - 1] : 0.0;
        double cond = rank == n ? smax / Math.max(smin, Double.MIN_NORMAL) : Double.POSITIVE_INFINITY;
        boolean deficient = rank < aw.numCols;
        boolean near = !deficient && cond > NEAR_SINGULAR_COND;

        // x = V_r S_r^{-1} U_r^T b_w
        DMatrixRMaj x = new DMatrixRMaj(aw.numCols, 1);
        for (int i = 0; i < rank; i++) {
            double utb = 0;
            for (int k = 0; k < aw.numRows; k++) utb += u.get(k, i) * bw.get(k, 0);
            double coeff = utb / sv[i];
            for (int k = 0; k < aw.numCols; k++) x.add(k, 0, v.get(k, i) * coeff);
        }

        // covariance = V_r S_r^{-2} V_r^T (white residual variance applied later)
        DMatrixRMaj cov = new DMatrixRMaj(aw.numCols, aw.numCols);
        for (int i = 0; i < rank; i++) {
            double invS2 = 1.0 / (sv[i] * sv[i]);
            for (int r = 0; r < aw.numCols; r++) {
                for (int c = 0; c < aw.numCols; c++) {
                    cov.add(r, c, v.get(r, i) * invS2 * v.get(c, i));
                }
            }
        }

        return new SvdSolution(vecArr(x), Arrays.copyOf(sv, n), rank, cond, deficient, near, cov);
    }

    public static DMatrixRMaj identity(int n) {
        DMatrixRMaj m = new DMatrixRMaj(n, n);
        CommonOps_DDRM.setIdentity(m);
        return m;
    }

    public static double frobenius(DMatrixRMaj a) {
        return Norm_DDRM.fastNormF(a);
    }
}
