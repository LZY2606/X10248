package polmu.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Versioned schema migrations. Migration 1 is the original schema; migration 2
 * adds diagnostics for unconvertible domain values and per-derived-row source
 * dependency tracking. Tests can migrate a v1 database forward and verify that
 * existing rows survive untouched.
 */
public final class Migrations {
    public static final int LATEST = 2;

    private Migrations() {}

    private static final String V1 = """
        CREATE TABLE source_blob(
          id TEXT PRIMARY KEY,
          kind TEXT NOT NULL,
          version INTEGER NOT NULL,
          label TEXT,
          unit TEXT,
          frame TEXT,
          raw BLOB NOT NULL,
          imported_at TEXT NOT NULL
        );
        CREATE TABLE branch(
          id TEXT PRIMARY KEY,
          name TEXT NOT NULL,
          parent_event_id TEXT,
          head_event_id TEXT,
          created_at TEXT NOT NULL
        );
        CREATE TABLE event(
          id TEXT PRIMARY KEY,
          branch_id TEXT NOT NULL,
          seq INTEGER NOT NULL,
          prev_event_id TEXT,
          kind TEXT NOT NULL,
          status TEXT,
          payload TEXT NOT NULL,
          fingerprint TEXT,
          created_at TEXT NOT NULL
        );
        CREATE TABLE event_dep(
          event_id TEXT NOT NULL,
          depends_on TEXT NOT NULL,
          PRIMARY KEY(event_id, depends_on)
        );
        CREATE TABLE derived(
          id TEXT PRIMARY KEY,
          event_id TEXT NOT NULL,
          branch_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          subject TEXT,
          value TEXT NOT NULL,
          unit TEXT,
          frame TEXT,
          status TEXT NOT NULL,
          fingerprint TEXT NOT NULL
        );
        """;

    private static final String V2 = """
        ALTER TABLE derived ADD COLUMN diagnostics TEXT;
        CREATE TABLE derived_dep(
          derived_id TEXT NOT NULL,
          source_id TEXT NOT NULL,
          PRIMARY KEY(derived_id, source_id)
        );
        CREATE INDEX idx_event_branch ON event(branch_id, seq);
        CREATE INDEX idx_derived_branch ON derived(branch_id, kind);
        """;

    public static void migrate(Connection c, int upToVersion) {
        try {
            Statement st = c.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            int current = currentVersion(c);
            for (int v = current + 1; v <= upToVersion; v++) {
                String sql = switch (v) {
                    case 1 -> V1;
                    case 2 -> V2;
                    default -> throw new IllegalStateException("no migration " + v);
                };
                for (String stmt : sql.split(";")) {
                    String s = stmt.trim();
                    if (!s.isEmpty()) st.execute(s);
                }
                st.execute("INSERT INTO schema_migrations(version, applied_at) VALUES(" + v + ", datetime('now'))");
            }
        } catch (Exception e) {
            throw new RuntimeException("migration failed: " + e.getMessage(), e);
        }
    }

    public static int currentVersion(Connection c) {
        try {
            Statement st = c.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> tableNames(Connection c) {
        try {
            List<String> out = new ArrayList<>();
            ResultSet rs = c.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
