-- v2: derived Mueller candidates, constraint residuals and decomposition candidates.
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
