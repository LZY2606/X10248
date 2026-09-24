package polimueller.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** SQLite connection holder with explicit, testable schema migrations. */
public final class Db implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys=ON");
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open db " + path, e);
        }
    }

    public Connection conn() { return conn; }

    int userVersion() {
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.getInt(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void setUserVersion(int v) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version=" + v);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void migrate() {
        int v = userVersion();
        if (v < 1) {
            exec("""
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS sources(
                  id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  content TEXT NOT NULL,
                  content_hash TEXT NOT NULL,
                  units TEXT,
                  frame TEXT,
                  created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS branches(
                  id TEXT PRIMARY KEY,
                  parent_id TEXT,
                  name TEXT NOT NULL,
                  created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS events(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  branch_id TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  fingerprint TEXT,
                  prev_hash TEXT,
                  hash TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(branch_id, seq)
                );
                CREATE TABLE IF NOT EXISTS rulings(
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  subject TEXT NOT NULL,
                  candidate_id TEXT NOT NULL,
                  decision TEXT NOT NULL,
                  reason TEXT,
                  active INTEGER NOT NULL DEFAULT 1,
                  event_seq INTEGER NOT NULL,
                  created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS derived(
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  deps TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'VALID',
                  created_at TEXT NOT NULL
                );
                INSERT OR IGNORE INTO meta(key,value) VALUES('app','polarization-mueller-workspace');
                """);
            setUserVersion(1);
            v = 1;
        }
        if (v < 2) {
            // v2: fingerprints on derived objects + explicit conflict records
            exec("""
                ALTER TABLE derived ADD COLUMN fingerprint TEXT;
                CREATE TABLE IF NOT EXISTS conflicts(
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  base_head INTEGER NOT NULL,
                  current_head INTEGER NOT NULL,
                  attempted TEXT NOT NULL,
                  ours TEXT NOT NULL,
                  created_at TEXT NOT NULL
                );
                """);
            setUserVersion(2);
        }
    }

    private void exec(String sql) {
        try (Statement st = conn.createStatement()) {
            for (String stmt : sql.split(";")) {
                String s = stmt.trim();
                if (!s.isEmpty()) st.execute(s);
            }
        } catch (Exception e) {
            throw new RuntimeException("migration failed: " + e.getMessage(), e);
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
