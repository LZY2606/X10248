package polworkspace;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    private Database() {}

    public static Connection open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            migrate(connection);
            return connection;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open database " + path, e);
        }
    }

    public static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
        int version = currentVersion(connection);
        if (version == 0) {
            apply(connection, Schema.v2);
            mark(connection, 1);
            mark(connection, 2);
            return;
        }
        if (version == 1) {
            apply(connection, Schema.v1ToV2);
            mark(connection, 2);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (var rs = connection.createStatement().executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void mark(Connection connection, int version) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT INTO schema_migrations(version) VALUES(?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static void apply(Connection connection, String script) throws SQLException {
        for (String sql : script.split(";\\s*\\n")) {
            String trimmed = sql.trim();
            if (!trimmed.isEmpty()) connection.createStatement().execute(trimmed);
        }
    }
}
