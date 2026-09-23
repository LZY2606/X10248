package polar.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SQLite persistence. Append-only events/candidates; current-state columns are
 * derivable from the event stream. Stable ids survive export -> wipe -> import.
 */
public final class Database implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final int LATEST_SCHEMA = 3;

    private final Connection conn;

    private Database(Connection c) { this.conn = c; }

    public static Database open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys=ON");
            return new Database(c);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static Database memory() { return open(":memory:"); }

    public Connection connection() { return conn; }

    @Override public void close() { try { conn.close(); } catch (SQLException ignored) {} }

    // -------------------------------------------------------------- records

    public record SourceRec(long seq, String id, String kind, int version,
                            String unit, String basis, byte[] raw, String createdEvent) {}
    public record EventRec(long seq, String id, String branchId, String type,
                           String ruleId, String payload, String createdAt) {}
    public record BranchRec(String id, String name, String parent, long version, long headSeq) {}
    public record CandidateRec(String id, String branchId, long eventSeq, long ord, String kind,
                               String status, double[] matrix, double[] errors,
                               Double condition, Double physResidual, String fingerprint,
                               int paramsVersion, List<Dep> deps, String detailJson) {}
    public record Dep(String kind, String id, int version) {}
    public record RulingRec(String id, String branchId, String candidateId, String decision,
                            String actor, String note, long seq, boolean undone, Long undoneSeq) {}
    public record ParamsRec(int version, String stokesOrder, String rotationConvention,
                            double singularThreshold) {}
    public record DiagnosticRec(String id, String objectId, String severity,
                                String message, long eventSeq) {}

    // -------------------------------------------------------------- migrations

    private static final String[] MIGRATIONS = {
            // v1: core append-only workspace
            """
            CREATE TABLE sources(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              id TEXT NOT NULL, kind TEXT NOT NULL, version INTEGER NOT NULL,
              unit TEXT, basis TEXT, raw BLOB NOT NULL, created_event TEXT,
              UNIQUE(id, version));
            CREATE TABLE params(
              version INTEGER PRIMARY KEY, stokes_order TEXT NOT NULL,
              rotation_convention TEXT NOT NULL, singular_threshold REAL NOT NULL,
              created_event TEXT);
            CREATE TABLE branches(
              id TEXT PRIMARY KEY, name TEXT NOT NULL, parent TEXT,
              version INTEGER NOT NULL DEFAULT 0, head_seq INTEGER NOT NULL DEFAULT 0,
              created_event TEXT);
            CREATE TABLE events(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              id TEXT UNIQUE NOT NULL, branch_id TEXT, type TEXT NOT NULL, payload TEXT);
            CREATE TABLE candidates(
              id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, event_seq INTEGER NOT NULL,
              ord INTEGER NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL,
              matrix_json TEXT, errors_json TEXT, condition_number REAL,
              phys_residual REAL, fingerprint TEXT, params_version INTEGER,
              deps_json TEXT, detail_json TEXT);
            CREATE TABLE rulings(
              id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, candidate_id TEXT NOT NULL,
              decision TEXT NOT NULL, actor TEXT, note TEXT, seq INTEGER NOT NULL,
              undone INTEGER NOT NULL DEFAULT 0, undone_seq INTEGER);
            CREATE TABLE dependencies(
              candidate_id TEXT NOT NULL, dep_kind TEXT NOT NULL,
              dep_id TEXT NOT NULL, dep_version INTEGER NOT NULL);
            CREATE TABLE branch_versions(
              branch_id TEXT NOT NULL, version INTEGER NOT NULL, head_seq INTEGER NOT NULL,
              PRIMARY KEY(branch_id, version));
            """,
            // v2: unconvertible values stay in place with diagnostics
            """
            CREATE TABLE diagnostics(
              id TEXT PRIMARY KEY, object_id TEXT NOT NULL, severity TEXT NOT NULL,
              message TEXT NOT NULL, event_seq INTEGER NOT NULL);
            """,
            // v3: machine-readable judgments carry rule identity
            "ALTER TABLE events ADD COLUMN rule_id TEXT;\n"
            + "ALTER TABLE events ADD COLUMN created_at TEXT;\n"
    };

    public int schemaVersion() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_migrations'")) {
            if (!rs.next()) return 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized void migrate() { migrateTo(LATEST_SCHEMA); }

    public synchronized void migrateTo(int target) {
        try {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT)");
            int current = schemaVersion();
            if (current >= target) return;
            inTx(() -> {
                try {
                    for (int v = current + 1; v <= target; v++) {
                        for (String stmt : MIGRATIONS[v - 1].split(";\n")) {
                            if (!stmt.strip().isBlank()) conn.createStatement().execute(stmt);
                        }
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO schema_migrations(version, applied_at) VALUES (?,?)")) {
                            ps.setInt(1, v);
                            ps.setString(2, Instant.now().toString());
                            ps.executeUpdate();
                        }
                    }
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized <T> T inTx(Supplier<T> body) {
        try {
            conn.setAutoCommit(false);
            try {
                T r = body.get();
                conn.commit();
                return r;
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized void inTx(Runnable body) {
        inTx(() -> { body.run(); return null; });
    }

    private static String sql(String s) { return "'" + s.replace("'", "''") + "'"; }

    // -------------------------------------------------------------- events

    public long insertEvent(String branchId, String type, String ruleId, Object payload) {
        try {
            String id = "evt-" + UUID.randomUUID();
            String body = JSON.writeValueAsString(payload);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO events(id, branch_id, type, rule_id, payload, created_at) "
                            + "VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, id);
                if (branchId == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, branchId);
                ps.setString(3, type);
                if (ruleId == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, ruleId);
                ps.setString(5, body);
                ps.setString(6, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long seq = rs.getLong(1);
                        if (branchId != null)
                            conn.createStatement().executeUpdate(
                                    "UPDATE branches SET head_seq=" + seq + " WHERE id=" + sql(branchId));
                        return seq;
                    }
                }
            }
            throw new IllegalStateException("event insert failed");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public List<EventRec> events(String branchId, Long fromSeqExclusive, Long toSeqInclusive) {
        StringBuilder sql = new StringBuilder(
                "SELECT seq,id,branch_id,type,rule_id,payload,created_at FROM events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (branchId != null) { sql.append(" AND branch_id=?"); args.add(branchId); }
        if (fromSeqExclusive != null) { sql.append(" AND seq>?"); args.add(fromSeqExclusive); }
        if (toSeqInclusive != null) { sql.append(" AND seq<=?"); args.add(toSeqInclusive); }
        sql.append(" ORDER BY seq");
        return queryEvents(sql.toString(), args);
    }

    public List<EventRec> allEvents(Long toSeqInclusive) {
        return events(null, null, toSeqInclusive);
    }

    public EventRec event(long seq) {
        List<EventRec> list = queryEvents(
                "SELECT seq,id,branch_id,type,rule_id,payload,created_at FROM events WHERE seq=?",
                List.of(seq));
        return list.isEmpty() ? null : list.get(0);
    }

    private List<EventRec> queryEvents(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<EventRec> out = new ArrayList<>();
                while (rs.next()) out.add(new EventRec(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------- sources

    public void insertSource(String id, String kind, int version, String unit, String basis,
                             byte[] raw, String createdEvent) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sources(id,kind,version,unit,basis,raw,created_event) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setInt(3, version);
            ps.setString(4, unit);
            ps.setString(5, basis);
            ps.setBytes(6, raw);
            ps.setString(7, createdEvent);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public SourceRec source(String id, int version) {
        List<SourceRec> r = querySources(
                "SELECT seq,id,kind,version,unit,basis,raw,created_event FROM sources WHERE id=? AND version=?",
                List.of(id, version));
        return r.isEmpty() ? null : r.get(0);
    }

    public SourceRec latestSourceById(String id) {
        List<SourceRec> r = querySources(
                "SELECT seq,id,kind,version,unit,basis,raw,created_event FROM sources WHERE id=? "
                        + "ORDER BY version DESC LIMIT 1", List.of(id));
        return r.isEmpty() ? null : r.get(0);
    }

    public Optional<SourceRec> latestSourceOfKind(String kind) {
        List<SourceRec> r = querySources(
                "SELECT seq,id,kind,version,unit,basis,raw,created_event FROM sources s "
                        + "WHERE version=(SELECT MAX(version) FROM sources WHERE kind=s.kind) "
                        + "AND kind=? ORDER BY seq DESC LIMIT 1", List.of(kind));
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<SourceRec> allSources() {
        return querySources(
                "SELECT seq,id,kind,version,unit,basis,raw,created_event FROM sources ORDER BY seq",
                List.of());
    }

    private List<SourceRec> querySources(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<SourceRec> out = new ArrayList<>();
                while (rs.next()) out.add(new SourceRec(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), rs.getString(6), rs.getBytes(7), rs.getString(8)));
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------- params / branches

    public void putParams(ParamsRec p, String createdEvent) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO params(version,stokes_order,rotation_convention,singular_threshold,created_event) "
                        + "VALUES (?,?,?,?,?)")) {
            ps.setInt(1, p.version());
            ps.setString(2, p.stokesOrder());
            ps.setString(3, p.rotationConvention());
            ps.setDouble(4, p.singularThreshold());
            ps.setString(5, createdEvent);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public ParamsRec latestParams() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version,stokes_order,rotation_convention,singular_threshold FROM params ORDER BY version DESC LIMIT 1")) {
            return rs.next() ? new ParamsRec(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4))
                             : new ParamsRec(1, "S0S1S2S3", "ACTIVE_CCW", 1e10);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void createBranch(String id, String name, String parent, String createdEvent) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branches(id,name,parent,version,head_seq,created_event) VALUES (?,?,?,0,0,?)")) {
            ps.setString(1, id); ps.setString(2, name);
            if (parent == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, parent);
            ps.setString(4, createdEvent);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public BranchRec branch(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,name,parent,version,head_seq FROM branches WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BranchRec(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<BranchRec> branches() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id,name,parent,version,head_seq FROM branches ORDER BY id")) {
            List<BranchRec> out = new ArrayList<>();
            while (rs.next()) out.add(new BranchRec(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getLong(4), rs.getLong(5)));
            return out;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Commits a version bump: records the version -> head-seq pin used by conflict diffs. */
    public long bumpVersion(String branchId, long headSeq) {
        BranchRec b = branch(branchId);
        long v = b.version() + 1;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE branches SET version=?, head_seq=? WHERE id=?")) {
            ps.setLong(1, v); ps.setLong(2, headSeq); ps.setString(3, branchId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branch_versions(branch_id,version,head_seq) VALUES (?,?,?)")) {
            ps.setString(1, branchId); ps.setLong(2, v); ps.setLong(3, headSeq);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return v;
    }

    public long headSeqAtVersion(String branchId, long version) {
        if (version == 0) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT head_seq FROM branch_versions WHERE branch_id=? AND version=?")) {
            ps.setString(1, branchId); ps.setLong(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        throw new IllegalStateException("unknown branch version: " + branchId + "@" + version);
    }

    // -------------------------------------------------------------- candidates

    public long insertCandidate(CandidateRec c) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO candidates(id,branch_id,event_seq,ord,kind,status,matrix_json,errors_json,"
                        + "condition_number,phys_residual,fingerprint,params_version,deps_json,detail_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, c.id()); ps.setString(2, c.branchId());
            ps.setLong(3, c.eventSeq()); ps.setLong(4, c.ord());
            ps.setString(5, c.kind()); ps.setString(6, c.status());
            ps.setString(7, c.matrix() == null ? null : JSON.writeValueAsString(c.matrix()));
            ps.setString(8, c.errors() == null ? null : JSON.writeValueAsString(c.errors()));
            if (c.condition() == null) ps.setNull(9, Types.REAL); else ps.setDouble(9, c.condition());
            if (c.physResidual() == null) ps.setNull(10, Types.REAL); else ps.setDouble(10, c.physResidual());
            ps.setString(11, c.fingerprint()); ps.setInt(12, c.paramsVersion());
            ps.setString(13, JSON.writeValueAsString(c.deps())); ps.setString(14, c.detailJson());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        for (Dep d : c.deps()) addDependency(c.id(), d);
        return c.ord();
    }

    private void addDependency(String candidateId, Dep d) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dependencies(candidate_id,dep_kind,dep_id,dep_version) VALUES (?,?,?,?)")) {
            ps.setString(1, candidateId); ps.setString(2, d.kind());
            ps.setString(3, d.id()); ps.setInt(4, d.version());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<CandidateRec> candidates(String branchId, Long upToEventSeq) {
        StringBuilder sql = new StringBuilder(
                "SELECT id,branch_id,event_seq,ord,kind,status,matrix_json,errors_json,condition_number,"
                        + "phys_residual,fingerprint,params_version,deps_json,detail_json "
                        + "FROM candidates WHERE branch_id=?");
        if (upToEventSeq != null) sql.append(" AND event_seq<=").append(upToEventSeq);
        sql.append(" ORDER BY ord");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CandidateRec> out = new ArrayList<>();
                while (rs.next()) out.add(readCandidate(rs));
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private CandidateRec readCandidate(ResultSet rs) throws SQLException {
        double[] m = readDoubles(rs.getString(7));
        double[] er = readDoubles(rs.getString(8));
        Double cond = rs.getObject(9) == null ? null : rs.getDouble(9);
        Double phys = rs.getObject(10) == null ? null : rs.getDouble(10);
        List<Dep> deps = new ArrayList<>();
        try {
            JsonNode dn = JSON.readTree(rs.getString(13));
            for (JsonNode d : dn) deps.add(new Dep(d.get("kind").asText(), d.get("id").asText(), d.get("version").asInt()));
        } catch (Exception e) { throw new SQLException(e); }
        return new CandidateRec(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), m, er, cond, phys, rs.getString(11),
                rs.getInt(12), deps, rs.getString(14));
    }

    private static double[] readDoubles(String json) {
        if (json == null) return null;
        try {
            JsonNode n = JSON.readTree(json);
            double[] v = new double[n.size()];
            for (int i = 0; i < v.length; i++) v[i] = n.get(i).asDouble();
            return v;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public CandidateRec candidate(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,branch_id,event_seq,ord,kind,status,matrix_json,errors_json,condition_number,"
                        + "phys_residual,fingerprint,params_version,deps_json,detail_json "
                        + "FROM candidates WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCandidate(rs) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public long nextCandidateOrd() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(ord),0)+1 FROM candidates")) {
            return rs.next() ? rs.getLong(1) : 1;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void setCandidateStatus(String id, String status) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE candidates SET status=? WHERE id=?")) {
            ps.setString(1, status); ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<String> candidateIdsDependingOnSource(String sourceId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT candidate_id FROM dependencies WHERE dep_kind='SOURCE' AND dep_id=?")) {
            ps.setString(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------- rulings

    public RulingRec addRuling(String branchId, String candidateId, String decision,
                               String actor, String note, long seq) {
        RulingRec r = new RulingRec("rul-" + UUID.randomUUID(), branchId, candidateId,
                decision, actor, note, seq, false, null);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rulings(id,branch_id,candidate_id,decision,actor,note,seq,undone,undone_seq) "
                        + "VALUES (?,?,?,?,?,?,?,0,NULL)")) {
            for (int i = 0; i < 7; i++) ps.setString(i + 1, switch (i) {
                case 0 -> r.id(); case 1 -> r.branchId(); case 2 -> r.candidateId();
                case 3 -> r.decision(); case 4 -> r.actor(); case 5 -> r.note(); case 6 -> String.valueOf(r.seq());
            });
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return r;
    }

    public void markRulingUndone(String rulingId, long undoSeq) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE rulings SET undone=1, undone_seq=? WHERE id=?")) {
            ps.setLong(1, undoSeq); ps.setString(2, rulingId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Current decision for a candidate, undo events taken into account. */
    public String currentDecision(String candidateId, Long upToSeq) {
        for (RulingRec r : rulings(candidateId, upToSeq))
            if (!r.undone()) return r.decision();
        return "PENDING";
    }

    public List<RulingRec> rulings(String candidateId, Long upToSeq) {
        String sql = "SELECT id,branch_id,candidate_id,decision,actor,note,seq,undone,undone_seq "
                + "FROM rulings WHERE candidate_id=?";
        if (upToSeq != null) sql += " AND seq<=" + upToSeq;
        sql += " ORDER BY seq DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, candidateId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RulingRec> out = new ArrayList<>();
                while (rs.next()) {
                    boolean undone = rs.getInt(8) == 1;
                    Long undoSeq = rs.getObject(9) == null ? null : rs.getLong(9);
                    if (upToSeq != null && undone && undoSeq != null && undoSeq > upToSeq) undone = false;
                    out.add(new RulingRec(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                            undone, undoSeq));
                }
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public RulingRec rulingById(String id) {
        List<RulingRec> all = new ArrayList<>();
        for (String cid : new String[]{""}) { /* placeholder */ }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,branch_id,candidate_id,decision,actor,note,seq,undone,undone_seq FROM rulings WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new RulingRec(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                        rs.getInt(8) == 1, rs.getObject(9) == null ? null : rs.getLong(9));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------- diagnostics

    public String addDiagnostic(String objectId, String severity, String message, long eventSeq) {
        String id = "diag-" + UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO diagnostics(id,object_id,severity,message,event_seq) VALUES (?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, objectId); ps.setString(3, severity);
            ps.setString(4, message); ps.setLong(5, eventSeq);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public List<DiagnosticRec> diagnostics(String objectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,object_id,severity,message,event_seq FROM diagnostics WHERE object_id=? ORDER BY event_seq")) {
            ps.setString(1, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DiagnosticRec> out = new ArrayList<>();
                while (rs.next()) out.add(new DiagnosticRec(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5)));
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------- export / import

    private static final String[] DUMP_TABLES_IN_ORDER = {
            "branches", "sources", "params", "events", "branch_versions",
            "candidates", "dependencies", "rulings", "diagnostics"
    };

    /** Canonical JSON archive: stable ids, ordering and provenance are explicit. */
    public String exportJson() {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("format", "mueller-lab-archive");
            root.put("schemaVersion", LATEST_SCHEMA);
            for (String table : DUMP_TABLES_IN_ORDER) root.set(table, dumpTable(table));
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ArrayNode dumpTable(String table) {
        ArrayNode arr = JSON.createArrayNode();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                ObjectNode row = JSON.createObjectNode();
                for (int i = 1; i <= n; i++) {
                    String col = md.getColumnName(i);
                    Object v = rs.getObject(i);
                    if (v == null) row.putNull(col);
                    else if (v instanceof byte[] b) row.put(col, java.util.Base64.getEncoder().encodeToString(b));
                    else if (v instanceof Integer x) row.put(col, x);
                    else if (v instanceof Long x) row.put(col, x);
                    else if (v instanceof Double x) row.put(col, x);
                    else row.put(col, v.toString());
                }
                arr.add(row);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return arr;
    }

    /** Wipe business data and restore from an archive inside one transaction. */
    public void importJson(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            inTx(() -> {
                try {
                    conn.createStatement().execute("DELETE FROM sqlite_sequence");
                } catch (SQLException ignored) { }
                try {
                    for (int i = DUMP_TABLES_IN_ORDER.length - 1; i >= 0; i--)
                        conn.createStatement().execute("DELETE FROM " + DUMP_TABLES_IN_ORDER[i]);
                    for (String table : DUMP_TABLES_IN_ORDER) loadTable(table, root.get(table));
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void loadTable(String table, JsonNode rows) throws SQLException {
        if (rows == null || !rows.isArray()) return;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder insert = new StringBuilder("INSERT INTO " + table + "(");
            StringBuilder qs = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                insert.append(md.getColumnName(i)).append(i < n ? "," : ") VALUES (");
                qs.append(i < n ? "?," : "?)");
            }
            try (PreparedStatement ps = conn.prepareStatement(insert.toString() + qs)) {
                for (JsonNode row : rows) {
                    for (int i = 1; i <= n; i++) {
                        String col = md.getColumnName(i);
                        int type = md.getColumnType(i);
                        JsonNode v = row.get(col);
                        if (v == null || v.isNull()) ps.setObject(i, null);
                        else if ("raw".equals(col))
                            ps.setBytes(i, java.util.Base64.getDecoder().decode(v.asText()));
                        else if (type == Types.INTEGER) ps.setLong(i, v.asLong());
                        else if (type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE)
                            ps.setDouble(i, v.asDouble());
                        else ps.setString(i, v.asText());
                    }
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Stable canonical text used by round-trip tests. */
    public String canonicalDump() {
        try {
            return JSON.writer().with(new com.fasterxml.jackson.core.util.DefaultPrettyPrinter())
                    .writeValueAsString(JSON.readTree(exportJson()));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
