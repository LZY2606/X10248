package polmu.store;

import java.util.List;

/** Ordered schema migrations. Migration tests apply them step by step. */
public final class Schema {
    public static final List<String[]> MIGRATIONS = List.of(
            // v1: base append-only schema
            new String[]{
                "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE branches (" +
                "  id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT," +
                "  base_seq INTEGER NOT NULL DEFAULT 0, created_seq INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN')",
                "CREATE TABLE source_records (" +
                "  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL," +
                "  label TEXT NOT NULL, unit TEXT, frame TEXT, raw BLOB NOT NULL, media_type TEXT NOT NULL," +
                "  created_seq INTEGER NOT NULL, superseded_by TEXT)",
                "CREATE TABLE events (" +
                "  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, branch_id TEXT," +
                "  kind TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)",
                "CREATE TABLE candidates (" +
                "  id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL," +
                "  payload TEXT NOT NULL, violations TEXT NOT NULL DEFAULT '[]', created_seq INTEGER NOT NULL," +
                "  stale_seq INTEGER)",
                "CREATE TABLE adjudications (" +
                "  id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL, branch_id TEXT NOT NULL," +
                "  decision TEXT NOT NULL, rationale TEXT, event_seq INTEGER NOT NULL, undone_seq INTEGER)",
                "CREATE TABLE dependencies (from_id TEXT NOT NULL, to_id TEXT NOT NULL," +
                "  PRIMARY KEY (from_id, to_id))",
                "CREATE TABLE review_sessions (" +
                "  branch_id TEXT PRIMARY KEY, version INTEGER NOT NULL, state TEXT NOT NULL, updated_seq INTEGER NOT NULL)"
            },
            // v2: fingerprints become first-class on events and candidates
            new String[]{
                "ALTER TABLE events ADD COLUMN fingerprint TEXT",
                "ALTER TABLE candidates ADD COLUMN fingerprint TEXT"
            }
    );

    public static final int CURRENT_VERSION = MIGRATIONS.size();
}
