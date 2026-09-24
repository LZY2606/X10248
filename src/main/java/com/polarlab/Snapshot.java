package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full workspace snapshot as one JSON bundle. Because every identity is content-derived
 * (blobs by sha256, events by canonical-content fingerprint), deleting the local database
 * and re-importing the bundle restores identical ids, ordering and provenance links.
 */
public final class Snapshot {
    private Snapshot() {}

    public static ObjectNode exportBundle(Store store) throws Exception {
        ObjectNode root = Store.OM.createObjectNode();
        root.put("format", "polar-mueller-lab/bundle-v1");
        root.put("ruleVersion", StoreEvents.RULE_VERSION);
        root.set("schemaMeta", table(store, "schema_meta", "version", "applied_at"));
        root.set("branches", table(store, "branches",
                "branch_id", "name", "parent_branch", "head_event", "revision", "base_revision", "created_at"));
        root.set("sources", table(store, "sources",
                "source_id", "kind", "label", "version", "supersedes", "sha256",
                "representation", "unit_frame", "diagnostic", "created_at"));
        root.set("events", table(store, "events",
                "event_id", "branch_id", "seq", "prev_event_id", "event_type", "payload_json",
                "fingerprint", "rule_version", "source_refs", "invalidates", "created_at"));
        root.set("decisions", table(store, "decisions",
                "decision_id", "branch_id", "event_id", "verdict", "target_kind",
                "target_id", "rationale", "superseded_by", "created_at"));
        root.set("derived", table(store, "derived",
                "derived_id", "event_id", "kind", "name", "value_json", "status",
                "identity", "valid_from_event", "invalidated_at_event"));
        root.set("conflicts", table(store, "conflicts",
                "conflict_id", "branch_id", "winner_event", "rejected_event",
                "base_revision", "diff_json", "status", "created_at"));

        ArrayNode blobs = root.putArray("blobs");
        try (Statement st = store.database().conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT sha256,path,bytes,media_type FROM blobs ORDER BY sha256")) {
            while (rs.next()) {
                ObjectNode b = blobs.addObject();
                b.put("sha256", rs.getString(1));
                b.put("bytes", rs.getLong(3));
                b.put("mediaType", rs.getString(4));
                b.put("dataBase64", Base64.getEncoder().encodeToString(store.readBlob(rs.getString(1))));
            }
        }
        return root;
    }

    private static ArrayNode table(Store store, String table, String... cols) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.length; i++) {
            sql.append(i == 0 ? "" : ", ").append(cols[i]);
        }
        sql.append(" FROM ").append(table);
        ArrayNode out = Store.OM.createArrayNode();
        try (Statement st = store.database().conn().createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                ObjectNode row = out.addObject();
                for (int i = 0; i < cols.length; i++) {
                    String v = rs.getString(i + 1);
                    if (v != null) {
                        row.put(cols[i], v);
                    }
                }
            }
        }
        return out;
    }

    /** Import into a freshly migrated database; verifies content identities as it writes. */
    public static void importBundle(Store store, ObjectNode root, Path blobRoot) throws Exception {
        for (JsonNode b : root.path("blobs")) {
            byte[] data = Base64.getDecoder().decode(b.get("dataBase64").asText());
            String sha = store.saveBlob(data, b.path("mediaType").asText("application/octet-stream"));
            if (!sha.equals(b.get("sha256").asText())) {
                throw new IllegalStateException("blob content mismatch on import: " + b.get("sha256").asText());
            }
        }
        importRows(store, "blobs", null); // already handled
        for (JsonNode r : root.path("sources")) {
            insert(store, "sources",
                    new String[]{"source_id","kind","label","version","supersedes","sha256",
                            "representation","unit_frame","diagnostic","created_at"}, r);
        }
        for (JsonNode r : root.path("branches")) {
            insert(store, "branches",
                    new String[]{"branch_id","name","parent_branch","head_event","revision",
                            "base_revision","created_at"}, r);
        }
        // events in seq order preserves prev links
        Map<String, Integer> order = new LinkedHashMap<>();
        for (JsonNode r : root.path("events")) {
            order.put(r.get("event_id").asText(), order.size());
        }
        java.util.List<JsonNode> evs = new java.util.ArrayList<>();
        root.path("events").forEach(evs::add);
        evs.sort((a, b) -> {
            int cmp = a.get("branch_id").asText().compareTo(b.get("branch_id").asText());
            return cmp != 0 ? cmp : Integer.compare(a.get("seq").asInt(), b.get("seq").asInt());
        });
        for (JsonNode r : evs) {
            insert(store, "events",
                    new String[]{"event_id","branch_id","seq","prev_event_id","event_type","payload_json",
                            "fingerprint","rule_version","source_refs","invalidates","created_at"}, r);
        }
        for (JsonNode r : root.path("decisions")) {
            insert(store, "decisions",
                    new String[]{"decision_id","branch_id","event_id","verdict","target_kind",
                            "target_id","rationale","superseded_by","created_at"}, r);
        }
        for (JsonNode r : root.path("derived")) {
            insert(store, "derived",
                    new String[]{"derived_id","event_id","kind","name","value_json","status",
                            "identity","valid_from_event","invalidated_at_event"}, r);
        }
        for (JsonNode r : root.path("conflicts")) {
            insert(store, "conflicts",
                    new String[]{"conflict_id","branch_id","winner_event","rejected_event",
                            "base_revision","diff_json","status","created_at"}, r);
        }
        store.commit();
    }

    private static void importRows(Store store, String t, Object o) { }

    private static void insert(Store store, String table, String[] cols, JsonNode r) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO ").append(table).append("(");
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            sql.append(i == 0 ? "" : ",").append(cols[i]);
            q.append(i == 0 ? "?" : ",?");
        }
        sql.append(") VALUES(").append(q).append(")");
        try (PreparedStatement ps = store.database().conn().prepareStatement(sql.toString())) {
            for (int i = 0; i < cols.length; i++) {
                JsonNode v = r.get(cols[i]);
                if (v == null || v.isNull()) {
                    ps.setNull(i + 1, java.sql.Types.VARCHAR);
                } else if (v.isNumber() && (cols[i].endsWith("seq") || cols[i].equals("version")
                        || cols[i].equals("revision") || cols[i].equals("base_revision")
                        || cols[i].equals("bytes"))) {
                    ps.setInt(i + 1, v.asInt());
                } else {
                    ps.setString(i + 1, v.asText());
                }
            }
            ps.executeUpdate();
        }
    }
}
