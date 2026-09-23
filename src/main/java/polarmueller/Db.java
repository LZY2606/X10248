package polarmueller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite 存储。schema 通过版本化迁移推进，迁移失败整体回滚。 */
public final class Db implements AutoCloseable {
    public final Connection c;

    private Db(Connection c) {
        this.c = c;
    }

    static final String[][] MIGRATIONS = {
        {
            "CREATE TABLE materials("
                + " id TEXT PRIMARY KEY,"
                + " kind TEXT NOT NULL,"
                + " version INTEGER NOT NULL,"
                + " raw_json TEXT NOT NULL,"
                + " batch_id TEXT NOT NULL,"
                + " created_event TEXT NOT NULL,"
                + " UNIQUE(kind, version))",
            "CREATE TABLE branches("
                + " id TEXT PRIMARY KEY,"
                + " name TEXT NOT NULL,"
                + " parent_id TEXT,"
                + " base_event_seq INTEGER NOT NULL DEFAULT 0,"
                + " head_seq INTEGER NOT NULL DEFAULT 0,"
                + " head_version INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE events("
                + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " id TEXT NOT NULL UNIQUE,"
                + " branch_id TEXT NOT NULL,"
                + " type TEXT NOT NULL,"
                + " payload_json TEXT NOT NULL,"
                + " created_at TEXT NOT NULL)",
            "CREATE TABLE derivations("
                + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " id TEXT NOT NULL UNIQUE,"
                + " branch_id TEXT NOT NULL,"
                + " kind TEXT NOT NULL,"
                + " fingerprint TEXT NOT NULL,"
                + " status TEXT NOT NULL,"
                + " payload_json TEXT NOT NULL,"
                + " depends_json TEXT NOT NULL,"
                + " event_seq INTEGER NOT NULL)",
            "CREATE TABLE rulings("
                + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " id TEXT NOT NULL UNIQUE,"
                + " branch_id TEXT NOT NULL,"
                + " target_id TEXT NOT NULL,"
                + " decision TEXT NOT NULL,"
                + " note TEXT NOT NULL DEFAULT '',"
                + " event_seq INTEGER NOT NULL,"
                + " undone_seq INTEGER NOT NULL DEFAULT 0)"
        },
        {
            "ALTER TABLE derivations ADD COLUMN invalidated_by TEXT NOT NULL DEFAULT ''"
        }
    };

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
            }
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void migrate() {
        try {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations("
                    + " version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            }
            int current = 0;
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                if (r.next()) current = r.getInt(1);
            }
            for (int v = current + 1; v <= MIGRATIONS.length; v++) {
                try (Statement s = c.createStatement()) {
                    for (String sql : MIGRATIONS[v - 1]) s.execute(sql);
                    s.execute("INSERT INTO schema_migrations(version, applied_at) VALUES ("
                        + v + ", datetime('now'))");
                }
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            try {
                c.rollback();
                c.setAutoCommit(true);
            } catch (SQLException ignore) {
                // ignore
            }
            throw new RuntimeException(e);
        }
    }

    public int schemaVersion() {
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            c.close();
        } catch (SQLException ignore) {
            // ignore
        }
    }
}
