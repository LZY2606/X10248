package lab.polarimetry.mmstudio.db;

import java.util.List;
import java.util.Map;

public final class Records {
    private Records() {}

    public record Branch(String branchId, String materialId, String label,
                         String parentBranchId, String headEventId,
                         String createdAt, String note) {}

    public record Event(long seq, String eventId, String branchId, String parentEventId,
                        String type, String versionRef, Map<String, Object> payload,
                        String basisFingerprint, String actor, String createdAt,
                        boolean reverted) {}

    public record Source(String sourceId, String kind, String materialId, String version,
                         String unitOrBasis, String rawRepresentation,
                         String parseDiagnostic, String ingestEventId, String createdAt) {}

    public record Artifact(String artifactId, String materialId, String branchId, String kind,
                           String candidateId, Map<String, Object> depends,
                           Map<String, Object> payload, String sourceEventId,
                           String validFromEventId, String supersededEventId, String createdAt) {}

    public record Diagnostic(String diagnosticId, String materialId, String scope,
                             String message, String severity, String eventId, String createdAt) {}

    public record Edge(String edgeId, String sourceId, String artifactId, String scopeLabel, String createdAt) {}
}
