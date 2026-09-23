package poloptics.domain;

import poloptics.db.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Append-only store with atomic import, branches and optimistic event append. */
public final class Repository {
    private final Database db;
    public Repository(Database db) { this.db = db; }

    public static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID();
    }

    public static String now() { return Instant.now().toString(); }

    public static String shaStatic(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- atomic import ----------

    public static final class ImportItem {
        public final String kind, role, mediaType, unitOrDatum, originalText, versionTag;
        public ImportItem(String kind, String role, String mediaType, String unitOrDatum,
                          String originalText, String versionTag) {
            this.kind = kind; this.role = role; this.mediaType = mediaType;
            this.unitOrDatum = unitOrDatum; this.originalText = originalText; this.versionTag = versionTag;
        }
    }

    public static final class ImportResult {
        public final String batchId;
        public final List<String> blobIds;
        public final List<SourceBlob> blobs;
        ImportResult(String b, List<String> ids, List<SourceBlob> bs) {
            batchId = b; blobIds = ids; blobs = bs;
        }
    }

    /**
     * All-or-nothing import: any failure rolls back the whole transaction;
     * no half batch survives.
     */
    public ImportResult commitImport(String label, List<ImportItem> items, String validatorError) {
        String batchId = newId("batch");
        try {
            if (validatorError != null) throw new IllegalArgumentException(validatorError);
            try (PreparedStatement ps = db.cx.prepareStatement(
                    "INSERT INTO import_batch(batch_id,label,started_at,committed_at,status) VALUES (?,?,?,?,?)")) {
                ps.setString(1, batchId); ps.setString(2, label);
                ps.setString(3, now()); ps.setString(4, now()); ps.setString(5, "COMMITTED");
                ps.executeUpdate();
            }
            List<String> ids = new ArrayList<>();
            List<SourceBlob> blobs = new ArrayList<>();
            for (ImportItem it : items) {
                byte[] raw = it.originalText.getBytes(StandardCharsets.UTF_8);
                String hash = Database.sha256(raw);
                String blobId = "blob_" + hash.substring(0, 16);
                boolean exists;
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "SELECT 1 FROM source_blob WHERE blob_id=?")) {
                    ps.setString(1, blobId);
                    try (ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
                }
                if (!exists) {
                    try (PreparedStatement ps = db.cx.prepareStatement("""
                            INSERT INTO source_blob(blob_id,kind,media_type,unit_or_datum,original_text,
                              sha256,size_bytes,imported_at,parse_status,parse_diagnostic)
                            VALUES (?,?,?,?,?,?,?,?,?,?)""")) {
                        String status = "OK"; String diag = null;
                        if (it.kind.equals("WAVELENGTH") && it.originalText.contains("UNCONVERTIBLE")) {
                            status = "UNCONVERTIBLE"; diag = "波长无法转换到 nm，保留原始表示与单位，未参与绘图归一化。";
                        }
                        ps.setString(1, blobId); ps.setString(2, it.kind); ps.setString(3, it.mediaType);
                        ps.setString(4, it.unitOrDatum); ps.setString(5, it.originalText);
                        ps.setString(6, hash); ps.setLong(7, raw.length); ps.setString(8, now());
                        ps.setString(9, status); ps.setString(10, diag);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "INSERT INTO import_batch_item(batch_id,blob_id,item_role) VALUES (?,?,?)")) {
                    ps.setString(1, batchId); ps.setString(2, blobId); ps.setString(3, it.role);
                    ps.executeUpdate();
                }
                ids.add(blobId);
                blobs.add(getBlob(blobId));
            }
            db.cx.commit();
            return new ImportResult(batchId, ids, blobs);
        } catch (RuntimeException | SQLException ex) {
            rollbackQuiet();
            try {
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "INSERT INTO import_batch(batch_id,label,started_at,committed_at,status,rollback_reason) VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, batchId); ps.setString(2, label); ps.setString(3, now());
                    ps.setObject(4, null); ps.setString(5, "ROLLED_BACK");
                    ps.setString(6, String.valueOf(ex.getMessage()).substring(0, Math.min(300, String.valueOf(ex.getMessage()).length())));
                    ps.executeUpdate();
                }
                db.cx.commit();
            } catch (SQLException ignore) { rollbackQuiet(); }
            if (ex instanceof RuntimeException re) throw re;
            throw new RuntimeException(ex);
        }
    }

    private void rollbackQuiet() {
        try { db.cx.rollback(); } catch (SQLException ignored) {}
    }

    public SourceBlob getBlob(String blobId) {
        try (PreparedStatement ps = db.cx.prepareStatement("SELECT * FROM source_blob WHERE blob_id=?")) {
            ps.setString(1, blobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SourceBlob(rs.getString("blob_id"), rs.getString("kind"),
                        rs.getString("media_type"), rs.getString("unit_or_datum"),
                        rs.getString("original_text"), rs.getString("sha256"),
                        rs.getLong("size_bytes"), rs.getString("imported_at"),
                        rs.getString("parse_status"), rs.getString("parse_diagnostic"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- branches ----------

    public String createBranch(String name, String parentBranchId, String note) {
        String id = newId("branch");
        try (PreparedStatement ps = db.cx.prepareStatement(
                "INSERT INTO branch(branch_id,name,parent_branch_id,created_at,note) VALUES (?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name);
            ps.setString(3, parentBranchId); ps.setString(4, now()); ps.setString(5, note);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public List<String[]> listBranches() {
        List<String[]> out = new ArrayList<>();
        try (Statement st = db.cx.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT branch_id,name,parent_branch_id,note FROM branch ORDER BY created_at")) {
            while (rs.next()) out.add(new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)});
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- event log with optimistic concurrency ----------

    private long lockSeq(String branchId) throws SQLException {
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT head_seq, head_event_id, version FROM branch_head WHERE branch_id=? ")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    /**
     * Append event at expected branch version (number of existing events).
     * Throws ConflictException when another writer committed first; caller sees
     * the diverged events and both-side diff.
     */
    public synchronized Event appendEvent(String branchId, String kind, String actor,
                                          String payloadJson, String fingerprint, long expectedVersion) {
        try {
            long current;
            String parent;
            try (PreparedStatement ps = db.cx.prepareStatement(
                    "SELECT head_seq, head_event_id FROM branch_head WHERE branch_id=?")) {
                ps.setString(1, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { current = rs.getLong(1) + 1; parent = rs.getString(2); }
                    else { current = 0; parent = null; }
                }
            }
            if (current != expectedVersion) {
                List<String> diverged = new ArrayList<>();
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "SELECT seq,event_id,kind,actor,substr(payload_json,1,120) FROM event WHERE branch_id=? AND seq>=? ORDER BY seq")) {
                    ps.setString(1, branchId); ps.setLong(2, expectedVersion);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                        diverged.add(rs.getLong(1)+"|"+rs.getString(2)+"|"+rs.getString(3)+"|"+rs.getString(4)+"|"+rs.getString(5)); }
                }
                db.cx.rollback();
                throw new ConflictException(expectedVersion, current, diverged);
            }
            String eventId = newId("evt");
            try (PreparedStatement ps = db.cx.prepareStatement("""
                    INSERT INTO event(event_id,seq,branch_id,parent_event_id,kind,event_time,actor,payload_json,fingerprint)
                    VALUES (?,?,?,?,?,?,?,?,?)""")) {
                ps.setString(1, eventId); ps.setLong(2, current); ps.setString(3, branchId);
                ps.setString(4, parent); ps.setString(5, kind); ps.setString(6, now());
                ps.setString(7, actor); ps.setString(8, payloadJson); ps.setString(9, fingerprint);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.cx.prepareStatement("""
                    INSERT INTO branch_head(branch_id,head_event_id,head_seq,version) VALUES(?,?,?,1)
                    ON CONFLICT(branch_id) DO UPDATE SET head_event_id=excluded.head_event_id,
                      head_seq=excluded.head_seq, version=branch_head.version+1""")) {
                ps.setString(1, branchId); ps.setString(2, eventId); ps.setLong(3, current);
                ps.executeUpdate();
            }
            db.cx.commit();
            return new Event(eventId, current, branchId, parent, kind, now(), actor, payloadJson, fingerprint);
        } catch (ConflictException ce) {
            throw ce;
        } catch (SQLException e) {
            rollbackQuiet();
            throw new RuntimeException(e);
        }
    }

    public long branchVersion(String branchId) {
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT head_seq FROM branch_head WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1)+1 : 0; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Event> eventsAfter(String branchId, long seqExclusive) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT * FROM event WHERE branch_id=? AND seq>? ORDER BY seq")) {
            ps.setString(1, branchId); ps.setLong(2, seqExclusive);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(readEvent(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Event> allEvents(String branchId) { return eventsAfter(branchId, -1); }

    private Event readEvent(ResultSet rs) throws SQLException {
        return new Event(rs.getString("event_id"), rs.getLong("seq"), rs.getString("branch_id"),
                rs.getString("parent_event_id"), rs.getString("kind"), rs.getString("event_time"),
                rs.getString("actor"), rs.getString("payload_json"), rs.getString("fingerprint"));
    }

    public Event getEvent(String eventId) {
        try (PreparedStatement ps = db.cx.prepareStatement("SELECT * FROM event WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? readEvent(rs) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- source refs (versioned bindings) ----------

    public String addSourceRef(String branchId, String eventId, String role,
                               String blobId, String versionTag, String supersedesRefId) {
        String refId = newId("ref");
        try {
            if (supersedesRefId != null) {
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "UPDATE source_ref SET active=0 WHERE ref_id=?")) {
                    ps.setString(1, supersedesRefId); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = db.cx.prepareStatement("""
                    INSERT INTO source_ref(ref_id,branch_id,event_id,role,blob_id,version_tag,supersedes_ref_id,active)
                    VALUES (?,?,?,?,?,?,?,1)""")) {
                ps.setString(1, refId); ps.setString(2, branchId); ps.setString(3, eventId);
                ps.setString(4, role); ps.setString(5, blobId); ps.setString(6, versionTag);
                ps.setString(7, supersedesRefId);
                ps.executeUpdate();
            }
            db.cx.commit();
        } catch (SQLException e) { rollbackQuiet(); throw new RuntimeException(e); }
        return refId;
    }

    public record SourceRefRow(String refId, String eventId, String role, String blobId,
                               String versionTag, String supersedes, int active) {}

    public List<SourceRefRow> activeRefs(String branchId) {
        List<SourceRefRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT ref_id,event_id,role,blob_id,version_tag,supersedes_ref_id,active FROM source_ref WHERE branch_id=? ORDER BY rowid")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                out.add(new SourceRefRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getInt(7))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public SourceRefRow getRef(String refId) {
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT ref_id,event_id,role,blob_id,version_tag,supersedes_ref_id,active FROM source_ref WHERE ref_id=?")) {
            ps.setString(1, refId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next()
                ? new SourceRefRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getInt(7)) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- derived artifacts ----------

    public String putDerived(String branchId, String eventId, String kind, String candidateId,
                             String payloadJson, String dependsOnJson, boolean valid) {
        String id = newId("der");
        try (PreparedStatement ps = db.cx.prepareStatement("""
                INSERT INTO derived(derived_id,branch_id,event_id,kind,candidate_id,payload_json,depends_on_json,valid)
                VALUES (?,?,?,?,?,?,?,?)""")) {
            ps.setString(1,id); ps.setString(2,branchId); ps.setString(3,eventId); ps.setString(4,kind);
            ps.setString(5,candidateId); ps.setString(6,payloadJson); ps.setString(7,dependsOnJson);
            ps.setInt(8, valid?1:0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public record DerivedRow(String derivedId, String branchId, String eventId, String kind,
                             String candidateId, String payloadJson, String dependsOnJson, int valid) {}

    public List<DerivedRow> derivedForEvent(String branchId, String eventId) {
        List<DerivedRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT * FROM derived WHERE branch_id=? AND event_id=? ORDER BY rowid")) {
            ps.setString(1, branchId); ps.setString(2, eventId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                out.add(new DerivedRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<DerivedRow> derivedAtOrBefore(String branchId, long seq) {
        List<DerivedRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.cx.prepareStatement(
                """
                SELECT d.* FROM derived d JOIN event e ON e.event_id=d.event_id
                WHERE d.branch_id=? AND e.seq<=? ORDER BY d.rowid""")) {
            ps.setString(1, branchId); ps.setLong(2, seq);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                out.add(new DerivedRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- decisions ----------

    public String addDecision(String branchId, String eventId, String targetDerivedId,
                              String targetCandidateId, String verdict, String rationale,
                              String undoOfId) {
        String id = newId("dec");
        try {
            if (undoOfId != null) {
                try (PreparedStatement ps = db.cx.prepareStatement(
                        "UPDATE decision SET undone=1 WHERE decision_id=?")) {
                    ps.setString(1, undoOfId); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = db.cx.prepareStatement("""
                    INSERT INTO decision(decision_id,branch_id,event_id,target_derived_id,target_candidate_id,
                      verdict,rationale,undo_of_decision_id,undone,created_at)
                    VALUES (?,?,?,?,?,?,?,?,0,?)""")) {
                ps.setString(1,id); ps.setString(2,branchId); ps.setString(3,eventId);
                ps.setString(4,targetDerivedId); ps.setString(5,targetCandidateId);
                ps.setString(6,verdict); ps.setString(7,rationale); ps.setString(8,undoOfId);
                ps.setString(9,now());
                ps.executeUpdate();
            }
            db.cx.commit();
        } catch (SQLException e) { rollbackQuiet(); throw new RuntimeException(e); }
        return id;
    }

    public record DecisionRow(String decisionId, String eventId, String targetDerivedId,
                              String targetCandidateId, String verdict, String rationale,
                              String undoOf, int undone) {}

    public List<DecisionRow> decisionsForBranch(String branchId) {
        List<DecisionRow> out = new ArrayList<>();
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT decision_id,event_id,target_derived_id,target_candidate_id,verdict,rationale,undo_of_decision_id,undone "
              + "FROM decision WHERE branch_id=? ORDER BY rowid")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                out.add(new DecisionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8))); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public DecisionRow getDecision(String id) {
        try (PreparedStatement ps = db.cx.prepareStatement(
                "SELECT decision_id,event_id,target_derived_id,target_candidate_id,verdict,rationale,undo_of_decision_id,undone FROM decision WHERE decision_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next()
                ? new DecisionRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8)) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
