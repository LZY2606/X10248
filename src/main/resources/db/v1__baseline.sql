-- v1 baseline schema used to exercise state migration tests.
CREATE TABLE blob (
  id TEXT PRIMARY KEY,
  sha256 TEXT NOT NULL UNIQUE,
  bytes BLOB NOT NULL,
  media_type TEXT NOT NULL
);

CREATE TABLE source_version (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  material_id TEXT NOT NULL,
  version_no INTEGER NOT NULL,
  label TEXT NOT NULL,
  blob_id TEXT NOT NULL REFERENCES blob(id),
  unit TEXT,
  basis TEXT,
  created_event_seq INTEGER,
  UNIQUE(material_id, version_no)
);

CREATE TABLE branch (
  name TEXT PRIMARY KEY,
  head_seq INTEGER NOT NULL DEFAULT 0,
  created_seq INTEGER NOT NULL
);

CREATE TABLE event (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  branch TEXT NOT NULL REFERENCES branch(name),
  at TEXT NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  prev_hash TEXT,
  hash TEXT NOT NULL,
  UNIQUE(branch, seq)
);

CREATE TABLE ruling (
  id TEXT PRIMARY KEY,
  branch TEXT NOT NULL,
  candidate_id TEXT NOT NULL,
  decision TEXT NOT NULL,
  rationale TEXT,
  event_seq INTEGER NOT NULL
);
