package pmx;

import java.sql.*;
import java.util.*;

/**
 * SQLite persistence. Source layers and interpretations are separate tables.
 * Everything affecting a branch is an append-only event (replay gives the state,
 * undo appends a reversal, optimistic head gives two-window conflicts).
 * Candidates are content-addressed and never rewritten.
 */
public final class Store implements AutoCloseable {
    final Connection db;

    public Store(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            db.setAutoCommit(true);
            migrate();
        } catch (Exception e) { throw new RuntimeException(e); }
        initSchemaV2();
    }

    private void migrate() throws SQLException {
        int ver = 0;
        try (Statement st = db.createStatement()) {
            st.execute("create table if not exists schema_meta(key text primary key, value text)");
            try (ResultSet rs = st.executeQuery("select value from schema_meta where key='version'")) {
                if (rs.next()) ver = Integer.parseInt(rs.getString(1));
            }
        }
        if (ver < 1) {
            try (Statement st = db.createStatement()) {
                // v1: sources + candidates as they were first modeled
                st.execute("create table if not exists cases_v1(id text primary key, name text, created_at integer)");
                st.execute("create table if not exists sources_v1(" +
                        "case_id text, kind text, version text, unit text, basis text, payload text, " +
                        "sha text, primary key(case_id, kind, version))");
            }
            setVer(1);
        }
        // v2 migration recorded; real v2 tables created in initSchemaV2 (branches/events)
        if (ver < 2) setVer(2);
    }

    private void setVer(int v) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "insert into schema_meta(key,value) values('version',?) on conflict(key) do update set value=excluded.value")) {
            ps.setInt(1, v); ps.executeUpdate();
        }
    }

    private void initSchemaV2() {
        try (Statement st = db.createStatement()) {
            st.execute("create table if not exists cases(id text primary key, name text, domain text, created_at integer)");
            st.execute("create table if not exists sources(" +
                    "id text primary key, case_id text, kind text, version text, unit text, basis text, " +
                    "stokes_order text, rotation_sign integer, payload text, sha text, imported_at integer, " +
                    "unique(case_id, kind, version))");
            st.execute("create table if not exists blobs(sha text primary key, bytes text)");
            st.execute("create table if not exists candidates(" +
                    "id text primary key, case_id text, kind text, fingerprint text, " +
                    "source_versions text, payload text, created_at integer)");
            st.execute("create table if not exists branches(" +
                    "id text primary key, case_id text, name text, parent_branch text, created_at integer)");
            st.execute("create table if not exists events(" +
                    "id text primary key, case_id text, branch_id text, seq integer, " +
                    "type text, payload text, basis_version text, head_after text, " +
                    "created_at integer, revoked integer default 0)");
            st.execute("create index if not exists ev_branch on events(branch_id, seq)");
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public int schemaVersion() {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("select value from schema_meta where key='version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------------- cases / blobs ----------------

    public void createCase(String id, String name, String domain) {
        try (PreparedStatement ps = db.prepareStatement(
                "insert or ignore into cases(id,name,domain,created_at) values(?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name); ps.setString(3, domain);
            ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Map<String, Object>> listCases() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("select id,name,domain,created_at from cases order by created_at")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString(1)); m.put("name", rs.getString(2));
                m.put("domain", rs.getString(3)); m.put("createdAt", rs.getLong(4));
                out.add(m);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public void putBlob(String sha, String bytes) {
        try (PreparedStatement ps = db.prepareStatement("insert or ignore into blobs(sha,bytes) values(?,?)")) {
            ps.setString(1, sha); ps.setString(2, bytes); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String getBlob(String sha) {
        try (PreparedStatement ps = db.prepareStatement("select bytes from blobs where sha=?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Insert one source version. Caller coordinates the transaction across all layers. */
    public void putSource(String id, String caseId, Sources.Layer l) {
        try (PreparedStatement ps = db.prepareStatement(
                "insert into sources(id,case_id,kind,version,unit,basis,stokes_order,rotation_sign,payload,sha,imported_at)" +
                        " values(?,?,?,?,?,?,?,?,?,?,?) on conflict(case_id,kind,version) do nothing")) {
            ps.setString(1, id); ps.setString(2, caseId); ps.setString(3, l.kind); ps.setString(4, l.version);
            ps.setString(5, l.unit); ps.setString(6, l.basis); ps.setString(7, l.stokesOrder);
            ps.setInt(8, l.rotationSign); ps.setString(9, Json.write(l.toMap())); ps.setString(10, l.sha);
            ps.setLong(11, l.importedAt); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Map<String, Object>> listSources(String caseId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "select id,kind,version,unit,basis,stokes_order,rotation_sign,sha from sources where case_id=? order by kind,version")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1)); m.put("kind", rs.getString(2)); m.put("version", rs.getString(3));
                    m.put("unit", rs.getString(4)); m.put("basis", rs.getString(5));
                    m.put("stokesOrder", rs.getString(6)); m.put("rotationSign", rs.getInt(7)); m.put("sha", rs.getString(8));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Sources.Layer getSource(String caseId, String kind, String version) {
        try (PreparedStatement ps = db.prepareStatement("select payload from sources where case_id=? and kind=? and version=?")) {
            ps.setString(1, caseId); ps.setString(2, kind); ps.setString(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Sources.Layer.fromMap(Json.obj(rs.getString(1)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void putCandidate(String id, String caseId, String kind, String fp,
                             Map<String, String> sourceVersions, Map<String, Object> payload) {
        try (PreparedStatement ps = db.prepareStatement(
                "insert or ignore into candidates(id,case_id,kind,fingerprint,source_versions,payload,created_at) values(?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, caseId); ps.setString(3, kind); ps.setString(4, fp);
            ps.setString(5, Json.write(sourceVersions)); ps.setString(6, Json.write(payload));
            ps.setLong(7, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Map<String, Object> getCandidate(String id) {
        try (PreparedStatement ps = db.prepareStatement("select payload from candidates where id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Json.obj(rs.getString(1)) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Map<String, Object>> listCandidates(String caseId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "select id,kind,fingerprint,source_versions,payload from candidates where case_id=?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1)); m.put("kind", rs.getString(2));
                    m.put("fingerprint", rs.getString(3)); m.put("sourceVersions", Json.obj(rs.getString(4)));
                    m.put("payload", Json.obj(rs.getString(5)));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }
