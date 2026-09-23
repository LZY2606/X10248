package polwork.domain;

import java.util.List;

/**
 * A Mueller-matrix estimate with its provenance fingerprint, covariance and
 * constraints. The non-physical candidate is stored as-is with violation
 * flags; nothing here is a post-projection stand-in.
 */
public final class MuellerCandidate {
    public final String candidateId;
    public final String fingerprint;
    public final double[][] mueller;
    public final double[][] elementStdError;
    public final DesignInfo design;
    public final PhysicalConstraints constraints;
    public final List<DecompositionCandidate> decompositions;
    public final String solverStatus;   // OK, NEAR_SINGULAR, RANK_DEFICIENT
    public final String note;

    public MuellerCandidate(String candidateId, String fingerprint, double[][] mueller,
                            double[][] elementStdError, DesignInfo design,
                            PhysicalConstraints constraints,
                            List<DecompositionCandidate> decompositions,
                            String solverStatus, String note) {
        this.candidateId = candidateId;
        this.fingerprint = fingerprint;
        this.mueller = mueller;
        this.elementStdError = elementStdError;
        this.design = design;
        this.constraints = constraints;
        this.decompositions = decompositions;
        this.solverStatus = solverStatus;
        this.note = note;
    }
}
