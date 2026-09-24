package pol.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pol.polar.Polarimetry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Event-sourced SQLite store: raw sources, branches, hash-chained events, derived artifacts. */
public final class Store implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection conn;

    public record SourceRow(String id, String kind, String label, String mediaType,
                            String units, String basis, byte[] body, String sha, int seq, String created) {}
    public record BranchRow(String id, String name, String parent, String parentEvent, String created) {}
    public record EventRow(String id, String branch, int seq, String type, String payload,
                           String prev, String hash, String created) {}
    public record DerivedRow(String id, String branch, String eventId, String kind, String status,
                             String fingerprint, String inputs, String payload, boolean stale, String ruleVersion) {}

    public static final class ConflictException extends RuntimeException {
        public final String currentHead;
        public final List<EventRow> missed;
        public ConflictException(String currentHead, List<EventRow> missed) {
            super("branch head moved to " + currentHead);
            this.currentHead = currentHead;
            this.missed = missed;
        }
    }

    public Store(Path dbFile) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            conn.setAutoCommit(false);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Run migrations up to {@code target}; pass SCHEMA_VERSION for full migration. */
    public void migrate(int target) {
        try {
            int v = version();
            if (v < 1 && target >= 1) {
                try (Statement s = conn.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS source(id TEXT PRIMARY KEY, kind TEXT, label TEXT, media_type TEXT, units TEXT, basis TEXT, body BLOB, sha TEXT, seq INTEGER, created TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS branch(id TEXT PRIMARY KEY, name TEXT, parent TEXT, parent_event TEXT, created TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS event(id TEXT PRIMARY KEY, branch TEXT, seq INTEGER, type TEXT, payload TEXT, prev TEXT, hash TEXT, created TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS derived(id TEXT PRIMARY KEY, branch TEXT, event_id TEXT, kind TEXT, status TEXT, fingerprint TEXT, inputs TEXT, payload TEXT, stale INTEGER DEFAULT 0)");
                    s.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','1')");
                }
            }
            if (v < 2 && target >= 2) {
                try (Statement s = conn.createStatement()) {
                    s.execute("ALTER TABLE derived ADD COLUMN rule_version TEXT DEFAULT ''");
                    s.execute("CREATE INDEX IF NOT EXISTS idx_event_branch ON event(branch, seq)");
                    s.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','2')");
                }
            }
            conn.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void migrate() { migrate(SCHEMA_VERSION); }

    public int version() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (SQLException e) { return 0; }
    }

    public <T> T inTx(java.util.function.Supplier<T> work) {
        try {
            T t = work.get();
            conn.commit();
            return t;
        } catch (RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw e;
        }
    }

    // ---------- sources ----------

    public void putSource(SourceRow r) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO source(id,kind,label,media_type,units,basis,body,sha,seq,created) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, r.id()); p.setString(2, r.kind()); p.setString(3, r.label());
            p.setString(4, r.mediaType()); p.setString(5, r.units()); p.setString(6, r.basis());
            p.setBytes(7, r.body()); p.setString(8, r.sha()); p.setInt(9, r.seq()); p.setString(10, r.created());
            p.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<SourceRow> sources() {
        List<SourceRow> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,kind,label,media_type,units,basis,body,sha,seq,created FROM source ORDER BY seq")) {
            while (rs.next())
                out.add(new SourceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBytes(7), rs.getString(8), rs.getInt(9), rs.getString(10)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Optional<SourceRow> source(String id) {
        return sources().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    // ---------- branches ----------

    public void putBranch(BranchRow b) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO branch(id,name,parent,parent_event,created) VALUES(?,?,?,?,?)")) {
            p.setString(1, b.id()); p.setString(2, b.name()); p.setString(3, b.parent());
            p.setString(4, b.parentEvent()); p.setString(5, b.created());
            p.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<BranchRow> branches() {
        List<BranchRow> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id,name,parent,parent_event,created FROM branch ORDER BY rowid")) {
            while (rs.next())
                out.add(new BranchRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- events ----------

    public String head(String branch) {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT id FROM event WHERE branch=? ORDER BY seq DESC LIMIT 1")) {
            p.setString(1, branch);
            ResultSet rs = p.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<EventRow> events(String branch) {
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT id,branch,seq,type,payload,prev,hash,created FROM event WHERE branch=? ORDER BY seq")) {
            p.setString(1, branch);
            ResultSet rs = p.executeQuery();
            while (rs.next())
                out.add(new EventRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<EventRow> eventsSince(String branch, String eventIdExclusive) {
        List<EventRow> all = events(branch);
        int idx = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(eventIdExclusive)) { idx = i + 1; break; }
        return all.subList(Math.min(idx, all.size()), all.size());
    }

    /** Append an event with optimistic concurrency on the branch head. */
    public EventRow appendEvent(String branch, String type, String payload, String expectedHead) {
        String head = head(branch);
        if (expectedHead != null && !Objects.equals(head, expectedHead))
            throw new ConflictException(head, eventsSince(branch, expectedHead));
        int seq = events(branch).size() + 1;
        String id = "evt-" + branch + "-" + seq;
        String hash = Polarimetry.sha256((head == null ? "genesis" : head) + "|" + id + "|" + payload);
        String created = Instant.now().toString();
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO event(id,branch,seq,type,payload,prev,hash,created) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, id); p.setString(2, branch); p.setInt(3, seq); p.setString(4, type);
            p.setString(5, payload); p.setString(6, head); p.setString(7, hash); p.setString(8, created);
            p.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return new EventRow(id, branch, seq, type, payload, head, hash, created);
    }

    // ---------- derived ----------

    public void putDerived(DerivedRow d) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO derived(id,branch,event_id,kind,status,fingerprint,inputs,payload,stale,rule_version) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, d.id()); p.setString(2, d.branch()); p.setString(3, d.eventId());
            p.setString(4, d.kind()); p.setString(5, d.status()); p.setString(6, d.fingerprint());
            p.setString(7, d.inputs()); p.setString(8, d.payload()); p.setInt(9, d.stale() ? 1 : 0);
            p.setString(10, d.ruleVersion());
            p.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<DerivedRow> derived(String branch) {
        List<DerivedRow> out = new ArrayList<>();
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT id,branch,event_id,kind,status,fingerprint,inputs,payload,stale,rule_version FROM derived WHERE branch=? ORDER BY rowid")) {
            p.setString(1, branch);
            ResultSet rs = p.executeQuery();
            while (rs.next())
                out.add(new DerivedRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getInt(9) != 0, rs.getString(10)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Dependency-scoped invalidation: only derived artifacts listing the source sha go stale. */
    public List<String> invalidateByInput(String branch, String sourceSha) {
        List<String> hit = new ArrayList<>();
        for (DerivedRow d : derived(branch))
            if (!d.stale() && d.inputs().contains(sourceSha)) {
                hit.add(d.id());
                try (PreparedStatement p = conn.prepareStatement("UPDATE derived SET stale=1 WHERE id=?")) {
                    p.setString(1, d.id());
                    p.executeUpdate();
                } catch (SQLException e) { throw new RuntimeException(e); }
            }
        return hit;
    }

    // ---------- export / import ----------

    public ObjectNode exportAll() {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", "polarimetry-lab-export");
        root.put("schemaVersion", version());
        ArrayNode src = root.putArray("sources");
        for (SourceRow r : sources()) {
            ObjectNode o = src.addObject();
            o.put("id", r.id()); o.put("kind", r.kind()); o.put("label", r.label());
            o.put("mediaType", r.mediaType()); o.put("units", r.units()); o.put("basis", r.basis());
            o.put("body", Base64.getEncoder().encodeToString(r.body()));
            o.put("sha", r.sha()); o.put("seq", r.seq()); o.put("created", r.created());
        }
        ArrayNode br = root.putArray("branches");
        for (BranchRow b : branches()) {
            ObjectNode o = br.addObject();
            o.put("id", b.id()); o.put("name", b.name()); o.put("parent", b.parent());
            o.put("parentEvent", b.parentEvent()); o.put("created", b.created());
        }
        ArrayNode ev = root.putArray("events");
        for (BranchRow b : branches())
            for (EventRow e : events(b.id())) {
                ObjectNode o = ev.addObject();
                o.put("id", e.id()); o.put("branch", e.branch()); o.put("seq", e.seq());
                o.put("type", e.type()); o.put("payload", e.payload()); o.put("prev", e.prev());
                o.put("hash", e.hash()); o.put("created", e.created());
            }
        ArrayNode dr = root.putArray("derived");
        for (BranchRow b : branches())
            for (DerivedRow d : derived(b.id())) {
                ObjectNode o = dr.addObject();
                o.put("id", d.id()); o.put("branch", d.branch()); o.put("eventId", d.eventId());
                o.put("kind", d.kind()); o.put("status", d.status()); o.put("fingerprint", d.fingerprint());
                o.put("inputs", d.inputs()); o.put("payload", d.payload()); o.put("stale", d.stale());
                o.put("ruleVersion", d.ruleVersion());
            }
        return root;
    }

    /** Import into an empty store; identities, order and provenance are preserved verbatim. */
    public void importAll(JsonNode root) {
        if (!sources().isEmpty() || !branches().isEmpty())
            throw new IllegalStateException("import requires an empty database");
        inTx(() -> {
            for (JsonNode o : root.get("sources"))
                putSource(new SourceRow(o.get("id").asText(), o.get("kind").asText(), o.get("label").asText(),
                        o.get("mediaType").asText(), o.get("units").asText(), o.get("basis").asText(),
                        Base64.getDecoder().decode(o.get("body").asText()), o.get("sha").asText(),
                        o.get("seq").asInt(), o.get("created").asText()));
            for (JsonNode o : root.get("branches"))
                putBranch(new BranchRow(o.get("id").asText(), o.get("name").asText(), o.get("parent").asText(),
                        o.get("parentEvent").asText(), o.get("created").asText()));
            for (JsonNode o : root.get("events"))
                insertEventRaw(new EventRow(o.get("id").asText(), o.get("branch").asText(), o.get("seq").asInt(),
                        o.get("type").asText(), o.get("payload").asText(),
                        o.get("prev").isNull() ? null : o.get("prev").asText(),
                        o.get("hash").asText(), o.get("created").asText()));
            for (JsonNode o : root.get("derived"))
                putDerived(new DerivedRow(o.get("id").asText(), o.get("branch").asText(), o.get("eventId").asText(),
                        o.get("kind").asText(), o.get("status").asText(), o.get("fingerprint").asText(),
                        o.get("inputs").asText(), o.get("payload").asText(), o.get("stale").asBoolean(),
                        o.get("ruleVersion").asText()));
            return null;
        });
    }

    private void insertEventRaw(EventRow e) {
        try (PreparedStatement p = conn.prepareStatement(
                "INSERT INTO event(id,branch,seq,type,payload,prev,hash,created) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, e.id()); p.setString(2, e.branch()); p.setInt(3, e.seq()); p.setString(4, e.type());
            p.setString(5, e.payload()); p.setString(6, e.prev()); p.setString(7, e.hash()); p.setString(8, e.created());
            p.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    public boolean isSeeded() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='seeded'")) {
            return rs.next();
        } catch (SQLException e) { return false; }
    }

    public void markSeeded(String casePackId) {
        try (PreparedStatement p = conn.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES('seeded',?)")) {
            p.setString(1, casePackId);
            p.executeUpdate();
            conn.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static String sha(byte[] body) {
        return Polarimetry.sha256(new String(body, StandardCharsets.UTF_8));
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
