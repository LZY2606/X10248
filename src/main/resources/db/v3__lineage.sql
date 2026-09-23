-- v3: provenance/dependency graph, invalidation ranges, rule versions.
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
