package polroom.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Opens/creates the local SQLite database and runs migrations. */
public final class Database {

    public static Connection open(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Properties props = new Properties();
            props.setProperty("foreign_keys", "true");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path, props);
            Schema.migrate(connection);
            return connection;
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    private Database() {
    }
}
