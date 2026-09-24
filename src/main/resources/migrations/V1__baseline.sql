CREATE TABLE schema_meta (
  installed_rank INTEGER PRIMARY KEY,
  version TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL,
  applied_at TEXT NOT NULL
);

CREATE TABLE branch (
  branch_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  parent_branch_id TEXT,
  base_event_id TEXT,
  purpose TEXT NOT NULL,
  created_event_id TEXT NOT NULL
);

CREATE TABLE event (
  event_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL REFERENCES branch(branch_id),
  seq INTEGER NOT NULL,
  parent_event_id TEXT,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(branch_id, seq)
);

CREATE TABLE source_version (
  source_key TEXT NOT NULL,
  version_id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  event_id TEXT NOT NULL,
  branch_id TEXT NOT NULL,
  raw_json TEXT NOT NULL,
  normalized_json TEXT NOT NULL,
  unit_or_basis TEXT NOT NULL,
  fingerprint TEXT NOT NULL
);

CREATE TABLE artifact (
  artifact_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  artifact_type TEXT NOT NULL,
  json TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  valid_until_event_id TEXT
);
