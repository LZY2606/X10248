package pmr.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite schema definitions and migrations. Current schema version is 2. */
public final class Schema {

    public static final int CURRENT_VERSION = 2;

    private Schema() {}

    public static int version(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static void fresh(Connection c) throws SQLException {
        createBlob(c);
        createMaterial(c);
        createArtifact(c);
        createBranch(c);
        createRuleVersion(c);
        createEvent(c);
        createDiagnostic(c);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA user_version = " + CURRENT_VERSION);
        }
    }

    // ---- v1 (kept only for migration tests) ----
    public static void createV1(Connection c) throws SQLException {
        createBlob(c);
        createMaterial(c);
        createArtifactV1(c);
        createBranchV1(c);
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE event (
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL REFERENCES branch(id),
                  seq INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  basis_event_id TEXT,
                  created_at TEXT NOT NULL,
                  UNIQUE(branch_id, seq)
                )""");
            st.execute("CREATE INDEX idx_event_branch ON event(branch_id, seq)");
            st.execute("PRAGMA user_version = 1");
        }
    }

    public static void migrateV1ToV2(Connection c) throws SQLException {
        if (version(c) != 1) {
            throw new SQLException("expected schema v1, found v" + version(c));
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE artifact ADD COLUMN artifact_order INTEGER NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE branch ADD COLUMN head_event_id TEXT");
            st.execute("""
                CREATE TABLE rule_version (
                  id TEXT PRIMARY KEY,
                  key TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  fingerprint TEXT NOT NULL,
                  spec TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(key, version)
                )""");
            st.execute("""
                CREATE TABLE diagnostic (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  branch_id TEXT REFERENCES branch(id),
                  scope TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  code TEXT NOT NULL,
                  message TEXT NOT NULL,
                  context TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX idx_diag_material ON diagnostic(material_id)");
            // backfill branch head from the last event of each branch
            st.execute("""
                UPDATE branch SET head_event_id = (
                  SELECT e.id FROM event e
                  WHERE e.branch_id = branch.id
                  ORDER BY e.seq DESC LIMIT 1
                )""");
            st.execute("PRAGMA user_version = 2");
        }
    }

    private static void createBlob(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE blob (
                  sha256 TEXT PRIMARY KEY,
                  bytes BLOB NOT NULL,
                  media_type TEXT NOT NULL,
                  size INTEGER NOT NULL
                )""");
        }
    }

    private static void createMaterial(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE material (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  description TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )""");
        }
    }

    private static void createArtifact(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE artifact (
                  id TEXT PRIMARY KEY,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  slot TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  unit TEXT NOT NULL,
                  coordinate_basis TEXT NOT NULL,
                  representation TEXT NOT NULL,
                  raw_text TEXT,
                  blob_sha TEXT REFERENCES blob(sha256),
                  content TEXT NOT NULL,
                  artifact_order INTEGER NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(material_id, slot, version)
                )""");
            st.execute("CREATE INDEX idx_artifact_material ON artifact(material_id)");
        }
    }

    private static void createArtifactV1(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE artifact (
                  id TEXT PRIMARY KEY,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  slot TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  unit TEXT NOT NULL,
                  coordinate_basis TEXT NOT NULL,
                  representation TEXT NOT NULL,
                  raw_text TEXT,
                  blob_sha TEXT REFERENCES blob(sha256),
                  content TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(material_id, slot, version)
                )""");
        }
    }

    private static void createBranch(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE branch (
                  id TEXT PRIMARY KEY,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  label TEXT NOT NULL,
                  purpose TEXT NOT NULL,
                  parent_branch_id TEXT REFERENCES branch(id),
                  forked_from_event_id TEXT,
                  head_event_id TEXT,
                  created_at TEXT NOT NULL,
                  UNIQUE(material_id, label)
                )""");
        }
    }

    private static void createBranchV1(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE branch (
                  id TEXT PRIMARY KEY,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  label TEXT NOT NULL,
                  purpose TEXT NOT NULL,
                  parent_branch_id TEXT REFERENCES branch(id),
                  forked_from_event_id TEXT,
                  created_at TEXT NOT NULL,
                  UNIQUE(material_id, label)
                )""");
        }
    }

    private static void createRuleVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE rule_version (
                  id TEXT PRIMARY KEY,
                  key TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  fingerprint TEXT NOT NULL,
                  spec TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  UNIQUE(key, version)
                )""");
        }
    }

    private static void createEvent(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE event (
                  id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL REFERENCES branch(id),
                  seq INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  basis_event_id TEXT,
                  created_at TEXT NOT NULL,
                  UNIQUE(branch_id, seq)
                )""");
            st.execute("CREATE INDEX idx_event_branch ON event(branch_id, seq)");
        }
    }

    private static void createDiagnostic(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE diagnostic (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  material_id TEXT NOT NULL REFERENCES material(id),
                  branch_id TEXT REFERENCES branch(id),
                  scope TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  code TEXT NOT NULL,
                  message TEXT NOT NULL,
                  context TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX idx_diag_material ON diagnostic(material_id)");
        }
    }
}
