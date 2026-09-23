package pol.store;

import java.util.List;

/**
 * Immutable, append-only event. Everything the room can show is a projection of
 * events. Events are never edited or deleted, so replaying to an old sequence
 * restores the exact prior interpretation without overwriting history.
 */
public final class Event {
    public final String id;
    public final String seq;
    public final String branch;
    public final String type;
    public final String parentId;
    public final String createdAt;
    public final String actor;
    public final String payloadJson;
    public final String fingerprint;
    public final String sourceEventIdsJson;
    public final String directSourceId;
    public final String revisionOfId;

    public Event(String id, String seq, String branch, String type, String parentId,
                 String createdAt, String actor, String payloadJson, String fingerprint,
                 String sourceEventIdsJson, String directSourceId, String revisionOfId) {
        this.id = id;
        this.seq = seq;
        this.branch = branch;
        this.type = type;
        this.parentId = parentId;
        this.createdAt = createdAt;
        this.actor = actor;
        this.payloadJson = payloadJson;
        this.fingerprint = fingerprint;
        this.sourceEventIdsJson = sourceEventIdsJson;
        this.directSourceId = directSourceId;
        this.revisionOfId = revisionOfId;
    }

    public List<String> sources() {
        if (sourceEventIdsJson == null || sourceEventIdsJson.isBlank()) {
            return List.of();
        }
        return pol.service.Json.readList(sourceEventIdsJson);
    }
}
