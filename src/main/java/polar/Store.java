package polar;

import polar.Model.*;
import java.sql.*;
import java.util.*;

/**
 * SQLite persistence. Schema is versioned via PRAGMA user_version.
 *   v1: baseline tables (sources, events, branches, derived).
 *   v2: adds sources.meta_json, events.fingerprint, derived.fingerprint, derived.status.
 * Import failures roll back the whole transaction: no half-written records.
 */
public final class Store implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;
    private final Connection conn;

    public Store(String path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(true);
            migrate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Store inMemory() { return new Store(":memory:"); }

    private void migrate() throws SQLException {
        int v = userVersion();
        if (v == 0) {
            exec("CREATE TABLE sources(id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INTEGER NOT NULL, content BLOB NOT NULL, media_type TEXT NOT NULL)");
            exec("CREATE TABLE events(id TEXT PRIMARY KEY, branch TEXT NOT NULL, seq INTEGER NOT NULL, parent_id TEXT, type TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)");
            exec("CREATE TABLE branches(name TEXT PRIMARY KEY, head_event TEXT, version INTEGER NOT NULL DEFAULT 0)");
            exec("CREATE TABLE derived(id TEXT NOT NULL, branch TEXT NOT NULL, kind TEXT NOT NULL, created_by_event TEXT NOT NULL, source_deps TEXT NOT NULL, derived_deps TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id, branch))");
            exec("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            setUserVersion(1);
            v = 1;
        }
        if (v == 1) {
            exec("ALTER TABLE sources ADD COLUMN meta_json TEXT NOT NULL DEFAULT '{}'");
            exec("ALTER TABLE events ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''");
            exec("ALTER TABLE derived ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''");
            exec("ALTER TABLE derived ADD COLUMN status TEXT NOT NULL DEFAULT 'OK'");
            setUserVersion(2);
        }
    }

    public int userVersion() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        }
    }

    private void setUserVersion(int v) throws SQLException { exec("PRAGMA user_version = " + v); }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }

    public void begin() {
        try { exec("BEGIN IMMEDIATE"); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void commit() {
        try { exec("COMMIT"); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void rollback() {
        try { exec("ROLLBACK"); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Runs the block in a transaction; any failure rolls back the whole change. */
    public <T> T tx(java.util.function.Supplier<T> body) {
        begin();
        try {
            T r = body.get();
            commit();
            return r;
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    // ---- sources ----

    public void putSource(Source s) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sources(id,kind,version,content,media_type,meta_json) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, s.id()); ps.setString(2, s.kind().wire); ps.setInt(3, s.version());
            ps.setBytes(4, s.content()); ps.setString(5, s.mediaType()); ps.setString(6, s.metaJson());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Source getSource(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM sources WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Source(rs.getString("id"), SourceKind.of(rs.getString("kind")),
                        rs.getInt("version"), rs.getBytes("content"), rs.getString("media_type"),
                        rs.getString("meta_json"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Source> allSources() {
        List<Source> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM sources ORDER BY kind, version")) {
            while (rs.next()) {
                out.add(new Source(rs.getString("id"), SourceKind.of(rs.getString("kind")),
                        rs.getInt("version"), rs.getBytes("content"), rs.getString("media_type"),
                        rs.getString("meta_json")));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public int nextSourceVersion(SourceKind kind) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(version),0)+1 FROM sources WHERE kind=?")) {
            ps.setString(1, kind.wire);
            try (ResultSet rs = ps.executeQuery()) { return rs.getInt(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---- events ----

    public void putEvent(Event e) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(id,branch,seq,parent_id,type,payload,ts,fingerprint) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.id()); ps.setString(2, e.branch()); ps.setLong(3, e.seq());
            ps.setString(4, e.parentId()); ps.setString(5, e.type().name());
            ps.setString(6, e.payloadJson()); ps.setLong(7, e.ts()); ps.setString(8, e.fingerprint());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Event getEvent(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM events WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToEvent(rs) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Event rowToEvent(ResultSet rs) throws SQLException {
        return new Event(rs.getString("id"), rs.getString("branch"), rs.getLong("seq"),
                rs.getString("parent_id"), EventType.valueOf(rs.getString("type")),
                rs.getString("payload"), rs.getString("fingerprint"), rs.getLong("ts"));
    }

    public List<Event> eventsOfBranch(String branch) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM events WHERE branch=? ORDER BY seq")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rowToEvent(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Event> allEvents() {
        List<Event> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM events ORDER BY seq, id")) {
            while (rs.next()) out.add(rowToEvent(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---- branches ----

    public void putBranch(Branch b) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branches(name,head_event,version) VALUES(?,?,?) " +
                "ON CONFLICT(name) DO UPDATE SET head_event=excluded.head_event, version=excluded.version")) {
            ps.setString(1, b.name()); ps.setString(2, b.headEvent()); ps.setLong(3, b.version());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Branch getBranch(String name) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM branches WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Branch(rs.getString("name"), rs.getString("head_event"), rs.getLong("version"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Branch> allBranches() {
        List<Branch> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM branches ORDER BY name")) {
            while (rs.next()) out.add(new Branch(rs.getString("name"), rs.getString("head_event"), rs.getLong("version")));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---- derived ----

    public void putDerived(Derived d) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO derived(id,branch,kind,created_by_event,source_deps,derived_deps,payload,fingerprint,status) " +
                "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.id()); ps.setString(2, d.branch()); ps.setString(3, d.kind().name());
            ps.setString(4, d.createdByEvent());
            ps.setString(5, Model.write(Model.JSON.valueToTree(d.sourceDeps())));
            ps.setString(6, Model.write(Model.JSON.valueToTree(d.derivedDeps())));
            ps.setString(7, d.payloadJson()); ps.setString(8, d.fingerprint()); ps.setString(9, d.status());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Derived> derivedOfBranch(String branch) {
        List<Derived> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM derived WHERE branch=? ORDER BY kind, id")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rowToDerived(rs)); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Derived> allDerived() {
        List<Derived> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM derived ORDER BY branch, kind, id")) {
            while (rs.next()) out.add(rowToDerived(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private Derived rowToDerived(ResultSet rs) throws SQLException {
        List<String> sd = new ArrayList<>(), dd = new ArrayList<>();
        Model.parse(rs.getString("source_deps")).forEach(n -> sd.add(n.asText()));
        Model.parse(rs.getString("derived_deps")).forEach(n -> dd.add(n.asText()));
        return new Derived(rs.getString("id"), rs.getString("branch"),
                DerivedKind.valueOf(rs.getString("kind")), rs.getString("created_by_event"),
                sd, dd, rs.getString("payload"), rs.getString("status"), rs.getString("fingerprint"));
    }

    // ---- meta (event counter) ----

    public long nextSeq(String key) {
        try {
            long v;
            try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM meta WHERE k=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { v = rs.next() ? Long.parseLong(rs.getString(1)) : 0; }
            }
            v++;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                ps.setString(1, key); ps.setString(2, Long.toString(v)); ps.executeUpdate();
            }
            return v;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Deletes everything (used before re-import). */
    public void wipe() {
        tx(() -> {
            try {
                exec("DELETE FROM derived"); exec("DELETE FROM events");
                exec("DELETE FROM branches"); exec("DELETE FROM sources"); exec("DELETE FROM meta");
            } catch (SQLException e) { throw new RuntimeException(e); }
            return null;
        });
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
