package com.polws.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** SQLite connection + schema migration (fresh installs use the cumulative final schema). */
public final class Db implements AutoCloseable {
    public static final int FINAL_VERSION = 3;
    private final Connection conn;

    private Db(Connection conn) {
        this.conn = conn;
    }

    public static Db open(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            c.createStatement().execute("PRAGMA foreign_keys = ON");
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("cannot open database " + file, e);
        }
    }

    public Connection conn() {
        return conn;
    }

    public int userVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setUserVersion(int v) {
        exec("PRAGMA user_version = " + v);
    }

    private void migrate() {
        try {
            conn.setAutoCommit(false);
            int v = userVersion();
            if (v == 0) {
                execScript("db/v3__final.sql");
                setUserVersion(3);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, 3);
                    ps.setString(2, Instant.now().toString());
                    ps.executeUpdate();
                }
            } else {
                if (v < 2) {
                    execScript("db/v2__decompositions.sql");
                    setUserVersion(2);
                    recordMigration(2);
                }
                if (v < 3) {
                    execScript("db/v3__lineage.sql");
                    // column additions not present in the v1 baseline/v2 scripts
                    addColumnIfMissing("source_version", "diagnostic", "TEXT");
                    addColumnIfMissing("source_version", "superseded_by", "TEXT");
                    addColumnIfMissing("branch", "parent_branch", "TEXT");
                    addColumnIfMissing("branch", "fork_point", "INTEGER");
                    addColumnIfMissing("ruling", "undone_by", "INTEGER");
                    addColumnIfMissing("ruling", "active", "INTEGER NOT NULL DEFAULT 1");
                    createIndexIfMissing("idx_event_branch", "event(branch, seq)");
                    createIndexIfMissing("idx_prov_source", "provenance(source_version_id)");
                    createIndexIfMissing("idx_prov_derived", "provenance(derived_id)");
                    createIndexIfMissing("idx_resid_candidate", "constraint_residual(candidate_id)");
                    createIndexIfMissing("idx_decomp_candidate", "decomp_candidate(mueller_candidate_id)");
                    createIndexIfMissing("idx_ruling_branch", "ruling(branch, active)");
                    setUserVersion(3);
                    recordMigration(3);
                }
            }
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // best effort
            }
            throw new IllegalStateException("migration failed", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    private void recordMigration(int v) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
            ps.setInt(1, v);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private void addColumnIfMissing(String table, String column, String decl) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, column)) {
            if (!rs.next()) {
                exec("ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl);
            }
        }
    }

    private void createIndexIfMissing(String name, String on) {
        try {
            exec("CREATE INDEX IF NOT EXISTS " + name + " ON " + on);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void execScript(String resource) throws SQLException, IOException {
        String sql;
        try (InputStream in = Db.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
            for (String part : sql.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    st.execute(trimmed);
                }
            }
        }
    }

    public void transaction(ThrowingRunnable body) {
        try {
            conn.setAutoCommit(false);
            body.run();
            conn.commit();
        } catch (RuntimeException e) {
            rollbackQuietly();
            throw e;
        } catch (Exception e) {
            rollbackQuietly();
            throw new RuntimeException(e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private void restoreAutoCommit() {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best effort
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
