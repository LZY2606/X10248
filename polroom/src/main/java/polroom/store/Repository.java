package polroom.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import polroom.domain.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event-sourced repository.
 *
 * <p>Sources are append-only versioned rows; branches are chains of events;
 * derivations are content-addressed by fingerprint and never silently replaced;
 * decisions are append-only and reversible.  Every write runs in a single
 * transaction and rolls back completely on failure.
 */
public class Repository implements AutoCloseable {

    public static final List<String> SOURCE_KINDS = List.of(
            "raw_intensity", "optical_state", "dark_frame", "reference_sample",
            "instrument_matrix", "wavelength", "covariance");

    private final Connection connection;

    public Repository(Connection connection) {
        this.connection = connection;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String now() {
        return Instant.now().toString();
    }

    // ------------------------------------------------------------------
    // materials
    // ------------------------------------------------------------------

    public String createMaterial(String name, String actor) {
        String materialId = newId();
        String eventId = newId();
        String timestamp = now();
        tx(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO materials(material_id, name, created_event_id, created_at) "
                            + "VALUES(?,?,?,?)")) {
                ps.setString(1, materialId);
                ps.setString(2, name);
                ps.setString(3, eventId);
                ps.setString(4, timestamp);
                ps.executeUpdate();
            }
            ObjectNode payload = Json.object();
            payload.put("materialId", materialId);
            payload.put("name", name);
            insertEvent(eventId, null, 0, null, "MATERIAL_CREATED", timestamp, actor,
                    "material created", payload);
        });
        return materialId;
    }

    public List<Map<String, Object>> listMaterials() {
        return query("SELECT material_id, name, created_at FROM materials ORDER BY created_at",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("materialId", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("createdAt", rs.getString(3));
                    return m;
                });
    }

    // ------------------------------------------------------------------
    // sources (seven kinds, append-only versions)
    // ------------------------------------------------------------------

    public record SourceVersion(String sourceId, int version, String materialId, String kind,
                                String fingerprint, String unit, String coordinateBasis,
                                String rawRepr, JsonNode payload,
                                String supersedesSourceId, String eventId,
                                String createdAt, List<String> diagnostics) {
    }

    public SourceVersion addSource(String materialId, String kind, String unit,
                                   String coordinateBasis, String rawRepr, JsonNode payload,
                                   String supersedesSourceId, List<String> diagnostics,
                                   String actor, String summary,
                                   String branchId, long expectedRevision) {
        if (!SOURCE_KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown source kind: " + kind);
        }
        String sourceId = newId();
        String eventId = newId();
        String timestamp = now();
        // content hash binds the original representation, payload, kind and units
        String content = rawRepr + "|" + payload.toString() + "|" + kind + "|"
                + safe(unit) + "|" + safe(coordinateBasis);
        String fingerprint = polroom.domain.Fingerprint.sha256(content);
        int[] versionHolder = new int[1];
        tx(() -> {
            int version = nextSourceVersion(materialId, kind, supersedesSourceId);
            versionHolder[0] = version;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sources(source_id, version, material_id, kind, fingerprint, "
                            + "unit, coordinate_basis, raw_repr, payload, supersedes_source_id, "
                            + "created_event_id, created_at, diagnostics) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bindSource(ps, sourceId, version, materialId, kind, fingerprint, unit,
                        coordinateBasis, rawRepr, payload, supersedesSourceId, eventId,
                        timestamp, diagnostics);
                ps.executeUpdate();
            }
            ObjectNode eventPayload = Json.object();
            eventPayload.put("materialId", materialId);
            eventPayload.put("sourceId", sourceId);
            eventPayload.put("sourceVersion", version);
            eventPayload.put("kind", kind);
            eventPayload.put("fingerprint", fingerprint);
            eventPayload.put("supersedesSourceId", valueOrNull(supersedesSourceId));
            appendEvent(branchId, eventId, "SOURCE_ADDED", actor, summary, eventPayload,
                    expectedRevision, timestamp);
        });
        return new SourceVersion(sourceId, versionHolder[0], materialId, kind, fingerprint,
                unit, coordinateBasis, rawRepr, payload, supersedesSourceId, eventId,
                timestamp, diagnostics);
    }

    private int nextSourceVersion(String materialId, String kind, String supersedesSourceId)
            throws SQLException {
        int max = 0;
        String idToCheck = supersedesSourceId;
        if (idToCheck != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT version FROM sources WHERE source_id = ?")) {
                ps.setString(1, idToCheck);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        max = rs.getInt(1);
                    }
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) FROM sources WHERE material_id = ? AND kind = ?")) {
            ps.setString(1, materialId);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    max = Math.max(max, rs.getInt(1));
                }
            }
        }
        return max + 1;
    }

    public List<SourceVersion> sources(String materialId) {
        return queryCon("SELECT * FROM sources WHERE material_id = ? ORDER BY kind, version",
                ps -> ps.setString(1, materialId),
                Repository::readSource);
    }

    public SourceVersion sourceById(String sourceId) {
        List<SourceVersion> list = queryCon(
                "SELECT * FROM sources WHERE source_id = ? ORDER BY version DESC LIMIT 1",
                ps -> ps.setString(1, sourceId), Repository::readSource);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // branches
    // ------------------------------------------------------------------

    public String createBranch(String materialId, String name, String parentBranchId,
                               String forkedFromEventId, String actor) {
        String branchId = newId();
        String timestamp = now();
        tx(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO branches(branch_id, material_id, name, parent_branch_id, "
                            + "forked_from_event_id, created_at, color_label) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, branchId);
                ps.setString(2, materialId);
                ps.setString(3, name);
                ps.setString(4, parentBranchId);
                ps.setString(5, forkedFromEventId);
                ps.setString(6, timestamp);
                ps.setString(7, null);
                ps.executeUpdate();
            }
            String eventId = newId();
            ObjectNode payload = Json.object();
            payload.put("branchId", branchId);
            payload.put("materialId", materialId);
            payload.put("name", name);
            if (parentBranchId != null) {
                payload.put("parentBranchId", parentBranchId);
            }
            if (forkedFromEventId != null) {
                payload.put("forkedFromEventId", forkedFromEventId);
            }
            insertEvent(eventId, branchId, 0, null, "BRANCH_CREATED", timestamp, actor,
                    "branch " + name + " created", payload);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO branch_heads(branch_id, head_event_id, head_seq, revision) "
                            + "VALUES(?,?,?,0)")) {
                ps.setString(1, branchId);
                ps.setString(2, eventId);
                ps.setInt(3, 0);
                ps.executeUpdate();
            }
        });
        return branchId;
    }

    public List<Map<String, Object>> branches(String materialId) {
        return queryCon(
                "SELECT b.branch_id, b.name, b.parent_branch_id, b.forked_from_event_id, "
                        + "b.created_at, h.head_event_id, h.head_seq, h.revision "
                        + "FROM branches b JOIN branch_heads h ON h.branch_id = b.branch_id "
                        + "WHERE b.material_id = ? ORDER BY b.created_at",
                ps -> ps.setString(1, materialId), rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("branchId", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("parentBranchId", rs.getString(3));
                    m.put("forkedFromEventId", rs.getString(4));
                    m.put("createdAt", rs.getString(5));
                    m.put("headEventId", rs.getString(6));
                    m.put("headSeq", rs.getInt(7));
                    m.put("revision", rs.getLong(8));
                    return m;
                });
    }

    public long revision(String branchId) {
        List<Long> values = queryCon("SELECT revision FROM branch_heads WHERE branch_id = ?",
                ps -> ps.setString(1, branchId), rs -> rs.getLong(1));
        if (values.isEmpty()) {
            throw new IllegalArgumentException("unknown branch: " + branchId);
        }
        return values.get(0);
    }

    // ------------------------------------------------------------------
    // events
    // ------------------------------------------------------------------

    public record Event(String eventId, String branchId, int seq, String parentEventId,
                        String kind, String occurredAt, String actor, String summary,
                        JsonNode payload) {
    }

    public Event appendEvent(String branchId, String kind, String actor, String summary,
                             JsonNode payload, long expectedRevision) {
        String eventId = newId();
        String timestamp = now();
        return appendEventInternal(branchId, eventId, kind, actor, summary, payload,
                expectedRevision, timestamp);
    }

    private Event appendEventInternal(String branchId, String eventId, String kind, String actor,
                                      String summary, JsonNode payload, long expectedRevision,
                                      String timestamp) {
        Event[] holder = new Event[1];
        tx(() -> holder[0] = appendEvent(branchId, eventId, kind, actor, summary, payload,
                expectedRevision, timestamp));
        return holder[0];
    }

    private Event appendEvent(String branchId, String eventId, String kind, String actor,
                              String summary, JsonNode payload, long expectedRevision,
                              String timestamp) throws SQLException {
        int nextSeq;
        String parent;
        long actualRevision;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT head_event_id, head_seq, revision FROM branch_heads WHERE branch_id = ?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown branch: " + branchId);
                }
                parent = rs.getString(1);
                nextSeq = rs.getInt(2) + 1;
                actualRevision = rs.getLong(3);
            }
        }
        if (actualRevision != expectedRevision) {
            throw new ConflictException(branchId, expectedRevision, actualRevision, parent);
        }
        insertEvent(eventId, branchId, nextSeq, parent, kind, timestamp, actor, summary, payload);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE branch_heads SET head_event_id = ?, head_seq = ?, revision = ? "
                        + "WHERE branch_id = ?")) {
            ps.setString(1, eventId);
            ps.setInt(2, nextSeq);
            ps.setLong(3, actualRevision + 1);
            ps.setString(4, branchId);
            ps.executeUpdate();
        }
        return new Event(eventId, branchId, nextSeq, parent, kind, timestamp, actor, summary,
                payload);
    }

    private void insertEvent(String eventId, String branchId, int seq, String parent, String kind,
                             String timestamp, String actor, String summary, JsonNode payload)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events(event_id, branch_id, seq, parent_event_id, kind, "
                        + "occurred_at, actor, summary, payload) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.setInt(3, seq);
            ps.setString(4, parent);
            ps.setString(5, kind);
            ps.setString(6, timestamp);
            ps.setString(7, actor);
            ps.setString(8, summary);
            ps.setString(9, Json.MAPPER.writeValueAsString(payload));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public List<Event> events(String branchId) {
        return queryCon("SELECT * FROM events WHERE branch_id = ? ORDER BY seq",
                ps -> ps.setString(1, branchId), Repository::readEvent);
    }

    public Event event(String eventId) {
        List<Event> list = queryCon("SELECT * FROM events WHERE event_id = ?",
                ps -> ps.setString(1, eventId), Repository::readEvent);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Restore the view at an old event (read-only snapshot of the branch prefix). */
    public List<Event> eventsUpTo(String branchId, String eventId) {
        List<Event> all = events(branchId);
        List<Event> prefix = new ArrayList<>();
        for (Event event : all) {
            prefix.add(event);
            if (event.eventId.equals(eventId)) {
                break;
            }
        }
        return prefix;
    }

    // ------------------------------------------------------------------
    // derivations (content-addressed, never replaced)
    // ------------------------------------------------------------------

    public void recordDerivation(String fingerprint, String ruleVersion, String stokesOrder,
                                 String rotationConvention, JsonNode basis, JsonNode params,
                                 JsonNode result, String branchId, long expectedRevision,
                                 String actor, String summary,
                                 Map<String, Object> scope) {
        String eventId = newId();
        tx(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO derivations(fingerprint, rule_version, stokes_order, "
                            + "rotation_convention, basis_json, params_json, result_json, "
                            + "created_event_id, created_at) VALUES(?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(fingerprint) DO NOTHING")) {
                ps.setString(1, fingerprint);
                ps.setString(2, ruleVersion);
                ps.setString(3, stokesOrder);
                ps.setString(4, rotationConvention);
                ps.setString(5, basis.toString());
                ps.setString(6, params.toString());
                ps.setString(7, result.toString());
                ps.setString(8, eventId);
                ps.setString(9, now());
                ps.executeUpdate();
            }
            ObjectNode payload = Json.object();
            payload.put("fingerprint", fingerprint);
            payload.put("scope", Json.MAPPER.valueToTree(scope));
            Event event = appendEvent(branchId, eventId, "DERIVATION_RECORDED", actor, summary,
                    payload, expectedRevision, now());
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO event_derivations(event_id, fingerprint) VALUES(?,?)")) {
                ps.setString(1, event.eventId);
                ps.setString(2, fingerprint);
                ps.executeUpdate();
            }
        });
    }

    public Map<String, Object> derivation(String fingerprint) {
        List<Map<String, Object>> list = queryCon(
                "SELECT * FROM derivations WHERE fingerprint = ?",
                ps -> ps.setString(1, fingerprint), rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fingerprint", rs.getString("fingerprint"));
                    m.put("ruleVersion", rs.getString("rule_version"));
                    m.put("stokesOrder", rs.getString("stokes_order"));
                    m.put("rotationConvention", rs.getString("rotation_convention"));
                    m.put("basis", readJson(rs.getString("basis_json")));
                    m.put("params", readJson(rs.getString("params_json")));
                    m.put("result", readJson(rs.getString("result_json")));
                    return m;
                });
        return list.isEmpty() ? null : list.get(0);
    }

    public List<String> derivationFingerprintsAt(String branchId, String eventId) {
        List<String> out = new ArrayList<>();
        for (Event event : eventsUpTo(branchId, eventId)) {
            queryCon("SELECT fingerprint FROM event_derivations WHERE event_id = ?",
                    ps -> ps.setString(1, event.eventId), rs -> {
                        out.add(rs.getString(1));
                        return null;
                    });
        }
        return out;
    }

    public void invalidateDerivation(String fingerprint, String branchId, long expectedRevision,
                                     String actor, String summary, Map<String, Object> scope) {
        ObjectNode payload = Json.object();
        payload.put("fingerprint", fingerprint);
        payload.put("scope", Json.MAPPER.valueToTree(scope));
        Event event = appendEvent(branchId, "DERIVATION_INVALIDATED", actor, summary, payload,
                expectedRevision);
        tx(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO derivation_invalidations("
                            + "fingerprint, invalidated_by_event_id, scope_json) VALUES(?,?,?)")) {
                ps.setString(1, fingerprint);
                ps.setString(2, event.eventId);
                ps.setString(3, Json.MAPPER.valueToTree(scope).toString());
                ps.executeUpdate();
            }
        });
    }

    public List<Map<String, Object>> invalidations() {
        return query("SELECT fingerprint, invalidated_by_event_id, scope_json "
                + "FROM derivation_invalidations", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fingerprint", rs.getString(1));
            m.put("invalidatedByEventId", rs.getString(2));
            m.put("scope", readJson(rs.getString(3)));
            return m;
        });
    }

    // ------------------------------------------------------------------
    // decisions (append-only, reversible)
    // ------------------------------------------------------------------

    public record Decision(String decisionId, String eventId, String candidateId, String verdict,
                           String rationale, String undoneByEventId, String createdAt) {
    }

    public Event decide(String branchId, String candidateId, String verdict, String rationale,
                        String actor, long expectedRevision) {
        String decisionId = newId();
        String eventId = newId();
        String timestamp = now();
        Event[] holder = new Event[1];
        tx(() -> {
            ObjectNode payload = Json.object();
            payload.put("decisionId", decisionId);
            payload.put("candidateId", candidateId);
            payload.put("verdict", verdict);
            payload.put("rationale", rationale == null ? "" : rationale);
            holder[0] = appendEvent(branchId, eventId, "DECISION_" + verdict, actor,
                    verdict + " " + candidateId, payload, expectedRevision, timestamp);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO decisions(decision_id, event_id, candidate_id, verdict, "
                            + "rationale, undone_by_event_id, created_at) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, decisionId);
                ps.setString(2, eventId);
                ps.setString(3, candidateId);
                ps.setString(4, verdict);
                ps.setString(5, rationale);
                ps.setString(6, null);
                ps.setString(7, timestamp);
                ps.executeUpdate();
            }
        });
        return holder[0];
    }

    /** Undo restores the previous branch state; the undo is itself an event. */
    public Event undoLastDecision(String branchId, String actor, long expectedRevision) {
        Event last = lastDecisionEvent(branchId);
        if (last == null) {
            throw new IllegalStateException("no decision to undo on branch " + branchId);
        }
        String undoEventId = newId();
        String timestamp = now();
        Event[] holder = new Event[1];
        tx(() -> {
            ObjectNode payload = Json.object();
            payload.put("undoesEventId", last.eventId);
            holder[0] = appendEvent(branchId, undoEventId, "DECISION_UNDONE", actor,
                    "undo " + last.kind, payload, expectedRevision, timestamp);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE decisions SET undone_by_event_id = ? WHERE event_id = ?")) {
                ps.setString(1, undoEventId);
                ps.setString(2, last.eventId);
                ps.executeUpdate();
            }
        });
        return holder[0];
    }

    private Event lastDecisionEvent(String branchId) {
        List<Event> events = events(branchId);
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e.kind.startsWith("DECISION_ACCEPTED") || e.kind.startsWith("DECISION_REJECTED")) {
                List<Decision> decisions = decisionsForEvent(e.eventId);
                if (!decisions.isEmpty() && decisions.get(0).undoneByEventId == null) {
                    return e;
                }
            }
        }
        return null;
    }

    public List<Decision> decisionsForEvent(String eventId) {
        return queryCon("SELECT * FROM decisions WHERE event_id = ?",
                ps -> ps.setString(1, eventId), Repository::readDecision);
    }

    public List<Decision> decisionsOnBranch(String branchId) {
        List<Decision> out = new ArrayList<>();
        for (Event event : events(branchId)) {
            out.addAll(decisionsForEvent(event.eventId));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // archive export / import with stable identities
    // ------------------------------------------------------------------

    public ObjectNode exportMaterial(String materialId) {
        ObjectNode archive = Json.object();
        archive.put("format", "polroom-archive/v1");
        archive.put("exportedAt", now());
        Map<String, Object> material = queryCon(
                "SELECT material_id, name, created_event_id, created_at FROM materials "
                        + "WHERE material_id = ?", ps -> ps.setString(1, materialId),
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("materialId", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("createdEventId", rs.getString(3));
                    m.put("createdAt", rs.getString(4));
                    return m;
                }).get(0);
        archive.set("material", Json.MAPPER.valueToTree(material));

        ArrayNode sources = archive.putArray("sources");
        for (SourceVersion source : sources(materialId)) {
            ObjectNode node = Json.object();
            node.put("sourceId", source.sourceId);
            node.put("version", source.version);
            node.put("kind", source.kind);
            node.put("fingerprint", source.fingerprint);
            node.put("unit", source.unit);
            node.put("coordinateBasis", source.coordinateBasis);
            node.put("rawRepr", source.rawRepr);
            node.set("payload", source.payload);
            node.put("supersedesSourceId", valueOrNull(source.supersedesSourceId));
            node.put("createdEventId", source.eventId);
            node.put("createdAt", source.createdAt);
            node.set("diagnostics", Json.MAPPER.valueToTree(source.diagnostics));
            sources.add(node);
        }
        ArrayNode branches = archive.putArray("branches");
        for (Map<String, Object> branch : branches(materialId)) {
            String branchId = (String) branch.get("branchId");
            ObjectNode node = Json.MAPPER.valueToTree(branch);
            ArrayNode events = node.putArray("events");
            for (Event event : events(branchId)) {
                ObjectNode en = Json.object();
                en.put("eventId", event.eventId);
                en.put("seq", event.seq);
                en.put("parentEventId", valueOrNull(event.parentEventId));
                en.put("kind", event.kind);
                en.put("occurredAt", event.occurredAt);
                en.put("actor", event.actor);
                en.put("summary", event.summary);
                en.set("payload", event.payload);
                ArrayNode dec = en.putArray("decisions");
                for (Decision decision : decisionsForEvent(event.eventId)) {
                    dec.add(Json.MAPPER.valueToTree(new String[]{
                            decision.decisionId, decision.candidateId, decision.verdict,
                            decision.rationale == null ? "" : decision.rationale,
                            decision.undoneByEventId == null ? "" : decision.undoneByEventId,
                            decision.createdAt}));
                }
                events.add(en);
            }
            branches.add(node);
        }
        ArrayNode derivations = archive.putArray("derivations");
        for (Map<String, Object> derivation : allDerivationsForMaterial(materialId)) {
            derivations.add(Json.MAPPER.valueToTree(derivation));
        }
        ArrayNode invalidations = archive.putArray("invalidations");
        for (Map<String, Object> invalidation : invalidationsForMaterial(materialId)) {
            invalidations.add(Json.MAPPER.valueToTree(invalidation));
        }
        return archive;
    }

    private List<Map<String, Object>> allDerivationsForMaterial(String materialId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> branch : branches(materialId)) {
            for (String fingerprint : derivationFingerprintsAt(
                    (String) branch.get("branchId"), (String) branch.get("headEventId"))) {
                Map<String, Object> d = derivation(fingerprint);
                if (d != null && !out.stream().anyMatch(x -> x.get("fingerprint").equals(fingerprint))) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> invalidationsForMaterial(String materialId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> branch : branches(materialId)) {
            String branchId = (String) branch.get("branchId");
            for (Event event : events(branchId)) {
                if ("DERIVATION_INVALIDATED".equals(event.kind)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("eventId", event.eventId);
                    m.put("branchId", branchId);
                    m.put("payload", event.payload);
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Import an archive preserving every id and ordering.  The whole import is
     * a single transaction: any failure rolls back and leaves no partial rows.
     */
    public void importMaterial(JsonNode archive) {
        tx(() -> {
            JsonNode material = archive.get("material");
            String materialId = material.get("materialId").asText();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO materials(material_id, name, created_event_id, created_at) "
                            + "VALUES(?,?,?,?)")) {
                ps.setString(1, materialId);
                ps.setString(2, material.get("name").asText());
                ps.setString(3, material.get("createdEventId").asText());
                ps.setString(4, material.get("createdAt").asText());
                ps.executeUpdate();
            }
            for (JsonNode source : archive.get("sources")) {
                List<String> diagnostics = Json.MAPPER.convertValue(source.get("diagnostics"),
                        new TypeReference<List<String>>() {
                        });
                bindSourceFromNode(materialId, source, diagnostics);
            }
            for (JsonNode branch : archive.get("branches")) {
                String branchId = branch.get("branchId").asText();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO branches(branch_id, material_id, name, parent_branch_id, "
                                + "forked_from_event_id, created_at, color_label) "
                                + "VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, branchId);
                    ps.setString(2, materialId);
                    ps.setString(3, branch.get("name").asText());
                    ps.setString(4, textOrNull(branch, "parentBranchId"));
                    ps.setString(5, textOrNull(branch, "forkedFromEventId"));
                    ps.setString(6, branch.get("createdAt").asText());
                    ps.setString(7, null);
                    ps.executeUpdate();
                }
                for (JsonNode event : branch.get("events")) {
                    importEvent(branchId, event);
                }
                String headEventId = branch.get("headEventId").asText();
                int headSeq = branch.get("headSeq").asInt();
                long revision = branch.get("revision").asLong();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO branch_heads(branch_id, head_event_id, head_seq, revision) "
                                + "VALUES(?,?,?,?)")) {
                    ps.setString(1, branchId);
                    ps.setString(2, headEventId);
                    ps.setInt(3, headSeq);
                    ps.setLong(4, revision);
                    ps.executeUpdate();
                }
            }
            for (JsonNode derivation : archive.get("derivations")) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO derivations(fingerprint, rule_version, stokes_order, "
                                + "rotation_convention, basis_json, params_json, result_json, "
                                + "created_event_id, created_at) VALUES(?,?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT(fingerprint) DO NOTHING")) {
                    ps.setString(1, derivation.get("fingerprint").asText());
                    ps.setString(2, derivation.get("ruleVersion").asText());
                    ps.setString(3, derivation.get("stokesOrder").asText());
                    ps.setString(4, derivation.get("rotationConvention").asText());
                    ps.setString(5, derivation.get("basis").toString());
                    ps.setString(6, derivation.get("params").toString());
                    ps.setString(7, derivation.get("result").toString());
                    ps.setString(8, derivation.get("fingerprint").asText());
                    ps.setString(9, now());
                    ps.executeUpdate();
                }
            }
        });
    }

    private void importEvent(String branchId, JsonNode event) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events(event_id, branch_id, seq, parent_event_id, kind, "
                        + "occurred_at, actor, summary, payload) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, event.get("eventId").asText());
            ps.setString(2, branchId);
            ps.setInt(3, event.get("seq").asInt());
            ps.setString(4, textOrNull(event, "parentEventId"));
            ps.setString(5, event.get("kind").asText());
            ps.setString(6, event.get("occurredAt").asText());
            ps.setString(7, event.get("actor").asText());
            ps.setString(8, event.get("summary").asText());
            ps.setString(9, event.get("payload").toString());
            ps.executeUpdate();
        }
        if (event.has("decisions")) {
            for (JsonNode decision : event.get("decisions")) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO decisions(decision_id, event_id, candidate_id, verdict, "
                                + "rationale, undone_by_event_id, created_at) VALUES(?,?,?,?,?,?,?)")) {
                    ps.setString(1, decision.get(0).asText());
                    ps.setString(2, event.get("eventId").asText());
                    ps.setString(3, decision.get(1).asText());
                    ps.setString(4, decision.get(2).asText());
                    ps.setString(5, decision.get(3).asText());
                    String undone = decision.get(4).asText();
                    ps.setString(6, undone.isEmpty() ? null : undone);
                    ps.setString(7, decision.get(5).asText());
                    ps.executeUpdate();
                }
            }
        }
    }

    private void bindSourceFromNode(String materialId, JsonNode source, List<String> diagnostics)
            throws Exception {
        JsonNode supersede = source.get("supersedesSourceId");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sources(source_id, version, material_id, kind, fingerprint, unit, "
                        + "coordinate_basis, raw_repr, payload, supersedes_source_id, "
                        + "created_event_id, created_at, diagnostics) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, source.get("sourceId").asText());
            ps.setInt(2, source.get("version").asInt());
            ps.setString(3, materialId);
            ps.setString(4, source.get("kind").asText());
            ps.setString(5, source.get("fingerprint").asText());
            ps.setString(6, source.get("unit").asText());
            ps.setString(7, source.get("coordinateBasis").asText());
            ps.setString(8, source.get("rawRepr").asText());
            ps.setString(9, source.get("payload").toString());
            ps.setString(10, supersede == null || supersede.isNull() ? null : supersede.asText());
            ps.setString(11, source.get("createdEventId").asText());
            ps.setString(12, source.get("createdAt").asText());
            ps.setString(13, Json.MAPPER.writeValueAsString(diagnostics));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    interface SqlRunner {
        void run() throws Exception;
    }

    private void tx(SqlRunner runner) {
        try {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            runner.run();
            connection.commit();
            connection.setAutoCommit(previous);
        } catch (ConflictException e) {
            rollback();
            throw e;
        } catch (Exception e) {
            rollback();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void bindSource(PreparedStatement ps, String sourceId, int version, String materialId,
                            String kind, String fingerprint, String unit, String coordinateBasis,
                            String rawRepr, JsonNode payload, String supersedesSourceId,
                            String eventId, String timestamp, List<String> diagnostics)
            throws Exception {
        ps.setString(1, sourceId);
        ps.setInt(2, version);
        ps.setString(3, materialId);
        ps.setString(4, kind);
        ps.setString(5, fingerprint);
        ps.setString(6, unit);
        ps.setString(7, coordinateBasis);
        ps.setString(8, rawRepr);
        ps.setString(9, payload.toString());
        ps.setString(10, supersedesSourceId);
        ps.setString(11, eventId);
        ps.setString(12, timestamp);
        ps.setString(13, Json.MAPPER.writeValueAsString(diagnostics));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrNull(String value) {
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static JsonNode readJson(String text) {
        try {
            return Json.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SourceVersion readSource(ResultSet rs) throws SQLException {
        try {
            return new SourceVersion(rs.getString("source_id"), rs.getInt("version"),
                    rs.getString("material_id"), rs.getString("kind"),
                    rs.getString("fingerprint"), rs.getString("unit"),
                    rs.getString("coordinate_basis"), rs.getString("raw_repr"),
                    Json.MAPPER.readTree(rs.getString("payload")),
                    rs.getString("supersedes_source_id"), rs.getString("created_event_id"),
                    rs.getString("created_at"),
                    Json.MAPPER.readValue(rs.getString("diagnostics"),
                            new TypeReference<List<String>>() {
                            }));
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private static Event readEvent(ResultSet rs) throws SQLException {
        try {
            return new Event(rs.getString("event_id"), rs.getString("branch_id"),
                    rs.getInt("seq"), rs.getString("parent_event_id"), rs.getString("kind"),
                    rs.getString("occurred_at"), rs.getString("actor"), rs.getString("summary"),
                    Json.MAPPER.readTree(rs.getString("payload")));
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private static Decision readDecision(ResultSet rs) throws SQLException {
        return new Decision(rs.getString("decision_id"), rs.getString("event_id"),
                rs.getString("candidate_id"), rs.getString("verdict"),
                rs.getString("rationale"), rs.getString("undone_by_event_id"),
                rs.getString("created_at"));
    }

    interface Mapper<T> {
        T map(ResultSet rs) throws Exception;
    }

    interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private <T> List<T> query(String sql, Mapper<T> mapper) {
        return queryCon(sql, ps -> {
        }, mapper);
    }

    private <T> List<T> queryCon(String sql, Binder binder, Mapper<T> mapper) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    T value = mapper.map(rs);
                    if (value != null) {
                        out.add(value);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
