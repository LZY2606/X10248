package poloptics.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;

/** SQLite connection holder with schema bootstrap and migration ledger. */
public final class Database implements AutoCloseable {
    public final Connection cx;
    public final String path;

    public Database(String path) {
        this.path = path;
        try {
            Class.forName("org.sqlite.JDBC");
            this.cx = DriverManager.getConnection("jdbc:sqlite:" + path);
            cx.setAutoCommit(false);
            try (Statement st = cx.createStatement()) {
                for (String sql : Schema.DDL.split(";")) {
                    String t = sql.trim();
                    if (!t.isEmpty()) st.execute(t);
                }
            }
            cx.commit();
            ensureMeta();
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    private void ensureMeta() throws SQLException {
        boolean exists;
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            exists = rs.next();
            if (exists && !String.valueOf(Schema.VERSION).equals(rs.getString(1))) {
                throw new SQLException("unknown schema version in database: " + rs.getString(1));
            }
        }
        if (!exists) {
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES ('schema_version', ?)")) {
                ps.setString(1, String.valueOf(Schema.VERSION));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = cx.prepareStatement(
                    "INSERT INTO meta(key,value) VALUES ('created_at', ?)")) {
                ps.setString(1, Instant.now().toString());
                ps.executeUpdate();
            }
            cx.commit();
        }
    }

    public int schemaVersion() {
        try (Statement st = cx.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : -1;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public void close() {
        try { cx.close(); } catch (SQLException ignored) {}
    }
}
