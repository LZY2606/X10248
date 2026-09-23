package polwork.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** SQLite connection factory. */
public final class Db {

    private final String url;

    public Db(String path) {
        this.url = "jdbc:sqlite:" + path;
    }

    public Connection connect() throws SQLException {
        Connection cx = DriverManager.getConnection(url);
        cx.createStatement().execute("PRAGMA foreign_keys=ON");
        SchemaMigrator.migrate(cx);
        return cx;
    }
}
