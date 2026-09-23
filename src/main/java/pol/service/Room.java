package pol.service;

import pol.domain.Ids;
import pol.domain.Qty;
import pol.store.Database;
import pol.store.Event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application core over the append-only store. Handles sources, branches,
 * rulings (optimistic on head sequence), undo/redo pointer moves and replay.
 */
public final class Room {

    public static final class Conflict extends RuntimeException {
        public final int expected;
        public final int actual;
        public final List<Map<String, Object>> divergence;

        public Conflict(int expected, int actual, List<Map<String, Object>> divergence) {
            super("version conflict: expected head " + expected + " but branch is at " + actual);
            this.expected = expected;
            this.actual = actual;
            this.divergence = divergence;
        }
    }

    private final Database db;

    public Room(Database db) {
        this.db = db;
    }

    public Database database() {
        return db;
    }

    public String now() {
        return Instant.now().toString();
    }

    public synchronized void ensureBranch(String name, String actor, String description) {
        if (db.getBranch(name) == null) {
            db.transaction(() -> {
                db.putBranch(name, 0, null, null, now(), description);
            });
        }
    }

    /** Append an event with full provenance. Optimistic conflict check. */
    public synchronized Event append(String branch, String type, Map<String, Object> payload,
                                     List<String> sourceIds, String actor,
                                     int expectedHead, String directSourceId, String revisionOfId) {
        return appendAt(branch, type, payload, sourceIds, actor, expectedHead,
                directSourceId, revisionOfId, now());
    }

    @SuppressWarnings("unchecked")
    public synchronized Event appendAt(String branch, String type, Map<String, Object> payload,
                                       List<String> sourceIds, String actor,
                                       int expectedHead, String directSourceId,
                                       String revisionOfId, String at) {
        Database.BranchRow branchRow = db.getBranch(branch);
        if (branchRow == null) {
            throw new IllegalArgumentException("unknown branch " + branch);
        }
        if (expectedHead >= 0 && branchRow.headSeq() != expectedHead) {
            throw new Conflict(expectedHead, branchRow.headSeq(),
                    divergence(branch, expectedHead, branchRow.headSeq()));
        }
        final Event[] holder = new Event[1];
        db.transaction(() -> {
            int seq = db.nextSeq(branch);
            String parentId = db.getBranch(branch).headEventId();
            String payloadJson = Json.write(payload);
            String srcJson = Json.write(sourceIds == null ? List.of() : sourceIds);
            String fp = null;
            if (payload.get("fingerprint") instanceof String s) {
                fp = s;
            }
            String id = Ids.of("event", branch, Integer.toString(seq),
                    type, payloadJson, parentId == null ? "" : parentId);
            Event event = new Event(id, Integer.toString(seq), branch, type, parentId, at,
                    actor, payloadJson, fp, srcJson, directSourceId, revisionOfId);
            db.insertEvent(event, expectedHead < 0 ? 0 : expectedHead);
            db.setHead(branch, seq, id);
            db.logPointer(branch, "append", seq - 1, seq, id, actor, at, type);
            holder[0] = event;
        });
        return holder[0];
    }

    private List<Map<String, Object>> divergence(String branch, int expected, int actual) {
        List<Event> events = db.eventsOnBranchUpTo(branch, actual);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Event e : events) {
            if (Integer.parseInt(e.seq) > expected) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", Integer.parseInt(e.seq));
                m.put("id", e.id);
                m.put("type", e.type);
                m.put("actor", e.actor);
                m.put("at", e.createdAt);
                m.put("payload", Json.read(e.payloadJson, Map.class));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Register an original byte blob and append its source/provenance event in a
     * single transaction. A failure rolls both back, so an import can never
     * leave a half record or an orphaned blob.
     */
    @SuppressWarnings("unchecked")
    public synchronized Event registerBlobAndAppend(String branch, String type,
                                                     Map<String, Object> payload,
                                                     List<String> sourceIds, String actor,
                                                     int expectedHead, String directSourceId,
                                                     String revisionOfId,
                                                     String blobId, String sha,
                                                     String mediaType, byte[] bytes, long byteCount) {
        Database.BranchRow branchRow = db.getBranch(branch);
        if (branchRow == null) {
            throw new IllegalArgumentException("unknown branch " + branch);
        }
        if (expectedHead >= 0 && branchRow.headSeq() != expectedHead) {
            throw new Conflict(expectedHead, branchRow.headSeq(),
                    divergence(branch, expectedHead, branchRow.headSeq()));
        }
        final Event[] holder = new Event[1];
        db.transaction(() -> {
            db.putBlob(blobId, sha, mediaType, byteCount, bytes, now());
            int seq = db.nextSeq(branch);
            String parentId = db.getBranch(branch).headEventId();
            String payloadJson = Json.write(payload);
            String id = Ids.of("event", branch, Integer.toString(seq),
                    type, payloadJson, parentId == null ? "" : parentId);
            Event event = new Event(id, Integer.toString(seq), branch, type, parentId, now(),
                    actor, payloadJson,
                    payload.get("fingerprint") instanceof String fp ? fp : null,
                    Json.write(sourceIds == null ? List.of() : sourceIds),
                    directSourceId, revisionOfId);
            db.insertEvent(event, expectedHead < 0 ? 0 : expectedHead);
            db.setHead(branch, seq, id);
            db.logPointer(branch, "append", seq - 1, seq, id, actor, now(), type);
            holder[0] = event;
        });
        return holder[0];
    }

    public synchronized void moveHead(String branch, int targetSeq, String actor, String note) {
        Database.BranchRow row = db.getBranch(branch);
        if (row == null) {
            throw new IllegalArgumentException("unknown branch " + branch);
        }
        List<Event> upTo = db.eventsOnBranchUpTo(branch, targetSeq);
        if (upTo.size() != targetSeq) {
            throw new IllegalArgumentException("cannot move to seq " + targetSeq
                    + "; only " + upTo.size() + " events exist");
        }
        String headEvent = targetSeq == 0 ? null : upTo.get(upTo.size() - 1).id;
        db.transaction(() -> {
            db.setHead(branch, targetSeq, headEvent);
            db.logPointer(branch, targetSeq < row.headSeq() ? "undo" : "redo",
                    row.headSeq(), targetSeq, headEvent, actor, now(), note);
        });
    }

    public synchronized String forkBranch(String fromBranch, String newBranch, String actor, String description) {
        Database.BranchRow from = db.getBranch(fromBranch);
        if (from == null) {
            throw new IllegalArgumentException("unknown branch " + fromBranch);
        }
        if (db.getBranch(newBranch) != null) {
            throw new IllegalArgumentException("branch exists " + newBranch);
        }
        List<Event> events = db.eventsOnBranchUpTo(fromBranch, from.headSeq());
        final String[] newHead = new String[1];
        db.transaction(() -> {
            db.putBranch(newBranch, 0, null, fromBranch, now(), description);
            String parent = null;
            int seq = 0;
            for (Event e : events) {
                seq++;
                Map<String, Object> payload = Json.read(e.payloadJson, Map.class);
                String id = Ids.of("event", newBranch, Integer.toString(seq),
                        e.type, e.payloadJson, parent == null ? "" : parent);
                Event copy = new Event(id, Integer.toString(seq), newBranch, e.type, parent,
                        e.createdAt, e.actor, e.payloadJson, e.fingerprint,
                        e.sourceEventIdsJson, e.directSourceId, e.revisionOfId);
                db.insertEvent(copy, seq - 1);
                parent = id;
            }
            db.setHead(newBranch, seq, parent);
            db.logPointer(newBranch, "fork", 0, seq, parent, actor, now(),
                    "forked from " + fromBranch + "@" + from.headSeq());
            newHead[0] = parent;
        });
        return newBranch[0];
    }

    public synchronized State stateAt(String branch, int seq) {
        Database.BranchRow row = db.getBranch(branch);
        if (row == null) {
            throw new IllegalArgumentException("unknown branch " + branch);
        }
        int target = seq < 0 ? row.headSeq() : Math.min(seq, row.headSeq());
        List<Event> events = db.eventsOnBranchUpTo(branch, target);
        return new Folder(db).fold(branch, target, events);
    }

    public synchronized State state(String branch) {
        return stateAt(branch, -1);
    }

    public List<Database.BranchRow> branches() {
        return db.listBranches();
    }
}
