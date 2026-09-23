package polwork.domain;

import java.util.List;

/** Full derived output for one fingerprint on one branch. */
public final class EstimationResult {
    public final String fingerprint;
    public final List<MuellerCandidate> candidates;
    public final String ruleSetVersion;
    public final String invalidatedByEventUid; // null = current
    public final String producedByEventUid;

    public EstimationResult(String fingerprint, List<MuellerCandidate> candidates,
                            String ruleSetVersion, String producedByEventUid,
                            String invalidatedByEventUid) {
        this.fingerprint = fingerprint;
        this.candidates = candidates;
        this.ruleSetVersion = ruleSetVersion;
        this.producedByEventUid = producedByEventUid;
        this.invalidatedByEventUid = invalidatedByEventUid;
    }

    public EstimationResult markedInvalid(String eventUid) {
        return new EstimationResult(fingerprint, candidates, ruleSetVersion,
                producedByEventUid, eventUid);
    }
}
