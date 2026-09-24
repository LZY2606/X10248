package mmlab.math;

/**
 * Outcome of a weighted least-squares solve with explicit null-space handling.
 * The design matrix is never forcibly inverted: singular values below the
 * threshold are dropped and their right-singular vectors are reported.
 */
public final class WlsResult {
    public final double[] solution;
    public final double[] singularValues;
    public final Mat rightVectors;
    public final int rank;
    public final double conditionNumber;
    public final double rankTolerance;
    public final Mat covariance;
    public final double residualNorm;
    public final double[] nullBasis;
    public final double chiSquare;
    public final int dof;

    public WlsResult(double[] solution, double[] singularValues, Mat rightVectors, int rank,
                     double conditionNumber, double rankTolerance, Mat covariance,
                     double residualNorm, double[] nullBasis, double chiSquare, int dof) {
        this.solution = solution;
        this.singularValues = singularValues;
        this.rightVectors = rightVectors;
        this.rank = rank;
        this.conditionNumber = conditionNumber;
        this.rankTolerance = rankTolerance;
        this.covariance = covariance;
        this.nullBasis = nullBasis;
        this.residualNorm = residualNorm;
        this.chiSquare = chiSquare;
        this.dof = dof;
    }

    public boolean isRankDeficient() {
        return rank < solution.length;
    }

    public int nullity() {
        return solution.length - rank;
    }
}
