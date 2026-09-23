package polarlab.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** SQLite connection + schema migrations. */
public final class Db {
    private final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys=ON");
            c.setAutoCommit(true);
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public Connection conn() { return conn; }

    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }

    int schemaVersion() {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0; // table does not exist yet
        }
    }

    private void migrate() {
        try {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT)");
            int v = schemaVersion();
            if (v < 1) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE counters(prefix TEXT PRIMARY KEY, next INTEGER NOT NULL)");
                    st.execute("CREATE TABLE artifacts(id TEXT PRIMARY KEY, kind TEXT NOT NULL, version INTEGER NOT NULL,"
                            + " label TEXT NOT NULL, media_type TEXT NOT NULL, content BLOB NOT NULL,"
                            + " sha256 TEXT NOT NULL, units TEXT NOT NULL DEFAULT '', basis TEXT NOT NULL DEFAULT '',"
                            + " created_at TEXT NOT NULL)");
                    st.execute("CREATE TABLE branches(id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT,"
                            + " head_event_id TEXT, version INTEGER NOT NULL DEFAULT 0,"
                            + " rule_json TEXT NOT NULL, created_at TEXT NOT NULL)");
                    st.execute("CREATE TABLE branch_inputs(branch_id TEXT NOT NULL, kind TEXT NOT NULL,"
                            + " artifact_id TEXT NOT NULL, PRIMARY KEY(branch_id, kind))");
                    st.execute("CREATE TABLE derived(id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,"
                            + " fingerprint TEXT NOT NULL, rule_json TEXT NOT NULL, status TEXT NOT NULL,"
                            + " payload TEXT NOT NULL, input_ids TEXT NOT NULL,"
                            + " superseded INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)");
                    st.execute("CREATE TABLE events(id TEXT PRIMARY KEY, branch_id TEXT NOT NULL,"
                            + " seq INTEGER NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)");
                    st.execute("CREATE TABLE diagnostics(id TEXT PRIMARY KEY, owner_id TEXT NOT NULL,"
                            + " severity TEXT NOT NULL, message TEXT NOT NULL, created_at TEXT NOT NULL)");
                }
                recordMigration(1);
                v = 1;
            }
            if (v < 2) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE artifacts ADD COLUMN source_note TEXT NOT NULL DEFAULT ''");
                    st.execute("ALTER TABLE events ADD COLUMN restores TEXT NOT NULL DEFAULT ''");
                }
                recordMigration(2);
            }
        } catch (Exception e) {
            throw new RuntimeException("migration failed", e);
        }
    }

    private void recordMigration(int version) throws Exception {
        try (var ps = conn.prepareStatement(
                "INSERT INTO schema_migrations(version, applied_at) VALUES(?,?)")) {
            ps.setInt(1, version);
            ps.setString(2, java.time.Instant.now().toString());
            ps.executeUpdate();
        }
    }
}
