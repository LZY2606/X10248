package pol.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite storage. All multi-row writes run inside one transaction so a failed
 * import rolls the whole batch back and can never leave a half record.
 */
public final class Database implements AutoCloseable {

    public static final int CURRENT_SCHEMA = 2;

    private final Connection connection;

    public Database(String path) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public void commit() {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("commit failed", e);
        }
    }

    public void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    public interface Tx {
        void run() throws Exception;
    }

    public void transaction(Tx tx) {
        try {
            tx.run();
            connection.commit();
        } catch (Exception e) {
            rollback();
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    public int schemaVersion() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void migrate() {
        int version = schemaVersion();
        if (version >= CURRENT_SCHEMA) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            if (version < 1) {
                st.execute(SCHEMA_V1);
            }
            if (version < 2) {
                st.execute("ALTER TABLE events ADD COLUMN direct_source_id TEXT");
                st.execute("ALTER TABLE events ADD COLUMN revision_of_id TEXT");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_direct ON events(direct_source_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_revision ON events(revision_of_id)");
            }
            st.execute("PRAGMA user_version=" + CURRENT_SCHEMA);
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    private static final String SCHEMA_V1 = String.join("\n",
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            ",",
            "CREATE TABLE IF NOT EXISTS blobs (",
            "  id TEXT PRIMARY KEY,",
            "  sha256 TEXT NOT NULL,",
            "  media_type TEXT NOT NULL,",
            "  byte_count INTEGER NOT NULL,",
            "  data BLOB NOT NULL,",
            "  imported_at TEXT NOT NULL",
            ")",
            ",",
            "CREATE TABLE IF NOT EXISTS branches (",
            "  name TEXT PRIMARY KEY,",
            "  head_seq INTEGER NOT NULL,",
            "  head_event_id TEXT,",
            "  parent_branch TEXT,",
            "  created_at TEXT NOT NULL,",
            "  description TEXT",
            ")",
            ",",
            "CREATE TABLE IF NOT EXISTS events (",
            "  id TEXT PRIMARY KEY,",
            "  branch TEXT NOT NULL,",
            "  seq INTEGER NOT NULL,",
            "  type TEXT NOT NULL,",
            "  parent_id TEXT,",
            "  created_at TEXT NOT NULL,",
            "  actor TEXT NOT NULL,",
            "  payload TEXT NOT NULL,",
            "  fingerprint TEXT,",
            "  source_events TEXT NOT NULL DEFAULT '[]',",
            "  base_version INTEGER NOT NULL DEFAULT 0,",
            "  UNIQUE(branch, seq)",
            ")",
            ",",
            "CREATE INDEX IF NOT EXISTS idx_events_branch ON events(branch, seq)",
            ",",
            "CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)",
            ",",
            "CREATE TABLE IF NOT EXISTS pointer_log (",
            "  id INTEGER PRIMARY KEY AUTOINCREMENT,",
            "  branch TEXT NOT NULL,",
            "  action TEXT NOT NULL,",
            "  from_seq INTEGER,",
            "  to_seq INTEGER NOT NULL,",
            "  event_id TEXT,",
            "  actor TEXT NOT NULL,",
            "  at TEXT NOT NULL,",
            "  note TEXT",
            ")",
            ",",
            "CREATE TABLE IF NOT EXISTS decision_locks (",
            "  branch TEXT PRIMARY KEY,",
            "  head_seq INTEGER NOT NULL,",
            "  actor TEXT NOT NULL,",
            "  acquired_at TEXT NOT NULL",
            ")");

    public boolean blobExists(String id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM blobs WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putBlob(String id, String sha, String mediaType, long byteCount, byte[] data, String at) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO blobs(id,sha256,media_type,byte_count,data,imported_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, sha);
            ps.setString(3, mediaType);
            ps.setLong(4, byteCount);
            ps.setBytes(5, data);
            ps.setString(6, at);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] getBlob(String id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT data FROM blobs WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int countBlobs() {
        return count("SELECT COUNT(*) FROM blobs");
    }

    public int countEvents() {
        return count("SELECT COUNT(*) FROM events");
    }

    private int count(String sql) {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putBranch(String name, int headSeq, String headEvent, String parent, String at, String description) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO branches(name,head_seq,head_event_id,parent_branch,created_at,description) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, name);
            ps.setInt(2, headSeq);
            ps.setString(3, headEvent);
            ps.setString(4, parent);
            ps.setString(5, at);
            ps.setString(6, description);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record BranchRow(String name, int headSeq, String headEventId, String parentBranch, String createdAt, String description) {
    }

    public BranchRow getBranch(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name,head_seq,head_event_id,parent_branch,created_at,description FROM branches WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new BranchRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<BranchRow> listBranches() {
        List<BranchRow> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name,head_seq,head_event_id,parent_branch,created_at,description FROM branches ORDER BY created_at, name")) {
            while (rs.next()) {
                out.add(new BranchRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public int nextSeq(String branch) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE branch=?")) {
            ps.setString(1, branch);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void insertEvent(Event e, int baseVersion) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events(id,branch,seq,type,parent_id,created_at,actor,payload,fingerprint,"
                        + "source_events,base_version,direct_source_id,revision_of_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.id);
            ps.setString(2, e.branch);
            ps.setInt(3, Integer.parseInt(e.seq));
            ps.setString(4, e.type);
            ps.setString(5, e.parentId);
            ps.setString(6, e.createdAt);
            ps.setString(7, e.actor);
            ps.setString(8, e.payloadJson);
            ps.setString(9, e.fingerprint);
            ps.setString(10, e.sourceEventIdsJson);
            ps.setInt(11, baseVersion);
            ps.setString(12, e.directSourceId);
            ps.setString(13, e.revisionOfId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public List<Event> eventsOnBranchUpTo(String branch, int upToSeq) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,seq,type,parent_id,created_at,actor,payload,fingerprint,source_events,direct_source_id,revision_of_id "
                        + "FROM events WHERE branch=? AND seq<=? ORDER BY seq")) {
            ps.setString(1, branch);
            ps.setInt(2, upToSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getString(1), rs.getString(2), branch, rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<Event> allEvents() {
        List<Event> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id,seq,branch,type,parent_id,created_at,actor,payload,fingerprint,source_events,direct_source_id,revision_of_id "
                             + "FROM events ORDER BY created_at, branch, seq")) {
            while (rs.next()) {
                out.add(new Event(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Event findEvent(String id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,seq,branch,type,parent_id,created_at,actor,payload,fingerprint,source_events,direct_source_id,revision_of_id "
                        + "FROM events WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Event(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setHead(String branch, int seq, String eventId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE branches SET head_seq=?, head_event_id=? WHERE name=?")) {
            ps.setInt(1, seq);
            ps.setString(2, eventId);
            ps.setString(3, branch);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void logPointer(String branch, String action, int fromSeq, int toSeq, String eventId,
                           String actor, String at, String note) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pointer_log(branch,action,from_seq,to_seq,event_id,actor,at,note) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, branch);
            ps.setString(2, action);
            ps.setInt(3, fromSeq);
            ps.setInt(4, toSeq);
            ps.setString(5, eventId);
            ps.setString(6, actor);
            ps.setString(7, at);
            ps.setString(8, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void putMeta(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
