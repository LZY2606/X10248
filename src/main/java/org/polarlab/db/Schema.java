package org.polarlab.db;

final class Schema {
    static final int CURRENT_VERSION = 2;

    static final String V1 = """
        CREATE TABLE meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE import_batch (
            id TEXT PRIMARY KEY,
            created_at TEXT NOT NULL,
            item_count INTEGER NOT NULL,
            status TEXT NOT NULL,
            note TEXT
        );

        CREATE TABLE item (
            item_id TEXT NOT NULL,
            version INTEGER NOT NULL,
            kind TEXT NOT NULL,
            json TEXT NOT NULL,
            raw_bytes_hash TEXT NOT NULL,
            raw_bytes BLOB,
            imported_at TEXT NOT NULL,
            batch_id TEXT,
            convertible INTEGER NOT NULL DEFAULT 1,
            PRIMARY KEY (item_id, version)
        );

        CREATE TABLE branch (
            id TEXT PRIMARY KEY,
            parent_id TEXT,
            forked_from_seq INTEGER,
            name TEXT NOT NULL,
            created_at TEXT NOT NULL,
            basis_event_id TEXT
        );

        CREATE TABLE branch_version (
            branch_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            version INTEGER NOT NULL,
            selected_at TEXT NOT NULL,
            event_id TEXT NOT NULL,
            PRIMARY KEY (branch_id, item_id)
        );

        CREATE TABLE event (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id TEXT NOT NULL UNIQUE,
            branch_id TEXT NOT NULL,
            parent_event_id TEXT,
            kind TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            machine_json TEXT NOT NULL,
            created_at TEXT NOT NULL,
            rules_version TEXT NOT NULL
        );

        CREATE TABLE adjudication (
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            candidate_key TEXT NOT NULL,
            decision TEXT NOT NULL,
            rationale TEXT,
            basis_event_id TEXT NOT NULL,
            supersedes_id TEXT,
            undone INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL,
            consistency_token TEXT NOT NULL,
            UNIQUE (branch_id, candidate_key)
        );

        CREATE TABLE artifact (
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            inputs_json TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            json TEXT NOT NULL,
            created_event_id TEXT NOT NULL,
            created_at TEXT NOT NULL,
            invalidated INTEGER NOT NULL DEFAULT 0,
            invalidated_event_id TEXT
        );
        """;

    private Schema() {}
}
