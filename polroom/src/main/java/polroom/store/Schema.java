package polroom.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite schema with an explicit versioned migration chain (V1 -> V2). */
public final class Schema {

    public static final int CURRENT_VERSION = 2;

    public static void migrate(Connection connection) throws SQLException {
        boolean hasMeta = false;
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")) {
            hasMeta = rs.next();
        }
        int version = 0;
        if (hasMeta) {
            try (Statement st = connection.createStatement();
                 var rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
                if (rs.next()) {
                    version = Integer.parseInt(rs.getString(1));
                }
            }
        }
        if (version == 0) {
            createV1(connection);
            version = 1;
        }
        if (version == 1) {
            migrateV1ToV2(connection);
            version = 2;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', '2')");
        }
    }

    static void createV1(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE materials ("
                    + "material_id TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "created_event_id TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE sources ("
                    + "source_id TEXT NOT NULL, "
                    + "version INTEGER NOT NULL, "
                    + "material_id TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, "
                    + "fingerprint TEXT NOT NULL, "
                    + "unit TEXT, "
                    + "coordinate_basis TEXT, "
                    + "raw_repr TEXT NOT NULL, "
                    + "payload TEXT NOT NULL, "
                    + "supersedes_source_id TEXT, "
                    + "created_event_id TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL, "
                    + "diagnostics TEXT NOT NULL DEFAULT '[]', "
                    + "PRIMARY KEY(source_id, version))");
            st.execute("CREATE TABLE branches ("
                    + "branch_id TEXT PRIMARY KEY, "
                    + "material_id TEXT NOT NULL, "
                    + "name TEXT NOT NULL, "
                    + "parent_branch_id TEXT, "
                    + "forked_from_event_id TEXT, "
                    + "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE events ("
                    + "event_id TEXT PRIMARY KEY, "
                    + "branch_id TEXT NOT NULL, "
                    + "seq INTEGER NOT NULL, "
                    + "parent_event_id TEXT, "
                    + "kind TEXT NOT NULL, "
                    + "occurred_at TEXT NOT NULL, "
                    + "actor TEXT NOT NULL, "
                    + "summary TEXT NOT NULL, "
                    + "payload TEXT NOT NULL, "
                    + "UNIQUE(branch_id, seq))");
            st.execute("CREATE TABLE derivations ("
                    + "fingerprint TEXT PRIMARY KEY, "
                    + "rule_version TEXT NOT NULL, "
                    + "stokes_order TEXT NOT NULL, "
                    + "rotation_convention TEXT NOT NULL, "
                    + "basis_json TEXT NOT NULL, "
                    + "params_json TEXT NOT NULL, "
                    + "result_json TEXT NOT NULL, "
                    + "created_event_id TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE event_derivations ("
                    + "event_id TEXT NOT NULL, "
                    + "fingerprint TEXT NOT NULL, "
                    + "PRIMARY KEY(event_id, fingerprint))");
            st.execute("CREATE TABLE decisions ("
                    + "decision_id TEXT PRIMARY KEY, "
                    + "event_id TEXT NOT NULL, "
                    + "candidate_id TEXT NOT NULL, "
                    + "verdict TEXT NOT NULL, "
                    + "rationale TEXT, "
                    + "undone_by_event_id TEXT, "
                    + "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE branch_heads ("
                    + "branch_id TEXT PRIMARY KEY, "
                    + "head_event_id TEXT NOT NULL, "
                    + "head_seq INTEGER NOT NULL, "
                    + "revision INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE derivation_invalidations ("
                    + "fingerprint TEXT NOT NULL, "
                    + "invalidated_by_event_id TEXT NOT NULL, "
                    + "scope_json TEXT NOT NULL, "
                    + "PRIMARY KEY(fingerprint, invalidated_by_event_id))");
            st.execute("INSERT INTO meta(key, value) VALUES('schema_version', '1')");
        }
        c.commit();
    }

    static void migrateV1ToV2(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE branches ADD COLUMN color_label TEXT");
            st.execute("CREATE TABLE review_marks ("
                    + "mark_id TEXT PRIMARY KEY, "
                    + "material_id TEXT NOT NULL, "
                    + "branch_id TEXT, "
                    + "event_id TEXT, "
                    + "label TEXT NOT NULL, "
                    + "created_at TEXT NOT NULL)");
            st.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', '2')");
        }
        c.commit();
    }

    private Schema() {
    }
}
