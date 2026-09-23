package polwork.domain;

/**
 * Physical-realizability diagnostics for a Mueller matrix candidate.
 * Violations are *reported, never projected away*: a flagged candidate stays
 * the original estimate plus a violation marker.
 */
public final class PhysicalConstraints {
    public final double passivityResidual;  // max over entries |M|>1, ideal sample normalization
    public final double cloudeMinEigenvalue; // min eigenvalue of coherency matrix
    public final boolean physicallyRealizable;
    public final String violations;         // machine-readable list
    public final double depolarizationIndex;

    public PhysicalConstraints(double passivityResidual, double cloudeMinEigenvalue,
                               boolean physicallyRealizable, String violations,
                               double depolarizationIndex) {
        this.passivityResidual = passivityResidual;
        this.cloudeMinEigenvalue = cloudeMinEigenvalue;
        this.physicallyRealizable = physicallyRealizable;
        this.violations = violations;
        this.depolarizationIndex = depolarizationIndex;
    }
}
