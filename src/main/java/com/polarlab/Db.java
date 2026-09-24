package com.polarlab;

import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite storage: append-only event log plus content-addressed blobs.
 * Schema migrations run inside a transaction and are themselves recorded.
 */
public final class Db implements AutoCloseable {

    static final int LATEST_SCHEMA = 2;

    private final Connection c;

    private Db(Connection c) {
        this.c = c;
    }

    public Connection conn() {
        return c;
    }

    public static Db open(File home) throws SQLException {
        File dbFile = new File(home, "polarlab.db");
        File blobDir = new File(home, "blobs");
        blobDir.mkdirs();
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.enforceForeignKeys(true);
        cfg.setBusyTimeout(10_000);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath(), cfg.toProperties());
        c.setAutoCommit(false);
        Db db = new Db(c);
        db.migrate();
        return db;
    }

    /** Test hook: in-memory database. */
    static Db memory() throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.enforceForeignKeys(true);
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:", cfg.toProperties());
        c.setAutoCommit(false);
        Db db = new Db(c);
        db.migrate();
        return db;
    }

    void migrate() throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL, applied_at TEXT NOT NULL DEFAULT (datetime('now')))");
        }
        int v = currentVersion();
        if (v == 0) {
            applyV1();
            v = 1;
        }
        if (v == 1) {
            applyV2();
            v = 2;
        }
        c.commit();
    }

    int currentVersion() throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_meta")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applyV1() throws SQLException {
        List<String> ddl = new ArrayList<>();
        ddl.add("""
                CREATE TABLE blobs (
                  sha256 TEXT PRIMARY KEY,
                  path TEXT NOT NULL,
                  bytes INTEGER NOT NULL,
                  media_type TEXT NOT NULL,
                  imported_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        ddl.add("""
                CREATE TABLE sources (
                  source_id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  label TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  supersedes TEXT,
                  sha256 TEXT NOT NULL REFERENCES blobs(sha256),
                  representation TEXT NOT NULL,
                  unit_frame TEXT NOT NULL,
                  diagnostic TEXT,
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        ddl.add("""
                CREATE TABLE branches (
                  branch_id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  parent_branch TEXT,
                  head_event TEXT,
                  revision INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        ddl.add("""
                CREATE TABLE events (
                  event_id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL REFERENCES branches(branch_id),
                  seq INTEGER NOT NULL,
                  prev_event_id TEXT,
                  event_type TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  fingerprint TEXT NOT NULL,
                  rule_version TEXT NOT NULL,
                  source_refs TEXT NOT NULL,
                  invalidates TEXT NOT NULL DEFAULT '[]',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  UNIQUE (branch_id, seq)
                )""");
        ddl.add("CREATE INDEX idx_events_branch ON events(branch_id, seq)");
        ddl.add("""
                CREATE TABLE decisions (
                  decision_id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  event_id TEXT NOT NULL,
                  verdict TEXT NOT NULL CHECK (verdict IN ('ACCEPT','REJECT')),
                  target_kind TEXT NOT NULL,
                  target_id TEXT NOT NULL,
                  rationale TEXT,
                  superseded_by TEXT,
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
        ddl.add("""
                CREATE TABLE derived (
                  derived_id TEXT PRIMARY KEY,
                  event_id TEXT NOT NULL REFERENCES events(event_id),
                  kind TEXT NOT NULL,
                  name TEXT NOT NULL,
                  value_json TEXT NOT NULL,
                  status TEXT NOT NULL,
                  identity TEXT NOT NULL,
                  valid_from_event TEXT NOT NULL,
                  invalidated_at_event TEXT
                )""");
        for (String sql : ddl) {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO schema_meta(version) VALUES (1)");
        }
    }

    /** v2 adds conflict detection columns for the two-window review scenario. */
    private void applyV2() throws SQLException {
        List<String> ddl = List.of(
                "ALTER TABLE branches ADD COLUMN base_revision INTEGER NOT NULL DEFAULT 0",
                """
                CREATE TABLE conflicts (
                  conflict_id TEXT PRIMARY KEY,
                  branch_id TEXT NOT NULL,
                  winner_event TEXT NOT NULL,
                  rejected_event TEXT NOT NULL,
                  base_revision INTEGER NOT NULL,
                  diff_json TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'OPEN',
                  created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""",
                "INSERT INTO schema_meta(version) VALUES (2)"
        );
        for (String sql : ddl) {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
    }

    public void commit() throws SQLException {
        c.commit();
    }

    public void rollback() {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @Override
    public void close() throws SQLException {
        c.close();
    }
}
