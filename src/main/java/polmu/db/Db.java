package polmu.db;

import java.sql.Connection;
import java.sql.DriverManager;

public final class Db {
    private Db() {}

    public static Connection open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys=ON");
            c.createStatement().execute("PRAGMA journal_mode=WAL");
            c.setAutoCommit(true);
            Migrations.migrate(c, Migrations.LATEST);
            return c;
        } catch (Exception e) {
            throw new RuntimeException("cannot open db " + path + ": " + e.getMessage(), e);
        }
    }
}
