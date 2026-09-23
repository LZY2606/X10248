package org.polarroom.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.polarroom.core.Conventions;
import org.polarroom.core.Models;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** SQLite-backed event store with content-addressed blobs. */
public final class Db implements AutoCloseable {

    private final Connection conn;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final ObjectMapper mapper = new ObjectMapper();

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            init();
            conn.commit();
        } catch (Exception e) {
            throw new IllegalStateException("cannot open db " + path, e);
        }
    }

    private void init() throws SQLException {
        Integer v = getUserVersion();
        if (v == null) {
            for (String ddl : Schema.V1) {
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                }
            }
            setUserVersion(1);
            v = 1;
        }
        if (v < 2) {
            for (String ddl : Schema.V2) {
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                }
            }
            setUserVersion(2);
        }
    }

    private Integer getUserVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private void setUserVersion(int v) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version=" + v);
        }
    }

    java.sql.Connection connection() {
        return conn;
    }

    public int schemaVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void commit() {
        writeLock.lock();
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            writeLock.unlock();
        }
    }

    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Runs work in a single transaction; any failure rolls everything back. */
    public <T> T transaction(java.util.function.Function<Db, T> work) {
        writeLock.lock();
        try {
            T out = work.apply(this);
            conn.commit();
            return out;
        } catch (RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            throw e;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------------------- blobs

    /** Stores bytes under their sha256; identical bytes reuse the same row. */
    public String putBlob(byte[] content, String mediaType) {
        String sha = Conventions.sha256Hex(content);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO blob(sha256, media_type, content) VALUES(?,?,?)")) {
            ps.setString(1, sha);
            ps.setString(2, mediaType);
            ps.setBytes(3, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return sha;
    }

    public byte[] getBlob(String sha) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM blob WHERE sha256=?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listBlobs() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sha256, media_type, length(content) AS n FROM blob ORDER BY sha256");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sha256", rs.getString(1));
                m.put("mediaType", rs.getString(2));
                m.put("bytes", rs.getInt(3));
                out.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // -------------------------------------------------------------- sources

    public void addSourceVersion(Models.SourceVersion v) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source_version(material_id, material_type, version_no, "
                        + "blob_sha, import_event_id, original_filename, note) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, v.materialId);
            ps.setString(2, v.type.code);
            ps.setInt(3, v.versionNo);
            ps.setString(4, v.blobSha);
            ps.setString(5, v.importEventId);
            ps.setString(6, v.originalFilename);
            ps.setString(7, v.note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Models.SourceVersion getSourceVersion(String materialId, int versionNo) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT material_id, material_type, version_no, blob_sha, import_event_id, "
                        + "original_filename, note FROM source_version "
                        + "WHERE material_id=? AND version_no=?")) {
            ps.setString(1, materialId);
            ps.setInt(2, versionNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSource(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int latestVersionNo(String materialId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(version_no),0) FROM source_version WHERE material_id=?")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Models.SourceVersion> listLatestSources() {
        List<Models.SourceVersion> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT s.material_id, s.material_type, s.version_no, s.blob_sha, "
                        + "s.import_event_id, s.original_filename, s.note "
                        + "FROM source_version s JOIN ("
                        + "SELECT material_id, MAX(version_no) mx FROM source_version GROUP BY material_id"
                        + ") t ON t.material_id=s.material_id AND s.version_no=t.mx "
                        + "ORDER BY s.material_type, s.material_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readSource(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<Models.SourceVersion> listAllSourceVersions() {
        List<Models.SourceVersion> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT material_id, material_type, version_no, blob_sha, import_event_id, "
                        + "original_filename, note FROM source_version "
                        + "ORDER BY material_id, version_no");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readSource(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private Models.SourceVersion readSource(ResultSet rs) throws SQLException {
        Models.SourceVersion v = new Models.SourceVersion();
        v.materialId = rs.getString(1);
        v.type = Models.MaterialType.fromCode(rs.getString(2));
        v.versionNo = rs.getInt(3);
        v.blobSha = rs.getString(4);
        v.importEventId = rs.getString(5);
        v.originalFilename = rs.getString(6);
        v.note = rs.getString(7);
        return v;
    }

    public void putUnitBasis(String materialId, int versionNo, String field, String basis) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO unit_basis(material_id, version_no, field, unit_or_basis) "
                        + "VALUES(?,?,?,?)")) {
            ps.setString(1, materialId);
            ps.setInt(2, versionNo);
            ps.setString(3, field);
            ps.setString(4, basis);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- branches

    public void insertBranch(Models.Branch b) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branch(branch_id, name, parent_branch_id, base_event_id, "
                        + "head_event_id, purpose, created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, b.branchId);
            ps.setString(2, b.name);
            ps.setString(3, b.parentBranchId);
            ps.setString(4, b.baseEventId);
            ps.setString(5, b.headEventId);
            ps.setString(6, b.purpose);
            ps.setLong(7, b.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Models.Branch getBranch(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT branch_id, name, parent_branch_id, base_event_id, head_event_id, "
                        + "purpose, created_at FROM branch WHERE branch_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readBranch(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Models.Branch> listBranches() {
        List<Models.Branch> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT branch_id, name, parent_branch_id, base_event_id, head_event_id, "
                        + "purpose, created_at FROM branch ORDER BY created_at, branch_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(readBranch(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private Models.Branch readBranch(ResultSet rs) throws SQLException {
        Models.Branch b = new Models.Branch();
        b.branchId = rs.getString(1);
        b.name = rs.getString(2);
        b.parentBranchId = rs.getString(3);
        b.baseEventId = rs.getString(4);
        b.headEventId = rs.getString(5);
        b.purpose = rs.getString(6);
        b.createdAt = rs.getLong(7);
        return b;
    }

    // ---------------------------------------------------------------- events

    /**
     * Appends an event with optimistic concurrency: the caller states the head
     * it branched from. If another committer advanced the branch first, a
     * {@link VersionConflictException} is raised and nothing is written.
     */
    public Models.Event appendEvent(String branchId, String kind, Object payload,
                                    String expectedParent, String authorNote,
                                    String revertedOfEventId) {
        writeLock.lock();
        try {
            Models.Branch b = getBranch(branchId);
            String currentHead = b.headEventId;
            boolean same = (currentHead == null && expectedParent == null)
                    || (currentHead != null && currentHead.equals(expectedParent));
            if (!same) {
                throw new VersionConflictException(expectedParent, currentHead);
            }
            int seq = countEvents(branchId) + 1;
            String payloadJson;
            try {
                payloadJson = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            String fpInput = "in_" + Conventions.sha256Hex(
                    (branchId + "|" + seq + "|" + kind + "|" + payloadJson)
                            .getBytes(StandardCharsets.UTF_8)).substring(0, 20);
            String eventId = "ev_" + Conventions.sha256Hex(
                    (branchId + "|" + seq + "|" + payloadJson + "|" + (currentHead == null ? "" : currentHead))
                            .getBytes(StandardCharsets.UTF_8)).substring(0, 18);
            Models.Event e = new Models.Event();
            e.eventId = eventId;
            e.branchId = branchId;
            e.seq = seq;
            e.parentEventId = currentHead;
            e.kind = kind;
            e.payloadJson = payloadJson;
            e.inputFingerprint = fpInput;
            e.createdAt = System.currentTimeMillis();
            e.authorNote = authorNote;
            e.revertedOfEventId = revertedOfEventId;
            insertEvent(e);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE branch SET head_event_id=? WHERE branch_id=?")) {
                ps.setString(1, eventId);
                ps.setString(2, branchId);
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
            return e;
        } finally {
            writeLock.unlock();
        }
    }

    private int countEvents(String branchId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertEvent(Models.Event e) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event(event_id, branch_id, seq, parent_event_id, kind, "
                        + "payload_json, author_note, reverted_of_event_id, created_at, "
                        + "input_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.eventId);
            ps.setString(2, e.branchId);
            ps.setInt(3, e.seq);
            ps.setString(4, e.parentEventId);
            ps.setString(5, e.kind);
            ps.setString(6, e.payloadJson);
            ps.setString(7, e.authorNote);
            ps.setString(8, e.revertedOfEventId);
            ps.setLong(9, e.createdAt);
            ps.setString(10, e.inputFingerprint);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public Models.Event getEvent(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id, branch_id, seq, parent_event_id, kind, payload_json, "
                        + "author_note, reverted_of_event_id, created_at, input_fingerprint "
                        + "FROM event WHERE event_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readEvent(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Models.Event> listEvents(String branchId) {
        List<Models.Event> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT event_id, branch_id, seq, parent_event_id, kind, payload_json, "
                        + "author_note, reverted_of_event_id, created_at, input_fingerprint "
                        + "FROM event WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readEvent(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Events of a branch up to and including a given event id (a prefix view). */
    public List<Models.Event> eventsUntil(String branchId, String eventId) {
        List<Models.Event> all = listEvents(branchId);
        List<Models.Event> out = new ArrayList<>();
        for (Models.Event e : all) {
            out.add(e);
            if (e.eventId.equals(eventId)) {
                break;
            }
        }
        return out;
    }

    private Models.Event readEvent(ResultSet rs) throws SQLException {
        Models.Event e = new Models.Event();
        e.eventId = rs.getString(1);
        e.branchId = rs.getString(2);
        e.seq = rs.getInt(3);
        e.parentEventId = rs.getString(4);
        e.kind = rs.getString(5);
        e.payloadJson = rs.getString(6);
        e.authorNote = rs.getString(7);
        e.revertedOfEventId = rs.getString(8);
        e.createdAt = rs.getLong(9);
        e.inputFingerprint = rs.getString(10);
        return e;
    }

    // -------------------------------------------------------------- derived

    public void putDerived(Models.DerivedRecord r) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO derived(derived_id, branch_id, event_id, kind, "
                        + "candidate_key, fingerprint, json) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, r.derivedId);
            ps.setString(2, r.branchId);
            ps.setString(3, r.eventId);
            ps.setString(4, r.kind);
            ps.setString(5, r.candidateKey);
            ps.setString(6, r.fingerprint);
            ps.setString(7, r.json);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Models.DerivedRecord> listDerivedAt(String branchId, String eventId) {
        List<Models.DerivedRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT derived_id, branch_id, event_id, kind, candidate_key, fingerprint, json "
                        + "FROM derived WHERE branch_id=? AND event_id=? "
                        + "ORDER BY kind, candidate_key")) {
            ps.setString(1, branchId);
            ps.setString(2, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Models.DerivedRecord r = new Models.DerivedRecord();
                    r.derivedId = rs.getString(1);
                    r.branchId = rs.getString(2);
                    r.eventId = rs.getString(3);
                    r.kind = rs.getString(4);
                    r.candidateKey = rs.getString(5);
                    r.fingerprint = rs.getString(6);
                    r.json = rs.getString(7);
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<Models.DerivedRecord> listDerivedBranch(String branchId) {
        List<Models.DerivedRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT derived_id, branch_id, event_id, kind, candidate_key, fingerprint, json "
                        + "FROM derived WHERE branch_id=? ORDER BY event_id, kind, candidate_key")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Models.DerivedRecord r = new Models.DerivedRecord();
                    r.derivedId = rs.getString(1);
                    r.branchId = rs.getString(2);
                    r.eventId = rs.getString(3);
                    r.kind = rs.getString(4);
                    r.candidateKey = rs.getString(5);
                    r.fingerprint = rs.getString(6);
                    r.json = rs.getString(7);
                    out.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // --------------------------------------------------------------- rulings

    public void insertRuling(String branchId, String subjectKey, String eventId,
                             String decision, String note) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ruling(branch_id, subject_key, event_id, decision, note, created_at) "
                        + "VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, branchId);
            ps.setString(2, subjectKey);
            ps.setString(3, eventId);
            ps.setString(4, decision);
            ps.setString(5, note);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Replays rulings on an event prefix, applying reversions. */
    public Map<String, Models.RulingState> rulingStateAt(List<Models.Event> prefix) {
        Map<String, Models.RulingState> states = new LinkedHashMap<>();
        for (Models.Event e : prefix) {
            if ("ruling".equals(e.kind) || "revert_ruling".equals(e.kind)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = mapper.readValue(e.payloadJson, Map.class);
                    String key = (String) payload.get("subjectKey");
                    if ("revert_ruling".equals(e.kind)) {
                        states.remove(key);
                    } else {
                        Models.RulingState s = new Models.RulingState();
                        s.subjectKey = key;
                        s.decision = (String) payload.get("decision");
                        s.eventId = e.eventId;
                        s.note = (String) payload.get("note");
                        states.put(key, s);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        return states;
    }

    public String meta(String key) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM archive_meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void putMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO archive_meta(key, value) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
