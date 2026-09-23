package polwork.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Forward-only SQLite schema migrations. State-migration tests build a v1
 * database and replay through to the current version. Derived tables are
 * rebuilt from events, so migrations touch only the log schema, never
 * conclusions.
 */
public final class SchemaMigrator {

    public static final int CURRENT_VERSION = 2;

    private SchemaMigrator() {}

    public static void migrate(Connection cx) throws SQLException {
        int from = currentVersion(cx);
        if (from < 1) {
            runBatch(cx, v1());
            from = 1;
        }
        if (from < 2) {
            // v1 databases may already hold the v2 columns if created by tests;
            // ALTERs are idempotent via metadata checks.
            for (String ddl : v2()) {
                addColumnIfMissing(cx, "source_versions", "origin_label",
                        "TEXT NOT NULL DEFAULT 'import'");
                addColumnIfMissing(cx, "derived_results", "invalidation_scope",
                        "TEXT NOT NULL DEFAULT ''");
                if (ddl.startsWith("CREATE")) run(cx, ddl);
            }
        }
        setVersion(cx, CURRENT_VERSION);
    }

    /** Exposed for state-migration tests: build a genuine v1-only database. */
    public static void buildVersion1(Connection cx) throws SQLException {
        runBatch(cx, v1());
        setVersion(cx, 1);
    }

    private static List<String> v1() {
        return List.of(
            """
            CREATE TABLE schema_meta (
                id INTEGER PRIMARY KEY,
                version INTEGER NOT NULL
            )""",
            """
            CREATE TABLE blobs (
                sha TEXT PRIMARY KEY,
                media_type TEXT NOT NULL,
                bytes BLOB NOT NULL,
                received_at TEXT NOT NULL
            )""",
            """
            CREATE TABLE source_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                version_id TEXT NOT NULL,
                material_id TEXT,
                wavelength_id TEXT,
                blob_sha TEXT NOT NULL REFERENCES blobs(sha),
                payload_json TEXT NOT NULL,
                convention_tag TEXT NOT NULL DEFAULT '',
                imported_at TEXT NOT NULL
            )""",
            """
            CREATE TABLE branches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                material_id TEXT NOT NULL,
                head_event_uid TEXT,
                created_at TEXT NOT NULL
            )""",
            """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ord INTEGER NOT NULL,
                uid TEXT NOT NULL UNIQUE,
                branch_uid TEXT NOT NULL REFERENCES branches(uid),
                parent_uid TEXT,
                base_head_uid TEXT,
                type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                at TEXT NOT NULL,
                undone_by TEXT,
                UNIQUE(branch_uid, ord)
            )""",
            """
            CREATE TABLE derived_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fingerprint TEXT NOT NULL,
                branch_uid TEXT NOT NULL REFERENCES branches(uid),
                produced_by_event_uid TEXT NOT NULL,
                invalidated_by_event_uid TEXT,
                rule_set_version TEXT NOT NULL,
                result_json TEXT NOT NULL
            )""",
            "CREATE INDEX idx_events_branch ON events(branch_uid, ord)",
            "CREATE INDEX idx_derived_fp ON derived_results(branch_uid, fingerprint)"
        );
    }

    private static List<String> v2() {
        return List.of(
            "ALTER TABLE source_versions ADD COLUMN origin_label TEXT NOT NULL DEFAULT 'import'",
            "ALTER TABLE derived_results ADD COLUMN invalidation_scope TEXT NOT NULL DEFAULT ''",
            """
            CREATE TABLE invalidation_edges (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cause_event_uid TEXT NOT NULL,
                affected_fingerprint TEXT NOT NULL,
                affected_kind TEXT NOT NULL,
                branch_uid TEXT NOT NULL
            )""",
            "CREATE INDEX idx_edges_cause ON invalidation_edges(cause_event_uid)"
        );
    }

    private static int currentVersion(Connection cx) throws SQLException {
        if (!tableExists(cx, "schema_meta")) return 0;
        try (var st = cx.createStatement();
             var rs = st.executeQuery("SELECT version FROM schema_meta WHERE id=1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void setVersion(Connection cx, int version) throws SQLException {
        try (var ps = cx.prepareStatement(
                "INSERT INTO schema_meta(id, version) VALUES (1, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET version=excluded.version")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static void runBatch(Connection cx, List<String> ddls) throws SQLException {
        for (String ddl : ddls) run(cx, ddl);
    }

    private static void run(Connection cx, String ddl) throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.execute(ddl);
        }
    }

    private static void addColumnIfMissing(Connection cx, String table, String column, String decl)
            throws SQLException {
        if (!columnExists(cx, table, column)) {
            run(cx, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl);
        }
    }

    private static boolean tableExists(Connection cx, String name) throws SQLException {
        try (var rs = cx.getMetaData().getTables(null, null, name, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection cx, String table, String column) throws SQLException {
        try (var rs = cx.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
