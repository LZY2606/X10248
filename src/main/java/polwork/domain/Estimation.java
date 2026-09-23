package polwork.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimation from paired generator/analyzer intensity measurements.
 *
 * Design rule enforced here: a singular or near-singular design matrix is
 * NEVER force-inverted. The solver reports the conditioning, keeps the
 * minimum-norm solution only as one explicitly-labelled candidate, and
 * derives the null-space basis so every legitimate solution of the
 * underdetermined system stays visible.
 */
public final class Estimation {

    /** Condition number above which inversion is refused. */
    public static final double CONDITION_LIMIT = 1e10;

    private Estimation() {
    }

    public record Measurement(double[] generatorStokes, double[] analyzerWeight, double intensity) {
    }

    public record DesignReport(RealMatrix design, double conditionNumber, int rank,
                               boolean invertible, double[] singularValues, RealMatrix nullSpaceBasis) {
    }

    public record Candidate(String id, String label, double[] vecM, String note) {
    }

    public record EstimateResult(DesignReport design, List<Candidate> candidates,
                                 double[] solution, double[] standardErrors, double chi2, int dof) {
    }

    /** One design-matrix row per measurement: w (kron) g, row-major vec(M). */
    public static RealMatrix designMatrix(List<Measurement> data) {
        RealMatrix w = new Array2DRowRealMatrix(data.size(), 16);
        for (int k = 0; k < data.size(); k++) {
            Measurement meas = data.get(k);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    w.setEntry(k, i * 4 + j, meas.analyzerWeight()[i] * meas.generatorStokes()[j]);
                }
            }
        }
        return w;
    }

    public static DesignReport analyzeDesign(RealMatrix design) {
        SingularValueDecomposition svd = new SingularValueDecomposition(design);
        double[] sv = svd.getSingularValues();
        double max = sv.length == 0 ? 0 : sv[0];
        double min = sv.length == 0 ? 0 : sv[sv.length - 1];
        double cond = min > 0 ? max / min : Double.POSITIVE_INFINITY;
        int rank = 0;
        double tol = Math.max(design.getRowDimension(), design.getColumnDimension()) * (max == 0 ? 1 : max) * 1e-12;
        for (double s : sv) {
            if (s > tol) {
                rank++;
            }
        }
        RealMatrix v = svd.getV();
        List<double[]> nullCols = new ArrayList<>();
        for (int j = rank; j < 16 && j < v.getColumnDimension(); j++) {
            nullCols.add(v.getColumn(j));
        }
        RealMatrix nullBasis = nullCols.isEmpty() ? null
                : new Array2DRowRealMatrix(nullCols.toArray(new double[0][])).transpose();
        boolean invertible = rank == 16 && cond <= CONDITION_LIMIT;
        return new DesignReport(design, cond, rank, invertible, sv, nullBasis);
    }

    /**
     * Weighted least squares vec(M) = (W' Cinv W)^-1 W' Cinv y.
     * Refuses singular designs; near-singular designs yield candidates only.
     */
    public static EstimateResult estimate(List<Measurement> data, RealMatrix covariance) {
        RealMatrix design = designMatrix(data);
        DesignReport report = analyzeDesign(design);
        double[] y = new double[data.size()];
        for (int k = 0; k < data.size(); k++) {
            y[k] = data.get(k).intensity();
        }
        RealMatrix cinv = covariance == null
                ? org.apache.commons.math3.linear.MatrixUtils.createRealIdentityMatrix(data.size())
                : DMatrix.inverseVerified(covariance);
        RealMatrix wtc = design.transpose().multiply(cinv);
        RealMatrix normal = wtc.multiply(design);
        RealMatrix rhs = wtc.operate(new Array2DRowRealMatrix(y));

        List<Candidate> candidates = new ArrayList<>();
        double[] solution = null;
        double[] stderr = null;
        double chi2 = Double.NaN;
        int dof = data.size() - report.rank();

        if (report.invertible()) {
            RealMatrix cov = DMatrix.inverseVerified(normal);
            solution = cov.operate(rhs).getColumn(0);
            stderr = new double[16];
            for (int i = 0; i < 16; i++) {
                stderr[i] = Math.sqrt(Math.max(0, cov.getEntry(i, i)));
            }
            double[] fit = design.operate(solution);
            double[] r = new double[y.length];
            for (int k = 0; k < y.length; k++) {
                r[k] = y[k] - fit[k];
            }
            chi2 = new Array2DRowRealMatrix(r).transpose().multiply(cinv)
                    .operate(new Array2DRowRealMatrix(r)).getEntry(0, 0);
            candidates.add(new Candidate("wls-unique", "unique WLS solution", solution, null));
        } else {
            // No forced inversion. Minimum-norm solution is kept as ONE
            // labelled candidate; null-space directions parameterize the rest.
            RealMatrix pinv = DMatrix.pseudoInverse(normal, 0);
            double[] minNorm = pinv.operate(rhs).getColumn(0);
            candidates.add(new Candidate("min-norm", "minimum-norm candidate (design "
                    + (report.rank() < 16 ? "rank-deficient" : "ill-conditioned")
                    + ", cond=" + String.format("%.3g", report.conditionNumber()) + ")",
                    minNorm, "not a unique solution; do not treat as the Mueller matrix"));
            if (report.nullSpaceBasis() != null) {
                for (int j = 0; j < report.nullSpaceBasis().getColumnDimension(); j++) {
                    double[] dir = report.nullSpaceBasis().getColumn(j);
                    double[] alt = minNorm.clone();
                    for (int i = 0; i < 16; i++) {
                        alt[i] += dir[i];
                    }
                    candidates.add(new Candidate("nullspace-" + j,
                            "min-norm + null-space direction " + j, alt,
                            "equally consistent with the data (residual identical)"));
                }
            }
        }
        return new EstimateResult(report, candidates, solution, stderr, chi2, dof);
    }
}
