-- Mueller Matrix Studio - schema v1
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_meta (
    schema_version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS branch (
    branch_id           TEXT PRIMARY KEY,
    material_id         TEXT NOT NULL,
    label               TEXT NOT NULL,
    parent_branch_id    TEXT,
    head_event_id       TEXT,
    created_at          TEXT NOT NULL,
    note                TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS event (
    seq                 INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id            TEXT NOT NULL UNIQUE,
    branch_id           TEXT NOT NULL REFERENCES branch(branch_id),
    parent_event_id     TEXT,
    type                TEXT NOT NULL,
    version_ref         TEXT NOT NULL DEFAULT '',
    payload_json        TEXT NOT NULL,
    basis_fingerprint   TEXT NOT NULL DEFAULT '',
    actor               TEXT NOT NULL DEFAULT 'system',
    created_at          TEXT NOT NULL,
    reverted            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS source (
    source_id           TEXT PRIMARY KEY,
    kind                TEXT NOT NULL,
    material_id         TEXT NOT NULL,
    version             TEXT NOT NULL,
    unit_or_basis       TEXT NOT NULL DEFAULT '',
    raw_representation  TEXT NOT NULL,
    parse_diagnostic    TEXT NOT NULL DEFAULT '',
    ingest_event_id     TEXT NOT NULL,
    created_at          TEXT NOT NULL,
    UNIQUE(kind, material_id, version)
);

CREATE TABLE IF NOT EXISTS artifact (
    artifact_id         TEXT PRIMARY KEY,
    material_id         TEXT NOT NULL,
    branch_id           TEXT NOT NULL,
    kind                TEXT NOT NULL,
    candidate_id        TEXT NOT NULL DEFAULT '',
    depends_json        TEXT NOT NULL,
    payload_json        TEXT NOT NULL,
    source_event_id     TEXT NOT NULL,
    valid_from_event_id TEXT NOT NULL,
    superseded_event_id TEXT,
    created_at          TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS diagnostic (
    diagnostic_id       TEXT PRIMARY KEY,
    material_id         TEXT NOT NULL,
    scope               TEXT NOT NULL,
    message             TEXT NOT NULL,
    severity            TEXT NOT NULL,
    event_id            TEXT NOT NULL,
    created_at          TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_branch ON event(branch_id, seq);
CREATE INDEX IF NOT EXISTS idx_artifact_branch ON artifact(branch_id, kind);
CREATE INDEX IF NOT EXISTS idx_source_material ON source(material_id, kind);
