package polworkspace;

final class Schema {
    private Schema() {}

    static final String v1 = """
        CREATE TABLE IF NOT EXISTS sources(
            id TEXT PRIMARY KEY,
            source_type TEXT NOT NULL,
            version TEXT NOT NULL,
            role TEXT NOT NULL,
            material_id TEXT NOT NULL,
            unit TEXT,
            coordinate_basis TEXT,
            representation TEXT NOT NULL,
            canonical_json TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            diagnostics_json TEXT NOT NULL DEFAULT '[]',
            supersedes_source_id TEXT,
            import_batch_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(source_type, version, role, material_id),
            FOREIGN KEY(supersedes_source_id) REFERENCES sources(id)
        );
        CREATE TABLE IF NOT EXISTS import_batches(
            id TEXT PRIMARY KEY,
            case_id TEXT,
            status TEXT NOT NULL,
            source_count INTEGER NOT NULL DEFAULT 0,
            error TEXT,
            raw_manifest_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS branches(
            id TEXT PRIMARY KEY,
            branch_key TEXT NOT NULL UNIQUE,
            title TEXT NOT NULL,
            parent_branch_id TEXT,
            current_event_id TEXT,
            revision INTEGER NOT NULL DEFAULT 0,
            accepted_run_id TEXT,
            accepted_candidate_id TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(parent_branch_id) REFERENCES branches(id)
        );
        CREATE TABLE IF NOT EXISTS events(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            parent_event_id TEXT,
            sequence INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            event_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            basis_fingerprint TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            conflict_base_revision INTEGER,
            UNIQUE(branch_id, sequence),
            FOREIGN KEY(branch_id) REFERENCES branches(id),
            FOREIGN KEY(parent_event_id) REFERENCES events(id)
        );
CREATE TABLE IF NOT EXISTS branch_sources(
            branch_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            source_type TEXT NOT NULL,
            PRIMARY KEY(branch_id, source_id),
            FOREIGN KEY(branch_id) REFERENCES branches(id) ON DELETE CASCADE,
            FOREIGN KEY(source_id) REFERENCES sources(id)
        );
        CREATE TABLE IF NOT EXISTS runs(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            event_id TEXT NOT NULL,
            label TEXT NOT NULL,
            status TEXT NOT NULL,
            source_fingerprint TEXT NOT NULL,
            rule_version TEXT NOT NULL,
            stokes_order TEXT NOT NULL,
            rotation_sign TEXT NOT NULL,
            wavelength_text TEXT NOT NULL,
            rank INTEGER NOT NULL,
            nullity INTEGER NOT NULL,
            condition_number REAL,
            singular_values_json TEXT NOT NULL,
            design_matrix_json TEXT NOT NULL,
            corrected_intensity_json TEXT NOT NULL,
            mueller_min_norm_json TEXT NOT NULL,
            mueller_covariance_json TEXT NOT NULL,
            residual_json TEXT NOT NULL,
            physical_json TEXT NOT NULL,
            diagnostics_json TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(branch_id) REFERENCES branches(id),
            FOREIGN KEY(event_id) REFERENCES events(id)
        );
        CREATE TABLE IF NOT EXISTS run_sources(
            run_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            source_type TEXT NOT NULL,
            version TEXT NOT NULL,
            role TEXT NOT NULL,
            influence_scope TEXT NOT NULL,
            PRIMARY KEY(run_id, source_id),
            FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE,
            FOREIGN KEY(source_id) REFERENCES sources(id)
        );
        CREATE TABLE IF NOT EXISTS candidates(
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            candidate_kind TEXT NOT NULL,
            family TEXT NOT NULL,
            stable_index INTEGER NOT NULL,
            label TEXT NOT NULL,
            alpha_json TEXT,
            mueller_json TEXT,
            uncertainty_json TEXT,
            decomposition_json TEXT,
            physical_status TEXT NOT NULL,
            violations_json TEXT NOT NULL,
            retained INTEGER NOT NULL DEFAULT 1,
            projected INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(run_id, candidate_kind, stable_index),
            FOREIGN KEY(run_id) REFERENCES runs(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_events_branch ON events(branch_id, sequence);
        CREATE INDEX IF NOT EXISTS idx_runs_branch ON runs(branch_id);
        CREATE INDEX IF NOT EXISTS idx_sources_type_version ON sources(source_type, version);
        """;
    static final String v1ToV2 = """
        ALTER TABLE events ADD COLUMN machine_judgement TEXT;
        ALTER TABLE sources ADD COLUMN normalization_diagnostics_json TEXT NOT NULL DEFAULT '[]';
        """;
    static final String v2 = v1 + v1ToV2;
}
