package polwork.domain;

import java.time.Instant;

/**
 * One immutable version of one source category. Payload JSON holds the parsed
 * content; {@code blobSha} points at the untouched original imported bytes.
 */
public final class SourceVersion {
    public final long rowId;
    public final String uid;
    public final SourceKind kind;
    public final String versionId;
    public final String materialId;
    public final String wavelengthId;
    public final String blobSha;
    public final String payloadJson;
    public final String conventionTag;
    public final Instant importedAt;

    public SourceVersion(long rowId, String uid, SourceKind kind, String versionId,
                         String materialId, String wavelengthId, String blobSha,
                         String payloadJson, String conventionTag, Instant importedAt) {
        this.rowId = rowId;
        this.uid = uid;
        this.kind = kind;
        this.versionId = versionId;
        this.materialId = materialId;
        this.wavelengthId = wavelengthId;
        this.blobSha = blobSha;
        this.payloadJson = payloadJson;
        this.conventionTag = conventionTag;
        this.importedAt = importedAt;
    }
}
