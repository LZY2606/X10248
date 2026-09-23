package polarmueller.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite access with explicit, ordered, testable migrations. */
public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

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

    public static Database inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Connection conn() { return conn; }

    /** Ordered migrations; each runs once, recorded in schema_migrations. */
    private void migrate() {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations("
                    + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            List<String[]> migrations = Migrations.ALL;
            int current = 0;
            try (ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                if (rs.next()) current = rs.getInt(1);
            }
            for (int v = current + 1; v <= migrations.size(); v++) {
                conn.setAutoCommit(false);
                try {
                    for (String sql : migrations.get(v - 1))
                        try (Statement m = conn.createStatement()) { m.execute(sql); }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO schema_migrations(version, applied_at) VALUES(?, datetime('now'))")) {
                        ps.setInt(1, v);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw new IllegalStateException("migration v" + v + " failed", e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Run a unit of work atomically; any failure rolls back the whole change. */
    public <T> T tx(SqlWork<T> work) {
        try {
            conn.setAutoCommit(false);
            try {
                T r = work.run(conn);
                conn.commit();
                return r;
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public interface SqlWork<T> { T run(Connection c) throws SQLException; }

    public List<String> tables() {
        List<String> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
