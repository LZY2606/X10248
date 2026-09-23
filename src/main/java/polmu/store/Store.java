package polmu.store;

import com.fasterxml.jackson.databind.JsonNode;
import polmu.Ids;
import polmu.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence and orchestration. Raw sources, human rulings and derived
 * interpretations live in separate tables; nothing derived ever rewrites a
 * source blob, and every mutation is an event on a branch chain.
 */
public class Store {
    private final Connection c;

    public Store(Connection c) {
        this.c = c;
    }

    public Connection conn() {
        return c;
    }

    // ---------- records ----------

    public record SourceRec(String id, String kind, int version, String label, String unit,
                           String frame, byte[] raw, String importedAt) {}

    public record EventRec(String id, String branchId, int seq, String prevEventId, String kind,
                           String status, JsonNode payload, String fingerprint, String createdAt) {}

    public record DerivedRec(String id, String eventId, String branchId, String kind, String subject,
                             JsonNode value, String unit, String frame, String status,
                             String fingerprint, JsonNode diagnostics) {}

    public record BranchRec(String id, String name, String parentEventId, String headEventId, String createdAt) {}

    public static class ConflictException extends RuntimeException {
        public final String branchId;
        public final String expectedHead;
        public final String currentHead;
        public final List<EventRec> missedEvents;

        public ConflictException(String branchId, String expectedHead, String currentHead, List<EventRec> missed) {
            super("branch head moved: expected " + expectedHead + " but is " + currentHead);
            this.branchId = branchId;
            this.expectedHead = expectedHead;
            this.currentHead = currentHead;
            this.missedEvents = missed;
        }
    }

    // ---------- sources ----------

    /** Insert one source inside the current transaction. Raw bytes are stored verbatim. */
    public String addSource(String kind, int version, String label, String unit, String frame, byte[] raw) {
        String id = Ids.sourceId(kind, version, raw);
        try {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO source_blob(id,kind,version,label,unit,frame,raw,imported_at) VALUES(?,?,?,?,?,?,?,datetime('now'))");
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setInt(3, version);
            ps.setString(4, label);
            ps.setString(5, unit);
            ps.setString(6, frame);
            ps.setBytes(7, raw);
            ps.execute();
            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Import a batch of sources atomically: any failure rolls back only this
     * batch, never leaving half a record behind.
     */
    public List<String> importBatch(List<SourceRec> batch) {
        List<String> ids = new ArrayList<>();
        try {
            c.setAutoCommit(false);
            try {
                for (SourceRec s : batch)
                    ids.add(addSource(s.kind(), s.version(), s.label(), s.unit(), s.frame(), s.raw()));
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("import batch rolled back: " + e.getMessage(), e);
        }
        return ids;
    }

    public SourceRec source(String id) {
        try {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM source_blob WHERE id=?");
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new SourceRec(rs.getString("id"), rs.getString("kind"), rs.getInt("version"),
                    rs.getString("label"), rs.getString("unit"), rs.getString("frame"),
                    rs.getBytes("raw"), rs.getString("imported_at"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<SourceRec> sources() {
        List<SourceRec> out = new ArrayList<>();
        try {
            ResultSet rs = c.createStatement().executeQuery(
                    "SELECT * FROM source_blob ORDER BY kind, version, id");
            while (rs.next())
                out.add(new SourceRec(rs.getString("id"), rs.getString("kind"), rs.getInt("version"),
                        rs.getString("label"), rs.getString("unit"), rs.getString("frame"),
                        rs.getBytes("raw"), rs.getString("imported_at")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // ---------- branches ----------

    public String createBranch(String name, String parentEventId) {
        String id = Ids.branchId(name, parentEventId, System.nanoTime());
        try {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO branch(id,name,parent_event_id,head_event_id,created_at) VALUES(?,?,?,?,datetime('now'))");
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, parentEventId);
            ps.setString(4, parentEventId);
            ps.execute();
            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BranchRec branch(String id) {
        try {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM branch WHERE id=?");
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new BranchRec(rs.getString("id"), rs.getString("name"),
                    rs.getString("parent_event_id"), rs.getString("head_event_id"), rs.getString("created_at"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<BranchRec> branches() {
        List<BranchRec> out = new ArrayList<>();
        try {
            ResultSet rs = c.createStatement().executeQuery("SELECT * FROM branch ORDER BY created_at, id");
            while (rs.next())
                out.add(new BranchRec(rs.getString("id"), rs.getString("name"),
                        rs.getString("parent_event_id"), rs.getString("head_event_id"), rs.getString("created_at")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // ---------- events ----------

    /**
     * Append an event to a branch with optimistic concurrency: the caller must
     * name the head it based its decision on. If another window saved first,
     * a ConflictException carries the events the caller missed.
     */
    public synchronized String appendEvent(String branchId, String kind, String status, JsonNode payload,
                                           String fingerprint, List<String> deps, String expectedHead) {
        BranchRec br = branch(branchId);
        if (br == null) throw new IllegalArgumentException("no branch " + branchId);
        String currentHead = br.headEventId();
        if (expectedHead != null && !expectedHead.equals(currentHead)) {
            throw new ConflictException(branchId, expectedHead, currentHead, eventsAfter(branchId, expectedHead));
        }
        int seq = currentHead == null ? 1 : event(currentHead).seq() + 1;
        String payloadJson = Json.write(payload);
        String id = Ids.eventId(branchId, seq, payloadJson);
        try {
            c.setAutoCommit(false);
            try {
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO event(id,branch_id,seq,prev_event_id,kind,status,payload,fingerprint,created_at) VALUES(?,?,?,?,?,?,?,?,datetime('now'))");
                ps.setString(1, id);
                ps.setString(2, branchId);
                ps.setInt(3, seq);
                ps.setString(4, currentHead);
                ps.setString(5, kind);
                ps.setString(6, status);
                ps.setString(7, payloadJson);
                ps.setString(8, fingerprint);
                ps.execute();
                if (deps != null) {
                    PreparedStatement pd = c.prepareStatement("INSERT OR IGNORE INTO event_dep(event_id,depends_on) VALUES(?,?)");
                    for (String d : deps) {
                        pd.setString(1, id);
                        pd.setString(2, d);
                        pd.execute();
                    }
                }
                PreparedStatement pb = c.prepareStatement("UPDATE branch SET head_event_id=? WHERE id=?");
                pb.setString(1, id);
                pb.setString(2, branchId);
                pb.execute();
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return id;
    }

    public EventRec event(String id) {
        try {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM event WHERE id=?");
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return toEvent(rs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static EventRec toEvent(ResultSet rs) throws Exception {
        return new EventRec(rs.getString("id"), rs.getString("branch_id"), rs.getInt("seq"),
                rs.getString("prev_event_id"), rs.getString("kind"), rs.getString("status"),
                Json.parse(rs.getString("payload")), rs.getString("fingerprint"), rs.getString("created_at"));
    }

    /** Events of a branch in chain order (oldest first). */
    public List<EventRec> events(String branchId) {
        List<EventRec> out = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM event WHERE branch_id=? ORDER BY seq");
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(toEvent(rs));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<EventRec> eventsAfter(String branchId, String eventId) {
        List<EventRec> all = events(branchId);
        List<EventRec> out = new ArrayList<>();
        boolean seen = eventId == null;
        for (EventRec e : all) {
            if (seen) out.add(e);
            if (e.id().equals(eventId)) seen = true;
        }
        return out;
    }

    // ---------- derived ----------

    public void addDerived(String eventId, String branchId, String kind, String subject, JsonNode value,
                           String unit, String frame, String status, String fingerprint,
                           List<String> depSourceIds, JsonNode diagnostics) {
        String id = Ids.derivedId(fingerprint, kind, subject);
        try {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO derived(id,event_id,branch_id,kind,subject,value,unit,frame,status,fingerprint,diagnostics) VALUES(?,?,?,?,?,?,?,?,?,?,?)");
            ps.setString(1, id);
            ps.setString(2, eventId);
            ps.setString(3, branchId);
            ps.setString(4, kind);
            ps.setString(5, subject);
            ps.setString(6, Json.write(value));
            ps.setString(7, unit);
            ps.setString(8, frame);
            ps.setString(9, status);
            ps.setString(10, fingerprint);
            ps.setString(11, diagnostics == null ? null : Json.write(diagnostics));
            ps.execute();
            if (depSourceIds != null) {
                PreparedStatement pd = c.prepareStatement("INSERT OR IGNORE INTO derived_dep(derived_id,source_id) VALUES(?,?)");
                for (String s : depSourceIds) {
                    pd.setString(1, id);
                    pd.setString(2, s);
                    pd.execute();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<DerivedRec> derivedForBranch(String branchId) {
        List<DerivedRec> out = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT * FROM derived WHERE branch_id=? ORDER BY kind, subject");
            ps.setString(1, branchId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(toDerived(rs));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static DerivedRec toDerived(ResultSet rs) throws Exception {
        String diag = rs.getString("diagnostics");
        return new DerivedRec(rs.getString("id"), rs.getString("event_id"), rs.getString("branch_id"),
                rs.getString("kind"), rs.getString("subject"), Json.parse(rs.getString("value")),
                rs.getString("unit"), rs.getString("frame"), rs.getString("status"),
                rs.getString("fingerprint"), diag == null ? null : Json.parse(diag));
    }

    /** Derived rows anywhere that directly depend on the given source id. */
    public List<DerivedRec> derivedDependingOn(String sourceId) {
        List<DerivedRec> out = new ArrayList<>();
        try {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT d.* FROM derived d JOIN derived_dep dd ON d.id=dd.derived_id WHERE dd.source_id=?");
            ps.setString(1, sourceId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(toDerived(rs));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // ---------- replay helpers ----------

    /**
     * Visible rulings on a branch up to (and including) the given event:
     * RULING events applied in order, with UNDO events reverting their target.
     */
    public Map<String, EventRec> visibleRulings(String branchId, String upToEventId) {
        Map<String, EventRec> rulings = new LinkedHashMap<>();
        List<String> undone = new ArrayList<>();
        for (EventRec e : events(branchId)) {
            if ("RULING".equals(e.kind()) && !undone.contains(e.id()))
                rulings.put(e.payload().get("candidateId").asText(), e);
            if ("UNDO".equals(e.kind())) {
                String target = e.payload().get("revertedEventId").asText();
                undone.add(target);
                rulings.values().removeIf(r -> r.id().equals(target));
            }
            if (e.id().equals(upToEventId)) break;
        }
        return rulings;
    }

    /** Ids of derived rows invalidated by revision events up to the given event. */
    public List<String> invalidatedAt(String branchId, String upToEventId) {
        List<String> out = new ArrayList<>();
        for (EventRec e : events(branchId)) {
            if (("SOURCE_REVISION".equals(e.kind()) || "RULE_REVISION".equals(e.kind()))
                    && e.payload().has("invalidated"))
                for (JsonNode n : e.payload().get("invalidated")) out.add(n.asText());
            if (e.id().equals(upToEventId)) break;
        }
        return out;
    }
}
