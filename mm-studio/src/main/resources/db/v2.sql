-- v2: provenance bookkeeping kept in v1; v2 adds explicit source->artifact
-- invalidation edges and a compact physical-realizability stamp for quick diffs.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS invalidation_edge (
    edge_id             TEXT PRIMARY KEY,
    source_id           TEXT NOT NULL,
    artifact_id         TEXT NOT NULL,
    scope_label         TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    UNIQUE(source_id, artifact_id)
);

CREATE TABLE IF NOT EXISTS branch_verdict (
    branch_id           TEXT PRIMARY KEY REFERENCES branch(branch_id),
    verdict             TEXT NOT NULL,
    candidate_id        TEXT NOT NULL DEFAULT '',
    decided_at_event_id TEXT NOT NULL,
    note                TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_edge_source ON invalidation_edge(source_id);
CREATE INDEX IF NOT EXISTS idx_edge_artifact ON invalidation_edge(artifact_id);
