-- Schema version 1: baseline chamber schema.
CREATE TABLE materials (
    material_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE TABLE sources (
    id                TEXT PRIMARY KEY,
    material_id       TEXT NOT NULL,
    kind              TEXT NOT NULL,
    version           TEXT NOT NULL,
    supersedes        TEXT,
    unit              TEXT,
    raw_representation TEXT,
    canonical_json    TEXT NOT NULL,
    sha256            TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    convertible       INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (material_id) REFERENCES materials(material_id)
);

CREATE TABLE rule_versions (
    id             TEXT PRIMARY KEY,
    rule_name      TEXT NOT NULL,
    version        TEXT NOT NULL,
    parameters_json TEXT NOT NULL,
    sha256         TEXT NOT NULL,
    seq            INTEGER NOT NULL
);

CREATE TABLE artifacts (
    id                  TEXT PRIMARY KEY,
    material_id         TEXT NOT NULL,
    branch_id           TEXT NOT NULL,
    kind                TEXT NOT NULL,
    status              TEXT NOT NULL,
    payload_json        TEXT NOT NULL,
    fingerprint         TEXT NOT NULL,
    rule_version_id     TEXT NOT NULL,
    source_ids_json     TEXT NOT NULL,
    parent_artifacts_json TEXT NOT NULL,
    event_seq           INTEGER NOT NULL,
    FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE TABLE branches (
    id               TEXT PRIMARY KEY,
    material_id      TEXT NOT NULL,
    name             TEXT NOT NULL,
    parent_branch_id TEXT,
    fork_seq         INTEGER,
    status           TEXT NOT NULL,
    head_seq         INTEGER NOT NULL,
    created_by       TEXT NOT NULL DEFAULT 'system'
);

CREATE TABLE events (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    material_id     TEXT NOT NULL,
    branch_id       TEXT,
    type            TEXT NOT NULL,
    subject_id      TEXT,
    summary         TEXT NOT NULL,
    detail_json     TEXT,
    parent_event_ref TEXT,
    client_event_id TEXT,
    base_version    INTEGER NOT NULL DEFAULT 0,
    author          TEXT NOT NULL DEFAULT 'system'
);

CREATE TABLE diagnostics (
    id          TEXT PRIMARY KEY,
    material_id TEXT NOT NULL,
    branch_id   TEXT,
    source_id   TEXT,
    severity    TEXT NOT NULL,
    code        TEXT NOT NULL,
    message     TEXT NOT NULL,
    event_seq   INTEGER
);
