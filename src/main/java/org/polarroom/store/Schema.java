package org.polarroom.store;

/**
 * SQLite DDL and migrations.
 *
 * <p>Version 1 is the fresh case schema. Version 2 adds the event input
 * fingerprint column plus a unit-basis registry; migrating an existing v1
 * database is covered by the state-migration test.
 */
public final class Schema {

    private Schema() {
    }

    public static final int CURRENT_VERSION = 2;

    public static final String[] V1 = {
            "CREATE TABLE IF NOT EXISTS schema_meta ("
                    + " key TEXT PRIMARY KEY, value TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS blob ("
                    + " sha256 TEXT PRIMARY KEY, "
                    + " media_type TEXT NOT NULL, "
                    + " content BLOB NOT NULL)",

            "CREATE TABLE IF NOT EXISTS source_version ("
                    + " material_id TEXT NOT NULL, "
                    + " material_type TEXT NOT NULL, "
                    + " version_no INTEGER NOT NULL, "
                    + " blob_sha TEXT NOT NULL, "
                    + " import_event_id TEXT, "
                    + " original_filename TEXT, "
                    + " note TEXT, "
                    + " PRIMARY KEY (material_id, version_no))",

            "CREATE TABLE IF NOT EXISTS branch ("
                    + " branch_id TEXT PRIMARY KEY, "
                    + " name TEXT NOT NULL, "
                    + " parent_branch_id TEXT, "
                    + " base_event_id TEXT, "
                    + " head_event_id TEXT, "
                    + " purpose TEXT, "
                    + " created_at INTEGER NOT NULL)",

            "CREATE TABLE IF NOT EXISTS event ("
                    + " event_id TEXT PRIMARY KEY, "
                    + " branch_id TEXT NOT NULL, "
                    + " seq INTEGER NOT NULL, "
                    + " parent_event_id TEXT, "
                    + " kind TEXT NOT NULL, "
                    + " payload_json TEXT NOT NULL, "
                    + " author_note TEXT, "
                    + " reverted_of_event_id TEXT, "
                    + " created_at INTEGER NOT NULL, "
                    + " UNIQUE (branch_id, seq))",

            "CREATE TABLE IF NOT EXISTS derived ("
                    + " derived_id TEXT PRIMARY KEY, "
                    + " branch_id TEXT NOT NULL, "
                    + " event_id TEXT NOT NULL, "
                    + " kind TEXT NOT NULL, "
                    + " candidate_key TEXT NOT NULL, "
                    + " fingerprint TEXT NOT NULL, "
                    + " json TEXT NOT NULL)",

            "CREATE TABLE IF NOT EXISTS ruling ("
                    + " branch_id TEXT NOT NULL, "
                    + " subject_key TEXT NOT NULL, "
                    + " event_id TEXT NOT NULL, "
                    + " decision TEXT NOT NULL, "
                    + " note TEXT, "
                    + " created_at INTEGER NOT NULL, "
                    + " PRIMARY KEY (branch_id, subject_key, event_id))",

            "CREATE TABLE IF NOT EXISTS archive_meta ("
                    + " key TEXT PRIMARY KEY, value TEXT NOT NULL)"
    };

    public static final String[] V2 = {
            "ALTER TABLE event ADD COLUMN input_fingerprint TEXT",
            "CREATE TABLE IF NOT EXISTS unit_basis ("
                    + " material_id TEXT NOT NULL, "
                    + " version_no INTEGER NOT NULL, "
                    + " field TEXT NOT NULL, "
                    + " unit_or_basis TEXT NOT NULL, "
                    + " PRIMARY KEY (material_id, version_no, field))"
    };
}
