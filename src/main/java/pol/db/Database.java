package pol.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private Database() {}

    public static Connection open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys = ON");
            migrate(c);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public static int userVersion(Connection c) throws SQLException {
        var rs = c.createStatement().executeQuery("PRAGMA user_version");
        rs.next();
        return rs.getInt(1);
    }

    public static void migrate(Connection c) throws SQLException {
        int v = userVersion(c);
        if (v < 1) migrate1(c);
        if (v < 2) migrate2(c);
        if (v < 3) migrate3(c);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String part : sql.split(";;")) {
                if (!part.trim().isEmpty()) s.execute(part);
            }
        }
    }

    private static void migrate1(Connection c) throws SQLException {
        exec(c, """
        CREATE TABLE sources (
            source_id      TEXT PRIMARY KEY,
            kind           TEXT NOT NULL,
            material_id    TEXT NOT NULL,
            display_name   TEXT NOT NULL,
            created_event  INTEGER,
            superseded_by  TEXT,
            superseded_event INTEGER
        );;
        CREATE TABLE source_versions (
            version_id     TEXT PRIMARY KEY,
            source_id      TEXT NOT NULL REFERENCES sources(source_id),
            version_no     INTEGER NOT NULL,
            raw_repr       TEXT NOT NULL,
            unit           TEXT,
            coordinate_ref TEXT,
            canonical_json TEXT,
            parse_status   TEXT NOT NULL,
            diagnostics    TEXT NOT NULL DEFAULT '[]',
            sha256         TEXT NOT NULL,
            created_event  INTEGER NOT NULL,
            UNIQUE(source_id, version_no)
        );;
        CREATE TABLE events (
            seq            INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id       TEXT NOT NULL UNIQUE,
            branch         TEXT NOT NULL,
            type           TEXT NOT NULL,
            payload_json   TEXT NOT NULL,
            basis_event_id TEXT,
            fingerprint    TEXT,
            created_utc    TEXT NOT NULL,
            replayed_from  TEXT
        );;
        CREATE TABLE branches (
            branch         TEXT PRIMARY KEY,
            parent_branch  TEXT,
            forked_at_seq  INTEGER,
            head_seq       INTEGER NOT NULL DEFAULT 0,
            created_utc    TEXT NOT NULL
        );;
        CREATE TABLE derived (
            derived_id     TEXT NOT NULL,
            branch         TEXT NOT NULL,
            kind           TEXT NOT NULL,
            version_no     INTEGER NOT NULL,
            payload_json   TEXT NOT NULL,
            basis_event_id TEXT NOT NULL,
            produced_event INTEGER NOT NULL,
            status         TEXT NOT NULL,
            invalidated_by INTEGER,
            PRIMARY KEY (derived_id, branch, version_no)
        );;
        CREATE TABLE rulings (
            ruling_id      TEXT PRIMARY KEY,
            branch         TEXT NOT NULL,
            target_type    TEXT NOT NULL,
            target_id      TEXT NOT NULL,
            decision       TEXT NOT NULL,
            rationale      TEXT,
            event_seq      INTEGER NOT NULL,
            undone_by      INTEGER,
            active         INTEGER NOT NULL DEFAULT 1
        );;
        CREATE TABLE ingest_batches (
            batch_id       TEXT PRIMARY KEY,
            event_seq      INTEGER NOT NULL,
            status         TEXT NOT NULL,
            created_utc    TEXT NOT NULL
        );;
        INSERT INTO branches(branch, head_seq, created_utc) VALUES ('main', 0, 'init');;
        PRAGMA user_version = 1;;
        """);
    }

    private static void migrate2(Connection c) throws SQLException {
        exec(c, """
        ALTER TABLE events ADD COLUMN client_token TEXT;;
        CREATE TABLE event_attachments (
            event_seq      INTEGER NOT NULL REFERENCES events(seq),
            name           TEXT NOT NULL,
            sha256         TEXT NOT NULL,
            size_bytes     INTEGER NOT NULL,
            PRIMARY KEY (event_seq, name)
        );;
        PRAGMA user_version = 2;;
        """);
    }

    private static void migrate3(Connection c) throws SQLException {
        exec(c, """
        CREATE TABLE branch_bindings (
            branch         TEXT PRIMARY KEY REFERENCES branches(branch),
            bindings_json  TEXT NOT NULL,
            updated_event  INTEGER NOT NULL
        );;
        CREATE TABLE dependency_edges (
            from_derived_id TEXT NOT NULL,
            from_branch     TEXT NOT NULL,
            to_source_id    TEXT NOT NULL,
            to_version_id   TEXT NOT NULL,
            event_seq       INTEGER NOT NULL,
            PRIMARY KEY (from_derived_id, from_branch, to_source_id, to_version_id)
        );;
        PRAGMA user_version = 3;;
        """);
    }
}
