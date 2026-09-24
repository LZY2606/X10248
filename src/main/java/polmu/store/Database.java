package polmu.store;

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

public final class Database implements AutoCloseable {
    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            Database db = new Database(c);
            db.migrate(Schema.CURRENT_VERSION);
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("open database " + path, e);
        }
    }

    public Connection conn() { return conn; }

    public int schemaVersion() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void migrate(int targetVersion) {
        try {
            int current = schemaVersion();
            if (current == 0) {
                try (Statement s = conn.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                }
            }
            for (int v = current; v < targetVersion; v++) {
                boolean auto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (Statement s = conn.createStatement()) {
                    for (String sql : Schema.MIGRATIONS.get(v)) s.execute(sql);
                    s.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','" + (v + 1) + "')");
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw new RuntimeException("migration to v" + (v + 1) + " failed", e);
                } finally {
                    conn.setAutoCommit(auto);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void update(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++)
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(sql, e);
        }
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof byte[] b) ps.setBytes(i + 1, b);
            else ps.setObject(i + 1, a);
        }
    }

    /** Run fn in a transaction; any failure rolls back the whole batch. */
    public <T> T tx(java.util.function.Supplier<T> fn) {
        try {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                T out = fn.get();
                conn.commit();
                return out;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
