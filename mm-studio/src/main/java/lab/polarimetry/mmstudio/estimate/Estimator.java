package lab.polarimetry.mmstudio.estimate;

import lab.polarimetry.mmstudio.math.Mx;
import org.apache.commons.math3.linear.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimation from a measurement design.
 * Near-singular designs are never forced through an inverse: the minimum-norm
 * candidate is reported together with rank, condition number and nullspace, and
 * uniqueness is marked false so reviewers keep all legitimate candidates.
 */
public final class Estimator {
    private Estimator() {}

    public static class Result {
        public RealMatrix design;                 // n x 16
        public double conditionNumber;
        public int rank;
        public double singularThreshold;
        public boolean unique;
        public RealMatrix muellerMinNorm;         // 4x4 candidate (nullspace set to zero)
        public RealMatrix covariance;             // 16x16 (pseudo-inverse based)
        public double[][] nullspaceBasis;         // each row a 16-vector in row-major vec space
        public double[] fittedIntensity;
        public double[] correctedIntensity;
        public double chiSquare;
        public int measurementCount;
        public List<String> notes = new ArrayList<>();
    }

    public static Result estimate(double[][] designRows, double[] correctedIntensity,
                                  RealMatrix covarianceY, double singularRelTol) {
        int n = designRows.length;
        Result r = new Result();
        r.measurementCount = n;
        r.correctedIntensity = correctedIntensity.clone();
        RealMatrix x = new Array2DRowRealMatrix(designRows);
        r.design = x;

        SingularValueDecomposition svd = new SingularValueDecomposition(x);
        double[] sv = svd.getSingularValues();
        double smax = sv.length == 0 ? 0 : sv[0];
        r.singularThreshold = singularRelTol * smax;
        int rank = 0;
        for (double v : sv) if (v > r.singularThreshold) rank++;
        r.rank = rank;
        r.unique = rank == 16;
        r.conditionNumber = sv[sv.length - 1] > 0 ? smax / sv[sv.length - 1] : Double.POSITIVE_INFINITY;

        // Weight matrix from measurement covariance (may be correlated).
        RealMatrix w = Mx.psdPseudoInverse(covarianceY, 1e-12);

        RealModel model = solveWeighted(x, correctedIntensity, w, sv, r.singularThreshold);
        r.muellerMinNorm = Mx.unflatten4(model.coeffs);
        r.covariance = model.covCoeffs;

        // Nullspace basis from V columns corresponding to dropped singular values.
        RealMatrix v = svd.getV();
        List<double[]> nulls = new ArrayList<>();
        for (int k = rank; k < sv.length && k < 16; k++) {
            double[] vec = new double[16];
            for (int i = 0; i < 16; i++) vec[i] = v.getEntry(i, k);
            nulls.add(vec);
        }
        r.nullspaceBasis = nulls.toArray(new double[0][]);

        r.fittedIntensity = x.operate(model.coeffs);
        double chi = 0;
        RealMatrix residVec = new Array2DRowRealMatrix(n, 1);
        for (int i = 0; i < n; i++) residVec.setEntry(i, 0, correctedIntensity[i] - r.fittedIntensity[i]);
        RealMatrix chiM = residVec.transpose().multiply(w).multiply(residVec);
        r.chiSquare = chiM.getEntry(0, 0);

        if (!r.unique) {
            r.notes.add(String.format(
                    "rank %d < 16 at singular-value threshold %.3g: design matrix is near-singular; "
                            + "no inverse was forced, minimum-norm candidate reported with %d-dimensional "
                            + "nullspace, uniqueness=false, all legal candidates mueller = mMinNorm + nullspace*t retained",
                    rank, r.singularThreshold, 16 - rank));
        }
        if (r.conditionNumber > 1e6) {
            r.notes.add(String.format("condition number %.3g exceeds 1e6; coefficients are ill-conditioned",
                    r.conditionNumber));
        }
        return r;
    }

    private record RealModel(double[] coeffs, RealMatrix covCoeffs) {}

    private static RealModel solveWeighted(RealMatrix x, double[] y, RealMatrix w,
                                           double[] singularValues, double threshold) {
        // Normal matrix N = X^T W X with SVD-based pseudo solve (never a hard inverse).
        RealMatrix n = x.transpose().multiply(w).multiply(x);
        RealMatrix nInv = Mx.psdPseudoInverse(n, threshold / Math.max(singularValues[0] * singularValues[0], 1e-300));
        RealMatrix xty = new Array2DRowRealMatrix(16, 1);
        double[] xtyRaw = x.transpose().multiply(w).operate(y);
        for (int i = 0; i < 16; i++) xty.setEntry(i, 0, xtyRaw[i]);
        RealModel m = new RealModel(nInv.multiply(xty).getColumn(0), nInv);
        return m;
    }
}
