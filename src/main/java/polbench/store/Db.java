package polbench.store;

import polbench.model.Records.*;
import polbench.model.Enums.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public final class Db implements AutoCloseable {
    public final Connection conn;

    public Db(String path) {
        try {
            boolean fresh = (path.equals(":memory:") || !new File(path).exists());
            String url = path.equals(":memory:")
                    ? "jdbc:sqlite::memory:"
                    : "jdbc:sqlite:" + path;
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            int ver = userVersion();
            if (ver == 0 && fresh) {
                runResource("/db/schema_v1.sql");
                setUserVersion(1);
            }
            migrate();
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException("open db failed", e));
        }
    }

    public static Db inMemory() { return new Db(":memory:"); }

    private int userVersion() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setUserVersion(int v) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = " + v);
        }
    }

    private void runResource(String resource) throws SQLException {
        try (InputStream in = Db.class.getResourceAsStream(resource)) {
            if (in == null) throw new SQLException("missing resource " + resource);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = conn.createStatement()) {
                for (String part : sql.split(";")) {
                    String p = part.trim();
                    if (!p.isEmpty() && !p.startsWith("--")) st.execute(p);
                }
            }
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    /** Migrate from older versions. Migration tests build a v1 file then call here. */
    public void migrate() {
        try {
            int ver = userVersion();
            if (ver < 2) {
                runResource("/db/schema_v2.sql");
                setUserVersion(2);
            }
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException("migration failed", e));
        }
    }

    public int schemaVersion() {
        try { return userVersion(); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void tx(RunnableEx body) {
        try {
            conn.setAutoCommit(false);
            Savepoint sp = conn.setSavepoint();
            try {
                body.run();
                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback(sp);
                throw e;
            } catch (Exception e) {
                conn.rollback(sp);
                throw new RuntimeException(e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public interface RunnableEx { void run() throws Exception; }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignore) {}
    }
}
