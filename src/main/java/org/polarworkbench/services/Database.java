package org.polarworkbench.services;

import java.io.IOException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public class Database implements AutoCloseable {
    private final String jdbcUrl;
    private final Path path;

    public Database(Path path) {
        this.path = path;
        this.jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("SQLite driver unavailable", ex);
        }
        migrate();
    }

    public Connection connect() {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl);
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            return connection;
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot open database", ex);
        }
    }

    public synchronized <T> T transaction(Function<Connection, T> operation) {
        try (Connection connection = connect()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Transaction failed", ex);
        }
    }

    public synchronized void transactionVoid(java.util.function.Consumer<Connection> operation) {
        transaction(connection -> {
            operation.accept(connection);
            return null;
        });
    }

    private void migrate() {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create data directory", ex);
        }
        transactionVoid(connection -> {
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                      installed_rank INTEGER PRIMARY KEY,
                      version TEXT NOT NULL UNIQUE,
                      description TEXT NOT NULL,
                      applied_at TEXT NOT NULL
                    )
                    """);
            List<Migration> installed = new ArrayList<>();
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT * FROM schema_meta ORDER BY installed_rank")) {
                while (rs.next()) {
                    installed.add(new Migration(rs.getInt("installed_rank"), rs.getString("version"),
                            rs.getString("description")));
                }
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            List<Migration> available = migrations();
            available.sort(Comparator.comparingInt(Migration::rank));
            for (Migration migration : available) {
                boolean exists = installed.stream().anyMatch(item -> item.version.equals(migration.version));
                if (!exists) {
                    String sql;
                    try {
                        sql = Files.readString(migration.path, StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        throw new SQLException("Cannot read migration " + migration.path, ex);
                    }
                    try (Statement statement = connection.createStatement()) {
                        for (String part : sql.split(";\\s*(?:\\r?\\n|$)")) {
                            if (!part.isBlank()) {
                                statement.execute(part);
                            }
                        }
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO schema_meta(installed_rank, version, description, applied_at) VALUES(?,?,?,?)")) {
                        insert.setInt(1, migration.rank);
                        insert.setString(2, migration.version);
                        insert.setString(3, migration.description);
                        insert.setString(4, Instant.now().toString());
                        insert.executeUpdate();
                    }
                }
            }
        });
    }

    private List<Migration> migrations() {
        try {
            List<Path> paths;
            Path overridden = Path.of(System.getProperty("polar.migrations", "src/main/resources/migrations"));
            if (Files.isDirectory(overridden)) {
                paths = Files.list(overridden).sorted().toList();
            } else {
                Path temp = Files.createTempDirectory("polar-migrations");
                for (String resource : List.of("/migrations/V1__baseline.sql", "/migrations/V2__candidates_conflicts.sql")) {
                    Path target = temp.resolve(resource.substring(resource.lastIndexOf('/') + 1));
                    try (var input = Database.class.getResourceAsStream(resource)) {
                        if (input == null) {
                            throw new IllegalStateException("Missing resource " + resource);
                        }
                        Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                paths = Files.list(temp).sorted().toList();
            }
            List<Migration> migrations = new ArrayList<>();
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".sql")) {
                    continue;
                }
                String[] parts = name.split("__", 2);
                migrations.add(new Migration(Integer.parseInt(parts[0].substring(1)), parts[0],
                        parts.length > 1 ? parts[1].replace(".sql", "") : "", path));
            }
            return migrations;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot list migrations", ex);
        }
    }

    private record Migration(int rank, String version, String description, Path path) {
    }
}
