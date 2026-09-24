package lab.polarimetry.mmstudio.db;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;

/** SQLite connection factory + deterministic schema migration runner (v1 -> v2). */
public final class Database implements AutoCloseable {
    public static final int LATEST_VERSION = 2;
    private final Connection conn;

    private Database(Connection conn) { this.conn = conn; }

    public static Database open(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.enforceForeignKeys(true);
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setBusyTimeout(5000);
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), cfg.toProperties());
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + file, e);
        }
    }

    public static Database inMemory() {
        try {
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.enforceForeignKeys(true);
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:", cfg.toProperties());
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (Exception e) {
            throw new RuntimeException("cannot open in-memory database", e);
        }
    }

    public Connection connection() { return conn; }

    private void migrate() throws SQLException, IOException {
        conn.setAutoCommit(true);
        int current = currentVersion();
        for (int v = current + 1; v <= LATEST_VERSION; v++) {
            String ddl = readResource("/db/v" + v + ".sql");
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String stmt : ddl.split(";")) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) st.execute(trimmed);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_meta(schema_version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, v);
                    ps.setString(2, Instant.now().toString());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_meta'")) {
            if (!rs.next()) return 0;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(schema_version), 0) FROM schema_meta")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing migration resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override public void close() throws SQLException { conn.close(); }
}
