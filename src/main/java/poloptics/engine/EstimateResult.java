package poloptics.engine;

import java.util.List;

/**
 * Outcome of a Mueller estimation attempt.
 *
 * Near-singular design matrices are NOT force-inverted: rank, nullity and the
 * minimum-norm particular solution are recorded together with null-space
 * directions, so all legitimate candidates consistent with the data survive.
 */
public final class EstimateResult {
    public final boolean solvable;
    public final double conditionNumber; // sigmaMax / sigmaMin, Inf when nullity > 0
    public final int rank;
    public final int nullity;
    public final double[] solution;          // min-norm least-squares vec(M^T), null if failed
    public final double[][] covariance16;    // propagated element covariance (may be Inf for nullspace)
    public final List<double[]> nullspace;   // basis vectors for undetermined directions
    public final double residualRms;
    public final String diagnostic;

    public EstimateResult(boolean solvable, double conditionNumber, int rank, int nullity,
                          double[] solution, double[][] covariance16, List<double[]> nullspace,
                          double residualRms, String diagnostic) {
        this.solvable = solvable;
        this.conditionNumber = conditionNumber;
        this.rank = rank;
        this.nullity = nullity;
        this.solution = solution;
        this.covariance16 = covariance16;
        this.nullspace = nullspace;
        this.residualRms = residualRms;
        this.diagnostic = diagnostic;
    }
}
