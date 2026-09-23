package polwork.domain;

import java.time.Instant;

/**
 * A reinterpretation line. Reinterpreting the same material creates a new
 * branch; the old branch, its decisions and the raw bytes it cites stay
 * readable forever.
 */
public final class Branch {
    public final long rowId;
    public final String uid;
    public final String name;
    public final String materialId;
    public final String headEventUid;
    public final Instant createdAt;

    public Branch(long rowId, String uid, String name, String materialId,
                  String headEventUid, Instant createdAt) {
        this.rowId = rowId;
        this.uid = uid;
        this.name = name;
        this.materialId = materialId;
        this.headEventUid = headEventUid;
        this.createdAt = createdAt;
    }

    public Branch withHead(String headEventUid) {
        return new Branch(rowId, uid, name, materialId, headEventUid, createdAt);
    }
}
