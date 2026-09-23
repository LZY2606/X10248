package polarmueller.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import polarmueller.store.Db;
import polarmueller.store.Store;

import java.sql.ResultSet;
import java.util.Base64;

/**
 * Full-fidelity export/import. Identities, ordering and provenance links are
 * preserved verbatim, so a delete-then-reimport cycle is lossless.
 */
public final class Archive {
    private Archive() {}

    public static JsonNode export(Db db) {
        ObjectNode root = Store.JSON.createObjectNode();
        root.put("archive_format", "polar-mueller-archive/v1");
        root.put("schema_version", db.schemaVersion());
        root.set("sources", dump(db, "SELECT id,version,kind,media_type,units,frame,raw,content_hash,imported_at FROM sources ORDER BY id,version",
                new String[]{"id","version","kind","media_type","units","frame","raw","content_hash","imported_at"}, true));
        root.set("branches", dump(db, "SELECT id,name,parent_id,created_at FROM branches ORDER BY created_at,id",
                new String[]{"id","name","parent_id","created_at"}, false));
        root.set("events", dump(db, "SELECT branch_id,seq,id,type,payload,created_at FROM events ORDER BY branch_id,seq",
                new String[]{"branch_id","seq","id","type","payload","created_at"}, false));
        root.set("derivations", dump(db, "SELECT id,branch_id,event_seq,kind,inputs,params,fingerprint,status,result,diagnostics,stale FROM derivations ORDER BY event_seq,id",
                new String[]{"id","branch_id","event_seq","kind","inputs","params","fingerprint","status","result","diagnostics","stale"}, false));
        root.set("adjudications", dump(db, "SELECT id,branch_id,event_seq,target_id,decision,rationale,actor,reverted_by FROM adjudications ORDER BY event_seq,id",
                new String[]{"id","branch_id","event_seq","target_id","decision","rationale","actor","reverted_by"}, false));
        return root;
    }

    private static ArrayNode dump(Db db, String sql, String[] cols, boolean blobLast) {
        ArrayNode arr = Store.JSON.createArrayNode();
        try (var st = db.conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ObjectNode row = arr.addObject();
                for (int i = 0; i < cols.length; i++) {
                    Object v = rs.getObject(i + 1);
                    if (v == null) row.putNull(cols[i]);
                    else if (v instanceof byte[] b) row.put(cols[i], Base64.getEncoder().encodeToString(b));
                    else if (v instanceof Number n) row.putPOJO(cols[i], n);
                    else row.put(cols[i], v.toString());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("export failed", e);
        }
        return arr;
    }

    /** Import into an empty database, preserving all identities and ordering. */
    public static void importArchive(Db db, JsonNode root) {
        if (!root.get("archive_format").asText().equals("polar-mueller-archive/v1"))
            throw new IllegalArgumentException("unsupported archive format");
        try {
            db.conn().setAutoCommit(false);
            for (JsonNode r : root.get("sources")) {
                try (var ps = db.conn().prepareStatement(
                        "INSERT INTO sources(id,version,kind,media_type,units,frame,raw,content_hash,imported_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, r.get("id").asText());
                    ps.setInt(2, r.get("version").asInt());
                    ps.setString(3, r.get("kind").asText());
                    ps.setString(4, r.get("media_type").asText());
                    ps.setString(5, r.get("units").isNull() ? null : r.get("units").asText());
                    ps.setString(6, r.get("frame").isNull() ? null : r.get("frame").asText());
                    ps.setBytes(7, Base64.getDecoder().decode(r.get("raw").asText()));
                    ps.setString(8, r.get("content_hash").asText());
                    ps.setString(9, r.get("imported_at").asText());
                    ps.executeUpdate();
                }
            }
            insertRows(db, root.get("branches"),
                    "INSERT INTO branches(id,name,parent_id,created_at) VALUES(?,?,?,?)",
                    new String[]{"id","name","parent_id","created_at"});
            insertRows(db, root.get("events"),
                    "INSERT INTO events(branch_id,seq,id,type,payload,created_at) VALUES(?,?,?,?,?,?)",
                    new String[]{"branch_id","seq","id","type","payload","created_at"});
            insertRows(db, root.get("derivations"),
                    "INSERT INTO derivations(id,branch_id,event_seq,kind,inputs,params,fingerprint,status,result,diagnostics,stale) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id","branch_id","event_seq","kind","inputs","params","fingerprint","status","result","diagnostics","stale"});
            insertRows(db, root.get("adjudications"),
                    "INSERT INTO adjudications(id,branch_id,event_seq,target_id,decision,rationale,actor,reverted_by) VALUES(?,?,?,?,?,?,?,?)",
                    new String[]{"id","branch_id","event_seq","target_id","decision","rationale","actor","reverted_by"});
            db.conn().commit();
            db.conn().setAutoCommit(true);
        } catch (Exception e) {
            try { db.conn().rollback(); db.conn().setAutoCommit(true); }
            catch (Exception ex) { throw new RuntimeException(ex); }
            throw new RuntimeException("import failed; rolled back", e);
        }
    }

    private static void insertRows(Db db, JsonNode rows, String sql, String[] cols) throws Exception {
        for (JsonNode r : rows) {
            try (var ps = db.conn().prepareStatement(sql)) {
                for (int i = 0; i < cols.length; i++) {
                    JsonNode v = r.get(cols[i]);
                    if (v == null || v.isNull()) ps.setObject(i + 1, null);
                    else if (v.isIntegralNumber()) ps.setLong(i + 1, v.asLong());
                    else if (v.isNumber()) ps.setDouble(i + 1, v.asDouble());
                    else ps.setString(i + 1, v.asText());
                }
                ps.executeUpdate();
            }
        }
    }
}
