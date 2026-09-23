package com.polarlab.domain;

import java.util.List;
import java.util.Map;

/**
 * Domain records shared by the store, engine and web layers.
 *
 * Source versions are immutable, append-only interpretations of content-addressed raw bytes.
 * Every domain quantity carries its unit / coordinate basis; values that cannot be converted
 * stay at their source position with diagnostics rather than being dropped for display.
 */
public final class Model {
    private Model() {}

    public enum SourceKind {
        INTENSITIES, STATE_ENCODING, DARK, REFERENCE, INSTRUMENT_MATRIX, WAVELENGTH, COVARIANCE
    }

    /** Immutable raw blob: bytes are stored once, referenced by content hash. */
    public record Blob(String hash, long size, String mediaType) {}

    /**
     * One version of a source. revision = 1 for the first interpretation of a sourceId.
     * payload is the parsed JSON tree retained verbatim; diagnostics hold non-fatal issues
     * (e.g. an unconvertible unit left in place).
     */
    public record SourceVersion(String sourceId, int revision, SourceKind kind,
                                String blobHash, String label, String unitBasis,
                                String importedAt, Map<String, Object> payload,
                                List<String> diagnostics) {
        public String ref() { return kind + ":" + sourceId + ":v" + revision + "@" + shortHash(); }
        public String shortHash() { return blobHash.substring(0, 12); }
    }

    /** Branch of interpretation. materialId groups branches that reinterpret the same material. */
    public record Branch(String id, String materialId, String name, String parentBranchId,
                         String baseEventId, String createdAt) {}

    /**
     * Append-only event. seq is per-branch ordering; headRef is the event this appends after
     * (optimistic concurrency: two windows must not both append after the same head).
     */
    public record Event(String id, String branchId, int seq, String type, String actor,
                        String createdAt, String headRef,
                        Map<String, Object> payload,
                        List<String> sourceRefs, String fingerprint) {}

    /** Derived analysis result recorded against an event lineage. */
    public record DerivedResult(String id, String branchId, String eventId, String kind,
                                String status, String candidateId, boolean adopted,
                                String parentDerivedId, Map<String, Object> summary,
                                List<String> inputRefs, String fingerprint) {}

    /** Reviewer decision against a candidate. supersededByEventId enables undo/redo chains. */
    public record Decision(String id, String branchId, String eventId, String derivedId,
                           String candidateId, String verdict /* ACCEPT|REJECT */,
                           String reason, String createdAt, String undoneByEventId) {}

    /** Dependency edge: derived result id -> source ref it depends on, with scope of impact. */
    public record Dependency(String derivedId, String sourceRef, String scope,
                             boolean localOnly) {}
}
