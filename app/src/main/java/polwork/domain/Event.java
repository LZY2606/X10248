package polwork.domain;

import java.time.Instant;

/**
 * Append-only event-sourcing record. Machine-readable {@code type} and JSON
 * {@code payload} capture what changed; {@code parentUid} is the previous
 * event on the branch and {@code baseHeadUid} is the head the actor saw when
 * committing (used for optimistic two-window conflict detection).
 *
 * Every adjudication is undoable: an undo appends a counter-event rather than
 * deleting anything, so any old event can still be revisited.
 */
public final class Event {
    public final long rowId;
    public final long ord;
    public final String uid;
    public final String branchUid;
    public final String parentUid;
    public final String baseHeadUid;
    public final String type;
    public final String payloadJson;
    public final Instant at;

    public Event(long rowId, long ord, String uid, String branchUid, String parentUid,
                 String baseHeadUid, String type, String payloadJson, Instant at) {
        this.rowId = rowId;
        this.ord = ord;
        this.uid = uid;
        this.branchUid = branchUid;
        this.parentUid = parentUid;
        this.baseHeadUid = baseHeadUid;
        this.type = type;
        this.payloadJson = payloadJson;
        this.at = at;
    }
}
