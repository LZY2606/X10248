CREATE TABLE candidate (
  candidate_id TEXT PRIMARY KEY,
  artifact_id TEXT NOT NULL REFERENCES artifact(artifact_id),
  stable_key TEXT NOT NULL,
  kind TEXT NOT NULL,
  json TEXT NOT NULL,
  UNIQUE(artifact_id, stable_key)
);

CREATE TABLE dependency_edge (
  dependency_edge_id TEXT PRIMARY KEY,
  artifact_id TEXT NOT NULL REFERENCES artifact(artifact_id),
  source_key TEXT NOT NULL,
  version_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  local_scope TEXT
);

CREATE TABLE decision (
  decision_id TEXT PRIMARY KEY,
  branch_id TEXT NOT NULL,
  based_on_event_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  artifact_id TEXT,
  candidate_id TEXT,
  action TEXT NOT NULL,
  rationale TEXT NOT NULL,
  decision_event_id TEXT,
  superseded_event_id TEXT,
  UNIQUE(branch_id, based_on_event_id, action, artifact_id, candidate_id)
);

CREATE TABLE invalidation (
  invalidation_id TEXT PRIMARY KEY,
  artifact_id TEXT NOT NULL REFERENCES artifact(artifact_id),
  reason TEXT NOT NULL,
  invalidated_event_id TEXT NOT NULL,
  changed_version_id TEXT NOT NULL
);

CREATE INDEX idx_event_branch ON event(branch_id, seq);
CREATE INDEX idx_artifact_branch ON artifact(branch_id, event_id);
CREATE INDEX idx_source_lookup ON source_version(source_key, branch_id, event_id);
