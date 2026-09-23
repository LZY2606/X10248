-- Final cumulative schema (user_version = 3) for fresh databases.
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
  diagnostic TEXT,
  created_event_seq INTEGER,
  superseded_by TEXT,
  UNIQUE(material_id, version_no)
);

CREATE TABLE branch (
  name TEXT PRIMARY KEY,
  head_seq INTEGER NOT NULL DEFAULT 0,
  created_seq INTEGER NOT NULL,
  parent_branch TEXT,
  fork_point INTEGER
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
  event_seq INTEGER NOT NULL,
  undone_by INTEGER,
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE estimate (
  config_id TEXT PRIMARY KEY,
  fingerprint TEXT NOT NULL,
  branch TEXT NOT NULL,
  rcond REAL NOT NULL,
  method TEXT NOT NULL,
  condition_number REAL,
  sv_rank INTEGER,
  singular_values TEXT,
  null_basis TEXT,
  chi_squared REAL,
  dof INTEGER,
  created_seq INTEGER
);

CREATE TABLE mueller_candidate (
  id TEXT PRIMARY KEY,
  config_id TEXT NOT NULL REFERENCES estimate(config_id),
  kind TEXT NOT NULL,
  elements TEXT NOT NULL,
  covariance TEXT,
  covariance_kind TEXT,
  reference_version_id TEXT,
  nonunique INTEGER NOT NULL DEFAULT 0,
  null_grid REAL
);

CREATE TABLE constraint_residual (
  id TEXT PRIMARY KEY,
  candidate_id TEXT NOT NULL REFERENCES mueller_candidate(id),
  name TEXT NOT NULL,
  residual REAL NOT NULL,
  satisfied INTEGER NOT NULL,
  UNIQUE(candidate_id, name)
);

CREATE TABLE decomp_candidate (
  id TEXT PRIMARY KEY,
  mueller_candidate_id TEXT NOT NULL REFERENCES mueller_candidate(id),
  family TEXT NOT NULL,
  scalars_json TEXT NOT NULL,
  md TEXT,
  mr TEXT,
  mdelta TEXT,
  diagnostic TEXT
);

CREATE TABLE provenance (
  derived_id TEXT NOT NULL,
  derived_kind TEXT NOT NULL,
  source_version_id TEXT NOT NULL REFERENCES source_version(id),
  role TEXT NOT NULL,
  PRIMARY KEY(derived_id, source_version_id, role)
);

CREATE TABLE depends_node (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  label TEXT
);

CREATE TABLE depends_edge (
  from_id TEXT NOT NULL REFERENCES depends_node(id),
  to_id TEXT NOT NULL REFERENCES depends_node(id),
  PRIMARY KEY(from_id, to_id)
);

CREATE TABLE invalidation (
  id TEXT PRIMARY KEY,
  source_version_id TEXT NOT NULL,
  invalidated_node_id TEXT NOT NULL REFERENCES depends_node(id),
  via_event_seq INTEGER NOT NULL,
  reason TEXT NOT NULL
);

CREATE TABLE rule_version (
  rule_id TEXT NOT NULL,
  version_no INTEGER NOT NULL,
  body_json TEXT NOT NULL,
  created_seq INTEGER,
  PRIMARY KEY(rule_id, version_no)
);

CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE INDEX idx_event_branch ON event(branch, seq);
CREATE INDEX idx_prov_source ON provenance(source_version_id);
CREATE INDEX idx_prov_derived ON provenance(derived_id);
CREATE INDEX idx_resid_candidate ON constraint_residual(candidate_id);
CREATE INDEX idx_decomp_candidate ON decomp_candidate(mueller_candidate_id);
CREATE INDEX idx_ruling_branch ON ruling(branch, active);
