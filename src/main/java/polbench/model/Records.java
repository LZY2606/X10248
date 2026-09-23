package polbench.model;

import polbench.model.Enums.*;

import java.util.List;

public final class Records {
    private Records() {}

    /** A source layer record: provenance kept separate from interpretation. */
    public record Source(
            String id,
            String materialId,
            SourceKind kind,
            String version,
            String supersedes,
            String unit,
            String coordinateBasis,
            String rawRepresentation,
            String canonicalJson,
            String sha256,
            long seq,
            boolean convertible
    ) {}

    public record RuleVersion(
            String id,
            String ruleName,
            String version,
            String parametersJson,
            String sha256,
            long seq
    ) {}

    public record Artifact(
            String id,
            String materialId,
            String branchId,
            ArtifactKind kind,
            String status,
            String payloadJson,
            String fingerprint,
            String ruleVersionId,
            List<String> sourceIds,
            List<String> parentArtifactIds,
            long eventSeq,
            String displayKey
    ) {}

    public record Branch(
            String id,
            String materialId,
            String name,
            String parentBranchId,
            long forkSeq,
            BranchStatus status,
            long headSeq,
            String createdBy
    ) {}

    public record Event(
            long seq,
            String materialId,
            String branchId,
            EventType type,
            String subjectId,
            String summary,
            String detailJson,
            String parentEventRef,
            String clientEventId,
            long baseVersion,
            long clientClock,
            String author
    ) {}

    public record Diagnostic(
            String id,
            String materialId,
            String branchId,
            String sourceId,
            String severity,
            String code,
            String message,
            long eventSeq
    ) {}
}
