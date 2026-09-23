package polarmueller.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import polarmueller.core.Fingerprint;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/** Provenance-aware store: sources, branches, events, derivations, adjudications. */
public final class Store {
    public static final ObjectMapper JSON = new ObjectMapper();

    private final Db db;
    public Store(Db db) { this.db = db; }

    /* ---------------- model ---------------- */

    public record SourceDoc(String id, int version, String kind, String mediaType,
                            String units, String frame, byte[] raw, String contentHash) {}

    public record Event(long seq, String id, String type, JsonNode payload, String createdAt) {}

    public record Derivation(String id, String branchId, long eventSeq, String kind,
                             List<String> inputs, Map<String, String> params, String fingerprint,
                             String status, JsonNode result, List<String> diagnostics, boolean stale) {}

    public record Adjudication(String id, String branchId, long eventSeq, String targetId,
                               String decision, String rationale, String actor, String revertedBy) {}

    public static final class ConflictException extends RuntimeException {
        public final long expectedHead, actualHead;
        public final List<Event> intervening;
        public ConflictException(long expected, long actual, List<Event> intervening) {
            super("version conflict: expected head " + expected + " but actual head is " + actual);
            this.expectedHead = expected;
            this.actualHead = actual;
            this.intervening = intervening;
        }
    }

    /* ---------------- branches & events ---------------- */

    public String createBranch(String id, String name, String parentId) {
        exec("INSERT INTO branches(id,name,parent_id,created_at) VALUES(?,?,?,?)",
                id, name, parentId, Instant.now().toString());
        return id;
    }

    public List<Map<String, Object>> branches() {
        List<Map<String, Object>> out = new ArrayList<>();
        query("SELECT id,name,parent_id,created_at FROM branches ORDER BY created_at,id", rs -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", rs.getString(1));
            b.put("name", rs.getString(2));
            b.put("parent_id", rs.getString(3));
            b.put("head", head(rs.getString(1)));
            out.add(b);
        });
        return out;
    }

    public long head(String branchId) {
        final long[] h = {0};
        query("SELECT COALESCE(MAX(seq),0) FROM events WHERE branch_id='" + esc(branchId) + "'",
                rs -> h[0] = rs.getLong(1));
        return h[0];
    }

    public List<Event> events(String branchId, long fromSeqExclusive, long toSeqInclusive) {
        List<Event> out = new ArrayList<>();
        query("SELECT seq,id,type,payload,created_at FROM events WHERE branch_id='" + esc(branchId) +
                        "' AND seq>" + fromSeqExclusive + " AND seq<=" + toSeqInclusive + " ORDER BY seq",
                rs -> out.add(new Event(rs.getLong(1), rs.getString(2), rs.getString(3),
                        readJson(rs.getString(4)), rs.getString(5))));
        return out;
    }

    public List<Event> events(String branchId) {
        return events(branchId, 0, Long.MAX_VALUE);
    }

    private long appendEvent(String branchId, String type, JsonNode payload) {
        long seq = head(branchId) + 1;
        String id = "evt-" + branchId + "-" + seq;
        exec("INSERT INTO events(branch_id,seq,id,type,payload,created_at) VALUES(?,?,?,?,?,?)",
                branchId, seq, id, type, payload.toString(), Instant.now().toString());
        return seq;
    }

    private void checkHead(String branchId, long expectedHead) {
        long actual = head(branchId);
        if (actual != expectedHead)
            throw new ConflictException(expectedHead, actual, events(branchId, expectedHead, actual));
    }

    /* ---------------- sources ---------------- */

    /** Transactional batch import: either the whole batch lands or nothing does. */
    public long importSources(String branchId, long expectedHead, List<SourceDoc> docs) {
        checkHead(branchId, expectedHead);
        try {
            db.conn().setAutoCommit(false);
            ArrayNode imported = JSON.createArrayNode();
            for (SourceDoc d : docs) {
                int version = d.version() > 0 ? d.version() : nextSourceVersion(d.id());
                String hash = d.contentHash() != null ? d.contentHash()
                        : Fingerprint.sha256(new String(d.raw(), java.nio.charset.StandardCharsets.UTF_8));
                try (PreparedStatement ps = db.conn().prepareStatement(
                        "INSERT INTO sources(id,version,kind,media_type,units,frame,raw,content_hash,imported_at)" +
                                " VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, d.id());
                    ps.setInt(2, version);
                    ps.setString(3, d.kind());
                    ps.setString(4, d.mediaType());
                    ps.setString(5, d.units());
                    ps.setString(6, d.frame());
                    ps.setBytes(7, d.raw());
                    ps.setString(8, hash);
                    ps.setString(9, Instant.now().toString());
                    ps.executeUpdate();
                }
                ObjectNode n = imported.addObject();
                n.put("id", d.id()).put("version", version).put("kind", d.kind())
                        .put("units", d.units()).put("frame", d.frame()).put("hash", hash);
            }
            db.conn().commit();
            db.conn().setAutoCommit(true);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("rule", "source_and_interpretation_separated");
            payload.set("imported", imported);
            long seq = appendEvent(branchId, "import_sources", payload);
            // scoped invalidation
            List<String> affected = new ArrayList<>();
            for (JsonNode n : imported) {
                affected.addAll(invalidateFor(n.get("id").asText(), n.get("version").asInt(), branchId));
            }
            if (!affected.isEmpty()) {
                ObjectNode inv = JSON.createObjectNode();
                inv.put("rule", "scoped_invalidation");
                inv.put("cause_event_seq", seq);
                ArrayNode arr = inv.putArray("affected_derivations");
                affected.forEach(arr::add);
                appendEvent(branchId, "invalidate", inv);
            }
            return seq;
        } catch (RuntimeException e) {
            try { db.conn().rollback(); db.conn().setAutoCommit(true); }
            catch (Exception ex) { throw new RuntimeException(ex); }
            throw e;
        } catch (Exception e) {
            try { db.conn().rollback(); db.conn().setAutoCommit(true); }
            catch (Exception ex) { throw new RuntimeException(ex); }
            throw new RuntimeException(e);
        }
    }

    private int nextSourceVersion(String id) {
        final int[] v = {0};
        query("SELECT COALESCE(MAX(version),0) FROM sources WHERE id='" + esc(id) + "'", rs -> v[0] = rs.getInt(1));
        return v[0] + 1;
    }

    private List<String> invalidateFor(String sourceId, int newVersion, String branchId) {
        List<String> affected = new ArrayList<>();
        for (Derivation d : derivations(branchId)) {
            if (d.stale()) continue;
            for (String in : d.inputs()) {
                // input token form: id@version#hash
                String[] parts = in.split("[@#]");
                if (parts[0].equals(sourceId) && Integer.parseInt(parts[1]) < newVersion) {
                    exec("UPDATE derivations SET stale=1 WHERE id='" + esc(d.id()) + "'");
                    affected.add(d.id());
                    break;
                }
            }
        }
        return affected;
    }

    public List<SourceDoc> sources(String kind) {
        List<SourceDoc> out = new ArrayList<>();
        String sql = "SELECT id,version,kind,media_type,units,frame,raw,content_hash FROM sources";
        if (kind != null) sql += " WHERE kind='" + esc(kind) + "'";
        sql += " ORDER BY id,version";
        query(sql, rs -> out.add(new SourceDoc(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getBytes(7), rs.getString(8))));
        return out;
    }

    public SourceDoc latestSource(String kind) {
        List<SourceDoc> all = sources(kind);
        SourceDoc best = null;
        for (SourceDoc d : all)
            if (best == null || d.version() > best.version()
                    || (d.version() == best.version() && d.id().compareTo(best.id()) > 0)) best = d;
        return best;
    }

    /* ---------------- derivations ---------------- */

    public String putDerivation(String branchId, long expectedHead, String kind,
                                List<String> inputs, Map<String, String> params,
                                String fingerprint, String status,
                                JsonNode result, List<String> diagnostics,
                                JsonNode ruleJudgment) {
        checkHead(branchId, expectedHead);
        String id = "der-" + kind + "-" + Fingerprint.shortId(fingerprint);
        ObjectNode payload = JSON.createObjectNode();
        payload.put("derivation_id", id);
        payload.put("kind", kind);
        payload.put("status", status);
        payload.put("fingerprint", fingerprint);
        if (ruleJudgment != null) payload.set("rule_judgment", ruleJudgment);
        ArrayNode inp = payload.putArray("inputs");
        inputs.forEach(inp::add);
        long seq = appendEvent(branchId, "derive", payload);
        exec("INSERT OR REPLACE INTO derivations(id,branch_id,event_seq,kind,inputs,params,fingerprint,status,result,diagnostics,stale)" +
                        " VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                id, branchId, seq, kind, JSON.valueToTree(inputs).toString(),
                JSON.valueToTree(params).toString(), fingerprint, status,
                result == null ? null : result.toString(),
                JSON.valueToTree(diagnostics).toString());
        return id;
    }

    public List<Derivation> derivations(String branchId) {
        List<Derivation> out = new ArrayList<>();
        query("SELECT id,branch_id,event_seq,kind,inputs,params,fingerprint,status,result,diagnostics,stale" +
                        " FROM derivations WHERE branch_id='" + esc(branchId) + "' ORDER BY event_seq,id",
                rs -> out.add(toDerivation(rs)));
        return out;
    }

    public Derivation derivation(String id) {
        final Derivation[] d = {null};
        query("SELECT id,branch_id,event_seq,kind,inputs,params,fingerprint,status,result,diagnostics,stale" +
                " FROM derivations WHERE id='" + esc(id) + "'", rs -> d[0] = toDerivation(rs));
        return d[0];
    }

    private Derivation toDerivation(ResultSet rs) throws java.sql.SQLException {
        List<String> inputs = new ArrayList<>();
        readJson(rs.getString(5)).forEach(n -> inputs.add(n.asText()));
        Map<String, String> params = new LinkedHashMap<>();
        readJson(rs.getString(6)).fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
        List<String> diag = new ArrayList<>();
        JsonNode dj = readJson(rs.getString(10));
        if (dj != null) dj.forEach(n -> diag.add(n.asText()));
        return new Derivation(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                inputs, params, rs.getString(7), rs.getString(8),
                readJson(rs.getString(9)), diag, rs.getInt(11) != 0);
    }

    /* ---------------- adjudications ---------------- */

    public String adjudicate(String branchId, long expectedHead, String targetId,
                             String decision, String rationale, String actor) {
        checkHead(branchId, expectedHead);
        long seq = head(branchId) + 1;
        String id = "adj-" + branchId + "-" + seq;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("adjudication_id", id);
        payload.put("target_id", targetId);
        payload.put("decision", decision);
        payload.put("rationale", rationale == null ? "" : rationale);
        payload.put("actor", actor == null ? "anonymous" : actor);
        appendEvent(branchId, "adjudicate", payload);
        exec("INSERT INTO adjudications(id,branch_id,event_seq,target_id,decision,rationale,actor)" +
                        " VALUES(?,?,?,?,?,?,?)",
                id, branchId, seq, targetId, decision, rationale, actor);
        return id;
    }

    /** Undo an adjudication: appends a revert event; the original record stays readable. */
    public void revert(String branchId, long expectedHead, String adjudicationId) {
        checkHead(branchId, expectedHead);
        Adjudication a = adjudication(adjudicationId);
        if (a == null) throw new IllegalArgumentException("no adjudication " + adjudicationId);
        if (a.revertedBy() != null) throw new IllegalStateException("already reverted");
        long seq = head(branchId) + 1;
        String revertId = "rev-" + branchId + "-" + seq;
        ObjectNode payload = JSON.createObjectNode();
        payload.put("revert_id", revertId);
        payload.put("reverts", adjudicationId);
        payload.put("restored_to_event_seq", a.eventSeq() - 1);
        appendEvent(branchId, "revert", payload);
        exec("UPDATE adjudications SET reverted_by='" + esc(revertId) + "' WHERE id='" + esc(adjudicationId) + "'");
    }

    public Adjudication adjudication(String id) {
        final Adjudication[] a = {null};
        query("SELECT id,branch_id,event_seq,target_id,decision,rationale,actor,reverted_by" +
                        " FROM adjudications WHERE id='" + esc(id) + "'",
                rs -> a[0] = new Adjudication(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
        return a[0];
    }

    public List<Adjudication> adjudications(String branchId) {
        List<Adjudication> out = new ArrayList<>();
        query("SELECT id,branch_id,event_seq,target_id,decision,rationale,actor,reverted_by" +
                        " FROM adjudications WHERE branch_id='" + esc(branchId) + "' ORDER BY event_seq",
                rs -> out.add(new Adjudication(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))));
        return out;
    }

    /** Adjudications still in force at a given event sequence (time travel). */
    public List<Adjudication> activeAdjudicationsAt(String branchId, long seq) {
        List<Adjudication> out = new ArrayList<>();
        for (Adjudication a : adjudications(branchId)) {
            if (a.eventSeq() > seq) continue;
            if (a.revertedBy() != null) {
                // revert event seq = numeric suffix
                long revSeq = Long.parseLong(a.revertedBy().substring(a.revertedBy().lastIndexOf('-') + 1));
                if (revSeq <= seq) continue;
            }
            out.add(a);
        }
        return out;
    }

    /* ---------------- helpers ---------------- */

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof String s) ps.setString(i + 1, s);
                else if (a instanceof Long l) ps.setLong(i + 1, l);
                else if (a instanceof Integer n) ps.setInt(i + 1, n);
                else if (a == null) ps.setObject(i + 1, null);
                else ps.setObject(i + 1, a);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("sql failed: " + sql, e);
        }
    }

    private interface RsConsumer { void accept(ResultSet rs) throws java.sql.SQLException; }

    private void query(String sql, RsConsumer c) {
        try (var st = db.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) c.accept(rs);
        } catch (Exception e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
    }

    private static String esc(String s) { return s.replace("'", "''"); }

    static JsonNode readJson(String s) {
        if (s == null) return null;
        try { return JSON.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
