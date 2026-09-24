package pol.domain;

import org.ejml.data.DMatrixRMaj;

/**
 * Result of an SVD least-squares estimation. {@code forcedFullRank} is true
 * only when a caller demanded a unique solution from a rank-deficient design;
 * the estimator refuses to fabricate uniqueness in that case (see Estimator).
 */
public record Fit(
        DMatrixRMaj solution,
        double[] singularValues,
        int rank,
        int nullity,
        double conditionNumber,
        boolean rankDeficient,
        boolean forcedFullRank,
        double residualSumSquares,
        int residualDegreesOfFreedom,
        DMatrixRMaj predicted,
        DMatrixRMaj residuals,
        double rcond,
        DMatrixRMaj design) {

    public double solutionElement(int index) {
        return solution.get(index, 0);
    }

    public double residualVariance() {
        if (residualDegreesOfFreedom <= 0) {
            return Double.NaN;
        }
        return residualSumSquares / residualDegreesOfFreedom;
    }
}
