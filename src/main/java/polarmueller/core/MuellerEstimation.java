package polarmueller.core;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller-matrix estimation from intensity measurements under multiple
 * generator/analyzer states. A near-singular design matrix is reported as
 * {@code singular_design} and is never forcibly inverted.
 */
public final class MuellerEstimation {
    public static final String RULE_VERSION = "mueller-wls/v1";
    public static final double COND_LIMIT = 1e8;

    private MuellerEstimation() {}

    public record Input(
            double[][] generator,   // N x 4 canonical IQUV Stokes
            double[][] analyzer,    // N x 4 canonical IQUV Stokes
            double[] intensity,     // N raw detector counts
            double[] dark,          // N dark counts
            double[][] covariance,  // N x N intensity covariance (counts^2)
            double[][] instrument   // 4x4 instrument correction matrix
    ) {}

    public record Result(
            String status,          // ok | singular_design | nonphysical
            double condition,
            int rank,
            double[] singularValues,
            double[] m,             // 16, row-major vec(M); null when not estimable
            double[] errors,        // 16 standard errors; null when not estimable
            double minCoherencyEigen,
            List<String> diagnostics
    ) {}

    public static Result estimate(Input in) {
        int n = in.intensity().length;
        List<String> diag = new ArrayList<>();
        DMatrixRMaj w = new DMatrixRMaj(n, 16);
        for (int i = 0; i < n; i++) {
            double[] row = MatrixOps.kron(in.analyzer()[i], in.generator()[i]);
            for (int j = 0; j < 16; j++) w.set(i, j, row[j]);
        }
        MatrixOps.Svd svd = MatrixOps.svd(w);
        if (svd.rank() < 16 || !(svd.condition() < COND_LIMIT)) {
            diag.add("design matrix rank=" + svd.rank() + " condition=" + svd.condition()
                    + " exceeds limit " + COND_LIMIT + "; inversion refused");
            return new Result("singular_design", svd.condition(), svd.rank(),
                    svd.singularValues(), null, null, Double.NaN, diag);
        }

        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = in.intensity()[i] - in.dark()[i];

        DMatrixRMaj cov = MatrixOps.mat(in.covariance());
        DMatrixRMaj covInv = MatrixOps.pinv(cov, 1e-12);
        DMatrixRMaj wt = new DMatrixRMaj(16, n);
        CommonOps_DDRM.transpose(w, wt);
        DMatrixRMaj a = new DMatrixRMaj(16, 16);
        DMatrixRMaj tmp = new DMatrixRMaj(16, n);
        CommonOps_DDRM.mult(wt, covInv, tmp);
        CommonOps_DDRM.mult(tmp, w, a);
        DMatrixRMaj aInv = MatrixOps.pinv(a, 1e-12);
        DMatrixRMaj b = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(tmp, new DMatrixRMaj(org.ejml.data.DMatrixRMaj.wrap(n, 1, y)), b);
        DMatrixRMaj mEst = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(aInv, b, mEst);

        // Instrument correction: M_true = Minv * M_est  =>  vec' = kron(I, Minv) vec
        DMatrixRMaj mInv = MatrixOps.invert(MatrixOps.mat(in.instrument()));
        DMatrixRMaj k = MatrixOps.kron(DMatrixRMaj.identity(4), mInv);
        DMatrixRMaj mTrue = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(k, mEst, mTrue);
        DMatrixRMaj covM = new DMatrixRMaj(16, 16);
        DMatrixRMaj kt = new DMatrixRMaj(16, 16);
        CommonOps_DDRM.transpose(k, kt);
        DMatrixRMaj tmp2 = new DMatrixRMaj(16, 16);
        CommonOps_DDRM.mult(k, aInv, tmp2);
        CommonOps_DDRM.mult(tmp2, kt, covM);

        double[] m = new double[16];
        double[] err = new double[16];
        for (int i = 0; i < 16; i++) {
            m[i] = mTrue.get(i, 0);
            err[i] = Math.sqrt(Math.max(covM.get(i, i), 0.0));
        }

        double minEigen = Physicality.minCoherencyEigenvalue(m);
        String status = "ok";
        if (minEigen < Physicality.VIOLATION_TOL) {
            status = "nonphysical";
            diag.add("coherency matrix minimum eigenvalue " + minEigen
                    + " < " + Physicality.VIOLATION_TOL
                    + "; candidate flagged as non-physical and NOT projected");
        }
        return new Result(status, svd.condition(), svd.rank(), svd.singularValues(),
                m, err, minEigen, diag);
    }
}
