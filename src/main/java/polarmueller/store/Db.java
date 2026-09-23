package polarmueller.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** SQLite connection holder with explicit, tested schema migrations. */
public final class Db implements AutoCloseable {
    public static final int SCHEMA_VERSION = 3;

    private final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open db " + path, e);
        }
    }

    public Connection conn() { return conn; }

    public int schemaVersion() {
        try (Statement s = conn.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_meta'");
            if (!rs.next()) return 0;
            ResultSet v = s.executeQuery("SELECT version FROM schema_meta");
            return v.next() ? v.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void migrate() {
        int v = schemaVersion();
        try {
            if (v == 0) {
                exec("CREATE TABLE schema_meta(version INTEGER NOT NULL)");
                exec("INSERT INTO schema_meta(version) VALUES (1)");
                exec("CREATE TABLE sources(" +
                        "id TEXT NOT NULL, version INTEGER NOT NULL," +
                        "kind TEXT NOT NULL, media_type TEXT NOT NULL," +
                        "units TEXT, frame TEXT," +
                        "raw BLOB NOT NULL, content_hash TEXT NOT NULL," +
                        "imported_at TEXT NOT NULL," +
                        "PRIMARY KEY(id, version))");
                exec("CREATE TABLE branches(" +
                        "id TEXT PRIMARY KEY, name TEXT NOT NULL," +
                        "parent_id TEXT, created_at TEXT NOT NULL)");
                exec("CREATE TABLE events(" +
                        "branch_id TEXT NOT NULL, seq INTEGER NOT NULL," +
                        "id TEXT NOT NULL, type TEXT NOT NULL," +
                        "payload TEXT NOT NULL, created_at TEXT NOT NULL," +
                        "PRIMARY KEY(branch_id, seq))");
                v = 1;
            }
            if (v == 1) {
                exec("CREATE TABLE derivations(" +
                        "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL," +
                        "event_seq INTEGER NOT NULL, kind TEXT NOT NULL," +
                        "inputs TEXT NOT NULL, params TEXT NOT NULL," +
                        "fingerprint TEXT NOT NULL, status TEXT NOT NULL," +
                        "result TEXT, diagnostics TEXT)");
                exec("UPDATE schema_meta SET version=2");
                v = 2;
            }
            if (v == 2) {
                exec("ALTER TABLE derivations ADD COLUMN stale INTEGER NOT NULL DEFAULT 0");
                exec("CREATE TABLE adjudications(" +
                        "id TEXT PRIMARY KEY, branch_id TEXT NOT NULL," +
                        "event_seq INTEGER NOT NULL, target_id TEXT NOT NULL," +
                        "decision TEXT NOT NULL, rationale TEXT," +
                        "actor TEXT, reverted_by TEXT)");
                exec("UPDATE schema_meta SET version=3");
                v = 3;
            }
            if (v != SCHEMA_VERSION) throw new IllegalStateException("unknown schema version " + v);
        } catch (Exception e) {
            throw new RuntimeException("migration failed at version " + v, e);
        }
    }

    private void exec(String sql) {
        try (Statement s = conn.createStatement()) { s.execute(sql); }
        catch (Exception e) { throw new RuntimeException("sql failed: " + sql, e); }
    }

    @Override public void close() {
        try { conn.close(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
