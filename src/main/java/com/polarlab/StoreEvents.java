package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event/decision/undo operations for {@link Store}; split out to keep files reviewable. */
final class StoreEvents {
    private StoreEvents() {}

    public static final String RULE_VERSION = "rules/mueller-v1.0.0";

    record BranchState(String headEvent, int revision, int baseRevision) {}

    static BranchState branchState(Store store, String branchId) throws SQLException {
        try (PreparedStatement ps = store.database().conn().prepareStatement(
                "SELECT head_event,revision,base_revision FROM branches WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown branch " + branchId);
                }
                return new BranchState(rs.getString(1), rs.getInt(2), rs.getInt(3));
            }
        }
    }

    /**
     * Append an event with optimistic concurrency: client sends the revision it viewed.
     * When another window committed first, a {@link Store.ConflictException} carries the diff,
     * the original row is NOT modified and nothing is written by this call.
     */
    public static Store.EventOut append(Store store, String branchId, String type, ObjectNode payload,
                                        List<String> sourceRefs, int expectedRevision) throws SQLException {
        Db db = store.database();
        BranchState bs = branchState(store, branchId);
        if (expectedRevision >= 0 && bs.revision() != expectedRevision) {
            throw new Store.ConflictException(diffSince(store, branchId, expectedRevision), bs.revision());
        }
        int seq = bs.revision() + 1;
        payload.put("seq", seq);
        payload.put("committedAt", Instant.now().toString());
        String refs = Store.OM.valueToTree(sourceRefs).toString();
        String fp = Fingerprint.canonical(Store.OM.valueToTree(canonicalEnvelope(type, payload, refs, RULE_VERSION)));
        String eventId = "ev-" + branchId.substring(Math.min(3, branchId.length())) + "-" + seq + "-" + fp.substring(0, 10);

        // compute invalidation scope from explicit dependency declarations in payload.dependsOn
        List<String> invalidated = computeInvalidated(store, branchId, payload);

        try (PreparedStatement ps = db.conn().prepareStatement("""
                INSERT INTO events(event_id,branch_id,seq,prev_event_id,event_type,payload_json,
                                   fingerprint,rule_version,source_refs,invalidates)
                VALUES(?,?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, eventId);
            ps.setString(2, branchId);
            ps.setInt(3, seq);
            ps.setString(4, bs.headEvent());
            ps.setString(5, type);
            ps.setString(6, Store.OM.writeValueAsString(payload));
            ps.setString(7, fp);
            ps.setString(8, RULE_VERSION);
            ps.setString(9, refs);
            ps.setString(10, Store.OM.valueToTree(invalidated).toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException(e);
        }
        for (String derivedId : invalidated) {
            try (PreparedStatement up = db.conn().prepareStatement(
                    "UPDATE derived SET invalidated_at_event=? WHERE derived_id=? AND invalidated_at_event IS NULL")) {
                up.setString(1, eventId);
                up.setString(2, derivedId);
                up.executeUpdate();
            }
        }
        try (PreparedStatement up = db.conn().prepareStatement(
                "UPDATE branches SET head_event=?, revision=?, base_revision=? WHERE branch_id=?")) {
            up.setString(1, eventId);
            up.setInt(2, seq);
            up.setInt(3, seq);
            up.setString(4, branchId);
            up.executeUpdate();
        }
        return store.events(branchId, seq).get(seq - 1);
    }

    /**
     * Invalidated derived objects are exactly those whose dependency set intersects the changed
     * source refs of the new event. Parameter/reference revisions never cause a global replace.
     */
    private static List<String> computeInvalidated(Store store, String branchId, ObjectNode payload) throws SQLException {
        Set<String> changed = new HashSet<>();
        JsonNode refs = payload.get("sourceRefsChanged");
        if (refs != null) {
            refs.forEach(n -> changed.add(n.asText()));
        }
        if (changed.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = store.database().conn().prepareStatement("""
                SELECT derived_id,value_json FROM derived
                WHERE invalidated_at_event IS NULL""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    JsonNode v = Store.OM.readTree(rs.getString(2));
                    JsonNode dep = v.path("dependsOnSources");
                    if (dep.isArray()) {
                        for (JsonNode d : dep) {
                            if (changed.contains(d.asText())) {
                                out.add(id);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
        return out;
    }

    /** Human/machine readable diff between client base revision and current branch head. */
    static ObjectNode diffSince(Store store, String branchId, int clientRevision) {
        try {
            ObjectNode diff = Store.OM.createObjectNode();
            diff.put("branchId", branchId);
            diff.put("clientBaseRevision", clientRevision);
            List<Store.EventOut> all = store.events(branchId);
            diff.put("serverRevision", all.size());
            ArrayNode theirs = diff.putArray("serverEventsAfterBase");
            for (Store.EventOut e : all) {
                if (e.seq() > clientRevision) {
                    ObjectNode item = theirs.addObject();
                    item.put("seq", e.seq());
                    item.put("eventId", e.eventId());
                    item.put("type", e.type());
                    item.set("summary", e.payload().path("summary"));
                }
            }
            return diff;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----- derived records -----

    public static void recordDerived(Store store, String eventId, String kind, String name,
                                     ObjectNode value, String status, String identity) throws SQLException {
        String derivedId = "der-" + kind + "-" + identity.substring(0, 12);
        try (PreparedStatement ps = store.database().conn().prepareStatement("""
                INSERT INTO derived(derived_id,event_id,kind,name,value_json,status,identity,valid_from_event)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(derived_id) DO UPDATE SET value_json=excluded.value_json,
                    status=excluded.status, valid_from_event=excluded.valid_from_event,
                    invalidated_at_event=NULL""")) {
            ps.setString(1, derivedId);
            ps.setString(2, eventId);
            ps.setString(3, kind);
            ps.setString(4, name);
            ps.setString(5, Store.OM.writeValueAsString(value));
            ps.setString(6, status);
            ps.setString(7, identity);
            ps.setString(8, eventId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public static List<ObjectNode> activeDerived(Store store) throws SQLException {
        List<ObjectNode> out = new ArrayList<>();
        try (PreparedStatement ps = store.database().conn().prepareStatement(
                "SELECT derived_id,event_id,kind,name,value_json,status,identity,valid_from_event,invalidated_at_event FROM derived ORDER BY kind");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode n = Store.OM.createObjectNode();
                n.put("derivedId", rs.getString(1));
                n.put("eventId", rs.getString(2));
                n.put("kind", rs.getString(3));
                n.put("name", rs.getString(4));
                n.set("value", Store.OM.readTree(rs.getString(5)));
                n.put("status", rs.getString(6));
                n.put("identity", rs.getString(7));
                n.put("validFromEvent", rs.getString(8));
                if (rs.getString(9) != null) {
                    n.put("invalidatedAtEvent", rs.getString(9));
                }
                out.add(n);
            }
        } catch (Exception e) {
            throw new SQLException(e);
        }
        return out;
    }

    // ----- decisions (accept / reject), reversible -----

    public record Decision(String decisionId, String branchId, String eventId, String verdict,
                           String targetKind, String targetId, String rationale, String supersededBy) {}

    public static String decide(Store store, String branchId, String verdict, String targetKind,
                                String targetId, String rationale, int expectedRevision) throws SQLException {
        ObjectNode payload = Store.OM.createObjectNode();
        payload.put("summary", verdict.toLowerCase() + " " + targetKind + " " + targetId);
        payload.put("verdict", verdict);
        payload.put("targetKind", targetKind);
        payload.put("targetId", targetId);
        payload.put("rationale", rationale == null ? "" : rationale);
        Store.EventOut ev = append(store, branchId, "DECISION", payload, List.of(), expectedRevision);
        String decisionId = "dec-" + ev.seq() + "-" + targetKind + "-" + targetId.substring(Math.min(8, targetId.length()));
        try (PreparedStatement ps = store.database().conn().prepareStatement("""
                INSERT INTO decisions(decision_id,branch_id,event_id,verdict,target_kind,target_id,rationale)
                VALUES(?,?,?,?,?,?,?)""")) {
            ps.setString(1, decisionId);
            ps.setString(2, branchId);
            ps.setString(3, ev.eventId());
            ps.setString(4, verdict);
            ps.setString(5, targetKind);
            ps.setString(6, targetId);
            ps.setString(7, rationale == null ? "" : rationale);
            ps.executeUpdate();
        }
        return decisionId;
    }

    /** Undo restores the previous branch state by recording a compensating event; lineage is preserved. */
    public static Store.EventOut undo(Store store, String branchId, int expectedRevision) throws SQLException {
        BranchState bs = branchState(store, branchId);
        if (bs.revision() == 0) {
            throw new IllegalStateException("nothing to undo on branch " + branchId);
        }
        Store.EventOut last = store.events(branchId, bs.revision()).get(bs.revision() - 1);
        ObjectNode payload = Store.OM.createObjectNode();
        payload.put("summary", "undo event " + last.eventId());
        payload.put("undoneEvent", last.eventId());
        payload.put("undoneType", last.type());
        payload.set("undonePayload", last.payload());
        Store.EventOut undoEv = append(store, branchId, "UNDO", payload, List.of(), expectedRevision);

        // revive derived objects invalidated exactly by the undone event
        try (PreparedStatement up = store.database().conn().prepareStatement(
                "UPDATE derived SET invalidated_at_event=NULL WHERE invalidated_at_event=?")) {
            up.setString(1, last.eventId());
            up.executeUpdate();
        }
        if ("DECISION".equals(last.type())) {
            try (PreparedStatement up = store.database().conn().prepareStatement(
                    "UPDATE decisions SET superseded_by=? WHERE event_id=?")) {
                up.setString(1, undoEv.eventId());
                up.setString(2, last.eventId());
                up.executeUpdate();
            }
        }
        return undoEv;
    }

    public static List<Decision> decisions(Store store, String branchId) throws SQLException {
        List<Decision> out = new ArrayList<>();
        try (PreparedStatement ps = store.database().conn().prepareStatement("""
                SELECT decision_id,branch_id,event_id,verdict,target_kind,target_id,rationale,superseded_by
                FROM decisions WHERE branch_id=? ORDER BY rowid""")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Decision(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> canonicalEnvelope(String type, ObjectNode payload, String refs, String rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", type);
        m.put("payload", Fingerprint.sortKeys(Store.OM.valueToTree(payload)));
        m.put("ruleVersion", rules);
        m.put("sourceRefs", Store.OM.valueToTree(Store.jsonList(refs)));
        return m;
    }
}
