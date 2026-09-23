package polar.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite store with explicit, tested schema migrations. */
public final class Database implements AutoCloseable {
    public static final int CURRENT_VERSION = 2;

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
            }
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection conn() { return conn; }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             var rs = s.executeQuery("SELECT version FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void migrate() {
        try {
            conn.setAutoCommit(false);
            int v = schemaVersion();
            if (v < 1) applyV1();
            if (v < 2) applyV2();
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new IllegalStateException("migration failed", e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** v1: sources (versioned), branches, event log, candidates. */
    private void applyV1() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS sources(
                  id TEXT NOT NULL,
                  version TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  content BLOB NOT NULL,
                  media_type TEXT NOT NULL,
                  sha256 TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  PRIMARY KEY(id, version)
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS branches(
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  parent_branch TEXT,
                  base_event TEXT,
                  head_event TEXT,
                  created_at TEXT NOT NULL
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS events(
                  id TEXT PRIMARY KEY,
                  branch TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  prev_event TEXT,
                  created_at TEXT NOT NULL
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS candidates(
                  id TEXT PRIMARY KEY,
                  event TEXT NOT NULL,
                  branch TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  fingerprint TEXT,
                  payload TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version(version) VALUES (1)");
        }
    }

    /** v2: candidate adjudication status + explicit invalidation records. */
    private void applyV2() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE candidates ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'");
            s.execute("""
                CREATE TABLE IF NOT EXISTS invalidations(
                  id TEXT PRIMARY KEY,
                  cause_event TEXT NOT NULL,
                  affected_object TEXT NOT NULL,
                  reason TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version(version) VALUES (2)");
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
