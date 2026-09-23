package polarmueller.db;

import java.util.List;

/**
 * Ordered schema migrations. v1 is the core event-sourced schema; v2 adds the
 * diagnostics channel used by the derived layer for unconvertible values.
 */
public final class Migrations {
    private Migrations() {}

    public static final List<String[]> ALL = List.of(
        // ---- v1: core schema ----
        new String[] {
            "CREATE TABLE cases("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')))",

            // Raw sources, separated by kind, versioned, raw bytes preserved.
            "CREATE TABLE source_blobs("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "kind TEXT NOT NULL,"            // intensities|states|dark|reference|instrument|wavelength|covariance
                + "version INTEGER NOT NULL,"
                + "name TEXT NOT NULL,"
                + "media_type TEXT NOT NULL,"
                + "units TEXT NOT NULL DEFAULT '',"
                + "frame TEXT NOT NULL DEFAULT ''," // e.g. stokes:IQSV;rotation:active
                + "raw BLOB NOT NULL,"
                + "sha256 TEXT NOT NULL,"
                + "supersedes TEXT,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')),"
                + "UNIQUE(case_id, kind, version))",

            "CREATE TABLE rule_sets("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "version INTEGER NOT NULL,"
                + "params TEXT NOT NULL,"          // JSON: condThreshold, physTol, stokesOrder, rotationConvention
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')),"
                + "UNIQUE(case_id, version))",

            "CREATE TABLE branches("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "name TEXT NOT NULL,"
                + "base_event TEXT,"
                + "head_event TEXT,"
                + "version INTEGER NOT NULL DEFAULT 0," // optimistic concurrency counter
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')))",

            "CREATE TABLE events("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "branch_id TEXT NOT NULL REFERENCES branches(id),"
                + "seq INTEGER NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "payload TEXT NOT NULL,"          // machine-readable JSON
                + "parent_event TEXT,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')),"
                + "UNIQUE(branch_id, seq))",

            // Derived objects: candidates with full provenance.
            "CREATE TABLE candidates("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "branch_id TEXT NOT NULL REFERENCES branches(id),"
                + "kind TEXT NOT NULL,"            // condition_report|mueller_estimate|physicality_report|decomposition
                + "label TEXT NOT NULL DEFAULT '',"
                + "fingerprint TEXT NOT NULL,"
                + "status TEXT NOT NULL,"          // ok|singular_design|nonphysical_flagged|stale
                + "payload TEXT NOT NULL,"          // JSON
                + "depends_on TEXT NOT NULL,"       // JSON array of blob ids / rule ids
                + "rule_version INTEGER NOT NULL,"
                + "created_seq INTEGER NOT NULL,"
                + "stale_since_seq INTEGER)",

            "CREATE TABLE rulings("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "branch_id TEXT NOT NULL REFERENCES branches(id),"
                + "candidate_id TEXT NOT NULL REFERENCES candidates(id),"
                + "decision TEXT NOT NULL,"         // accept|reject
                + "rationale TEXT NOT NULL DEFAULT '',"
                + "base_version INTEGER NOT NULL,"
                + "event_id TEXT NOT NULL,"
                + "reverted_by_event TEXT,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')))",

            "CREATE TABLE invalidations("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "branch_id TEXT NOT NULL,"
                + "trigger_event TEXT NOT NULL,"
                + "affected_candidate TEXT NOT NULL,"
                + "reason TEXT NOT NULL,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')))"
        },
        // ---- v2: derived-layer diagnostics (unconvertible values stay in place) ----
        new String[] {
            "CREATE TABLE diagnostics("
                + "id TEXT PRIMARY KEY,"
                + "case_id TEXT NOT NULL REFERENCES cases(id),"
                + "branch_id TEXT NOT NULL,"
                + "candidate_id TEXT,"
                + "severity TEXT NOT NULL,"         // info|warn|error
                + "message TEXT NOT NULL,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now')))",
            "CREATE INDEX idx_events_branch ON events(branch_id, seq)",
            "CREATE INDEX idx_candidates_branch ON candidates(branch_id, kind)",
            "CREATE INDEX idx_sources_case ON source_blobs(case_id, kind, version)"
        }
    );
}
