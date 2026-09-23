package poloptics.db;

/** Immutable append-only schema. Migration tests assert version chain. */
public final class Schema {
    public static final int VERSION = 1;
    private Schema() {}

    public static final String DDL = """
        PRAGMA journal_mode = WAL;

        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        -- Immutable raw sources. Original representation + unit/datum kept verbatim.
        CREATE TABLE IF NOT EXISTS source_blob (
            blob_id TEXT PRIMARY KEY,
            kind TEXT NOT NULL,                 -- INTENSITY|STATE_CODE|DARK|REFERENCE|INSTRUMENT|WAVELENGTH|COVARIANCE
            media_type TEXT NOT NULL,
            unit_or_datum TEXT,                -- e.g. "count" / "nm @ vacuum" / Stoke coordinate basis
            original_text TEXT NOT NULL,       -- exact representation as imported
            sha256 TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            imported_at TEXT NOT NULL,
            parse_status TEXT NOT NULL,        -- OK|UNCONVERTIBLE
            parse_diagnostic TEXT
        );

        -- Import batch: all-or-nothing. Abandoned batches leave no rows referenced.
        CREATE TABLE IF NOT EXISTS import_batch (
            batch_id TEXT PRIMARY KEY,
            label TEXT NOT NULL,
            started_at TEXT NOT NULL,
            committed_at TEXT,
            status TEXT NOT NULL,              -- OPEN|COMMITTED|ROLLED_BACK
            rollback_reason TEXT
        );
        CREATE TABLE IF NOT EXISTS import_batch_item (
            batch_id TEXT NOT NULL,
            blob_id TEXT NOT NULL,
            item_role TEXT NOT NULL,
            PRIMARY KEY (batch_id, blob_id)
        );

        -- Branches are interpretations of sources. Never mutate sources.
        CREATE TABLE IF NOT EXISTS branch (
            branch_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            parent_branch_id TEXT,
            created_at TEXT NOT NULL,
            note TEXT
        );

        -- Append-only event log. Every state change is an event with stable identity.
        CREATE TABLE IF NOT EXISTS event (
            event_id TEXT PRIMARY KEY,
            seq INTEGER NOT NULL,
            branch_id TEXT NOT NULL,
            parent_event_id TEXT,
            kind TEXT NOT NULL,
            event_time TEXT NOT NULL,
            actor TEXT NOT NULL,               -- user/system/replay
            payload_json TEXT NOT NULL,
            fingerprint TEXT,
            UNIQUE (branch_id, seq)
        );

        -- Versioned bindings of sources into an interpretation (ref revision rule).
        CREATE TABLE IF NOT EXISTS source_ref (
            ref_id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            event_id TEXT NOT NULL,
            role TEXT NOT NULL,
            blob_id TEXT NOT NULL,
            version_tag TEXT NOT NULL,
            supersedes_ref_id TEXT,
            active INTEGER NOT NULL DEFAULT 1
        );

        -- Derived artifacts: estimates, physicality reports, decompositions.
        CREATE TABLE IF NOT EXISTS derived (
            derived_id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            event_id TEXT NOT NULL,
            kind TEXT NOT NULL,                -- ESTIMATE|PHYSICALITY|DECOMPOSITION|CANDIDATE
            candidate_id TEXT,
            payload_json TEXT NOT NULL,
            depends_on_json TEXT NOT NULL,     -- {refs:[...], paramsHash:...}
            valid INTEGER NOT NULL DEFAULT 1
        );

        -- Review decisions, reversible.
        CREATE TABLE IF NOT EXISTS decision (
            decision_id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            event_id TEXT NOT NULL,
            target_derived_id TEXT,
            target_candidate_id TEXT,
            verdict TEXT NOT NULL,             -- ACCEPT|REJECT
            rationale TEXT,
            undo_of_decision_id TEXT,
            undone INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );

        -- Optimistic concurrency token for review saves.
        CREATE TABLE IF NOT EXISTS branch_head (
            branch_id TEXT PRIMARY KEY,
            head_event_id TEXT NOT NULL,
            head_seq INTEGER NOT NULL,
            version INTEGER NOT NULL
        );
        """;
}
