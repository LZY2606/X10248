package pol.engine;

import pol.domain.Ids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Store {
    private final Connection c;

    public Store(Connection c) { this.c = c; }

    public Connection connection() { return c; }

    public <T> T tx(Function<Connection, T> body) {
        try {
            c.setAutoCommit(false);
            try {
                T out = body.apply(c);
                c.commit();
                return out;
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            } catch (SQLException e) {
                c.rollback();
                throw new IllegalStateException(e);
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> branchHead(String branch) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT branch, parent_branch, forked_at_seq, head_seq FROM branches WHERE branch = ?")) {
            p.setString(1, branch);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("unknown branch: " + branch);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("branch", rs.getString(1));
                m.put("parent_branch", rs.getString(2));
                m.put("forked_at_seq", rs.getInt(3));
                m.put("head_seq", rs.getInt(4));
                return m;
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public int headSeq(String branch) {
        return (int) branchHead(branch).get("head_seq");
    }

    public void createBranch(String name, String parent) {
        tx(conn -> {
            int parentSeq = headSeq(parent);
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO branches(branch, parent_branch, forked_at_seq, head_seq, created_utc) " +
                            "VALUES (?,?,?,?,?)")) {
                p.setString(1, name);
                p.setString(2, parent);
                p.setInt(3, parentSeq);
                p.setInt(4, parentSeq);
                p.setString(5, Ids.now());
                p.executeUpdate();
            } catch (SQLException e) { throw new IllegalStateException(e); }
            return null;
        });
    }

    public List<Map<String, Object>> listBranches() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT branch, parent_branch, forked_at_seq, head_seq FROM branches ORDER BY branch")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("branch", rs.getString(1));
                m.put("parent_branch", rs.getString(2));
                m.put("forked_at_seq", rs.getInt(3));
                m.put("head_seq", rs.getInt(4));
                out.add(m);
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public static final class Conflict extends RuntimeException {
        public final int expected, actual;
        public Conflict(int expected, int actual) {
            super("version conflict: expected head " + expected + ", actual " + actual);
            this.expected = expected; this.actual = actual;
        }
    }

    /** Append an event with optimistic concurrency on branch head. */
    public Map<String, Object> appendEvent(String branch, String type, String payloadJson,
                                           String basisEventId, String fingerprint,
                                           Integer expectedHead, String clientToken,
                                           String replayedFrom) {
        return tx(conn -> {
            int current = headSeq(branch);
            if (expectedHead != null && expectedHead != current) {
                throw new Conflict(expectedHead, current);
            }
            String eventId = Ids.uuid();
            int seq;
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO events(event_id, branch, type, payload_json, basis_event_id, fingerprint," +
                            " created_utc, replayed_from, client_token) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, eventId);
                p.setString(2, branch);
                p.setString(3, type);
                p.setString(4, payloadJson);
                p.setString(5, basisEventId);
                p.setString(6, fingerprint);
                p.setString(7, Ids.now());
                p.setString(8, replayedFrom);
                p.setString(9, clientToken);
                p.executeUpdate();
                try (ResultSet k = p.getGeneratedKeys()) { k.next(); seq = k.getInt(1); }
            } catch (SQLException e) { throw new IllegalStateException(e); }
            try (PreparedStatement p = conn.prepareStatement("UPDATE branches SET head_seq = ? WHERE branch = ?")) {
                p.setInt(1, seq);
                p.setString(2, branch);
                p.executeUpdate();
            } catch (SQLException e) { throw new IllegalStateException(e); }
            return eventById(seq);
        });
    }

    public Map<String, Object> eventById(int seq) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT seq, event_id, branch, type, payload_json, basis_event_id, fingerprint, created_utc, replayed_from " +
                        "FROM events WHERE seq = ?")) {
            p.setInt(1, seq);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return mapEvent(rs);
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> eventByEventId(String eventId) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT seq, event_id, branch, type, payload_json, basis_event_id, fingerprint, created_utc, replayed_from " +
                        "FROM events WHERE event_id = ?")) {
            p.setString(1, eventId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return mapEvent(rs);
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private Map<String, Object> mapEvent(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", rs.getInt(1));
        m.put("event_id", rs.getString(2));
        m.put("branch", rs.getString(3));
        m.put("type", rs.getString(4));
        m.put("payload", pol.domain.Json.readMap(rs.getString(5)));
        m.put("payload_json", rs.getString(5));
        m.put("basis_event_id", rs.getString(6));
        m.put("fingerprint", rs.getString(7));
        m.put("created_utc", rs.getString(8));
        m.put("replayed_from", rs.getString(9));
        return m;
    }

    public List<Map<String, Object>> eventsOnBranch(String branch, Integer atOrBefore) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT seq, event_id, branch, type, payload_json, basis_event_id, fingerprint, created_utc, replayed_from " +
                "FROM events WHERE branch = ?" + (atOrBefore == null ? "" : " AND seq <= " + atOrBefore) + " ORDER BY seq";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, branch);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(mapEvent(rs));
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public int forkedAt(String branch) {
        return (int) branchHead(branch).get("forked_at_seq");
    }

    /** Events visible on a branch: parent prefix up to fork, then branch's own. */
    public List<Map<String, Object>> visibleEvents(String branch, Integer atOrBefore) {
        Map<String, Object> h = branchHead(branch);
        String parent = (String) h.get("parent_branch");
        int fork = (int) h.get("forked_at_seq");
        List<Map<String, Object>> out = new ArrayList<>();
        if (parent != null) out.addAll(eventsOnBranch(parent, fork));
        out.addAll(eventsOnBranch(branch, atOrBefore));
        return out;
    }

    // ---- sources ----

    public void upsertSource(Connection conn, String sourceId, String kind, String materialId,
                             String displayName, Integer createdEvent, String supersededBy,
                             Integer supersededEvent) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO sources(source_id, kind, material_id, display_name, created_event, superseded_by, superseded_event) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT(source_id) DO UPDATE SET " +
                        "kind=excluded.kind, material_id=excluded.material_id, display_name=excluded.display_name," +
                        "superseded_by=COALESCE(excluded.superseded_by, sources.superseded_by)," +
                        "superseded_event=COALESCE(excluded.superseded_event, sources.superseded_event)")) {
            p.setString(1, sourceId); p.setString(2, kind); p.setString(3, materialId);
            p.setString(4, displayName);
            if (createdEvent == null) p.setNull(5, java.sql.Types.INTEGER); else p.setInt(5, createdEvent);
            p.setString(6, supersededBy);
            if (supersededEvent == null) p.setNull(7, java.sql.Types.INTEGER); else p.setInt(7, supersededEvent);
            p.executeUpdate();
        }
    }

    public void insertVersion(Connection conn, String versionId, String sourceId, int versionNo,
                              String rawRepr, String unit, String coordRef, String canonicalJson,
                              String parseStatus, String diagnostics, String sha, int eventSeq) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO source_versions(version_id, source_id, version_no, raw_repr, unit, coordinate_ref," +
                        " canonical_json, parse_status, diagnostics, sha256, created_event) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, versionId); p.setString(2, sourceId); p.setInt(3, versionNo);
            p.setString(4, rawRepr); p.setString(5, unit); p.setString(6, coordRef);
            p.setString(7, canonicalJson); p.setString(8, parseStatus); p.setString(9, diagnostics);
            p.setString(10, sha); p.setInt(11, eventSeq);
            p.executeUpdate();
        }
    }

    public int nextVersionNo(String sourceId) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM source_versions WHERE source_id = ?")) {
            p.setString(1, sourceId);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> latestVersion(String sourceId) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT version_id, version_no, raw_repr, unit, coordinate_ref, canonical_json, parse_status," +
                        " diagnostics, sha256, created_event FROM source_versions WHERE source_id = ? " +
                        "ORDER BY version_no DESC LIMIT 1")) {
            p.setString(1, sourceId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version_id", rs.getString(1));
                m.put("version_no", rs.getInt(2));
                m.put("raw_repr", rs.getString(3));
                m.put("unit", rs.getString(4));
                m.put("coordinate_ref", rs.getString(5));
                m.put("canonical", rs.getString(6) == null ? null : pol.domain.Json.readMap(rs.getString(6)));
                m.put("canonical_json", rs.getString(6));
                m.put("parse_status", rs.getString(7));
                m.put("diagnostics", pol.domain.Json.read(rs.getString(8)));
                m.put("sha256", rs.getString(9));
                m.put("created_event", rs.getInt(10));
                return m;
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public Map<String, Object> versionById(String versionId) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT v.source_id, version_id, version_no, raw_repr, unit, coordinate_ref, canonical_json, parse_status," +
                        " diagnostics, sha256, created_event, s.kind, s.material_id, s.display_name " +
                        "FROM source_versions v JOIN sources s ON s.source_id = v.source_id WHERE version_id = ?")) {
            p.setString(1, versionId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source_id", rs.getString(1));
                m.put("version_id", rs.getString(2));
                m.put("version_no", rs.getInt(3));
                m.put("raw_repr", rs.getString(4));
                m.put("unit", rs.getString(5));
                m.put("coordinate_ref", rs.getString(6));
                m.put("canonical", rs.getString(7) == null ? null : pol.domain.Json.readMap(rs.getString(7)));
                m.put("parse_status", rs.getString(8));
                m.put("diagnostics", pol.domain.Json.read(rs.getString(9)));
                m.put("sha256", rs.getString(10));
                m.put("created_event", rs.getInt(11));
                m.put("kind", rs.getString(12));
                m.put("material_id", rs.getString(13));
                m.put("display_name", rs.getString(14));
                return m;
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public List<Map<String, Object>> listSources(String materialId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT source_id, kind, material_id, display_name, superseded_by FROM sources" +
                (materialId == null ? "" : " WHERE material_id = ?") + " ORDER BY kind, source_id";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            if (materialId != null) p.setString(1, materialId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source_id", rs.getString(1));
                    m.put("kind", rs.getString(2));
                    m.put("material_id", rs.getString(3));
                    m.put("display_name", rs.getString(4));
                    m.put("superseded_by", rs.getString(5));
                    m.put("latest", latestVersion(rs.getString(1)));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public List<Map<String, Object>> listVersions(String sourceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT version_id, version_no, parse_status, sha256, created_event FROM source_versions " +
                        "WHERE source_id = ? ORDER BY version_no")) {
            p.setString(1, sourceId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version_id", rs.getString(1));
                    m.put("version_no", rs.getInt(2));
                    m.put("parse_status", rs.getString(3));
                    m.put("sha256", rs.getString(4));
                    m.put("created_event", rs.getInt(5));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }
}
