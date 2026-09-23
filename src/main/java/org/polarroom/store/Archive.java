package org.polarroom.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.polarroom.core.Models;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-workspace archive as one JSON document.
 *
 * <p>Blobs, source versions, branches, events, derived records and rulings are
 * written in dependency order. Re-importing into a fresh database preserves
 * every stable identity (sha256, event id, branch id, fingerprint) and the
 * parent/child ordering of the event graph.
 */
public final class Archive {

    public static final String FORMAT = "polarroom-archive-1.0";

    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] exportAll(Db db) {
        return db.transaction(d -> {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", FORMAT);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("schemaVersion", d.schemaVersion());

            List<Map<String, Object>> metas = new ArrayList<>();
            for (String k : List.of("case", "material", "wavelength_nm")) {
                String v = d.meta(k);
                if (v != null) {
                    metas.add(Map.of("key", k, "value", v));
                }
            }
            root.put("meta", metas);

            List<Map<String, Object>> blobs = new ArrayList<>();
            for (Map<String, Object> row : d.listBlobs()) {
                String sha = (String) row.get("sha256");
                byte[] content = d.getBlob(sha);
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("sha256", sha);
                bm.put("mediaType", row.get("mediaType"));
                bm.put("contentBase64",
                        Base64.getEncoder().encodeToString(content));
                blobs.add(bm);
            }
            root.put("blobs", blobs);

            List<Map<String, Object>> sources = new ArrayList<>();
            for (Models.SourceVersion v : d.listAllSourceVersions()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("materialId", v.materialId);
                sm.put("type", v.type.code);
                sm.put("versionNo", v.versionNo);
                sm.put("blobSha", v.blobSha);
                sm.put("importEventId", v.importEventId);
                sm.put("originalFilename", v.originalFilename);
                sm.put("note", v.note);
                sources.add(sm);
            }
            root.put("sources", sources);

            List<Map<String, Object>> branches = new ArrayList<>();
            for (Models.Branch b : d.listBranches()) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("branchId", b.branchId);
                bm.put("name", b.name);
                bm.put("parentBranchId", b.parentBranchId);
                bm.put("baseEventId", b.baseEventId);
                bm.put("headEventId", b.headEventId);
                bm.put("purpose", b.purpose);
                bm.put("createdAt", b.createdAt);
                branches.add(bm);
            }
            root.put("branches", branches);

            List<Map<String, Object>> events = new ArrayList<>();
            for (Models.Branch b : d.listBranches()) {
                for (Models.Event e : d.listEvents(b.branchId)) {
                    events.add(eventMap(e));
                }
            }
            root.put("events", events);

            List<Map<String, Object>> derived = new ArrayList<>();
            for (Models.Branch b : d.listBranches()) {
                for (Models.DerivedRecord r : d.listDerivedBranch(b.branchId)) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("derivedId", r.derivedId);
                    rm.put("branchId", r.branchId);
                    rm.put("eventId", r.eventId);
                    rm.put("kind", r.kind);
                    rm.put("candidateKey", r.candidateKey);
                    rm.put("fingerprint", r.fingerprint);
                    rm.put("json", r.json);
                    derived.add(rm);
                }
            }
            root.put("derived", derived);

            try {
                return mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> eventMap(Models.Event e) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("eventId", e.eventId);
        em.put("branchId", e.branchId);
        em.put("seq", e.seq);
        em.put("parentEventId", e.parentEventId);
        em.put("kind", e.kind);
        em.put("payloadJson", e.payloadJson);
        em.put("authorNote", e.authorNote);
        em.put("revertedOfEventId", e.revertedOfEventId);
        em.put("createdAt", e.createdAt);
        em.put("inputFingerprint", e.inputFingerprint);
        return em;
    }

    /** Imports an archive into a fresh database; identity fields are preserved. */
    @SuppressWarnings("unchecked")
    public void importAll(Db db, byte[] json) {
        db.transaction(d -> {
            if (d.listBranches().size() > 0) {
                throw new IllegalStateException(
                        "import target database must be empty");
            }
            Map<String, Object> root;
            try {
                root = mapper.readValue(json, Map.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (Map<String, Object> m : (List<Map<String, Object>>) root.get("meta")) {
                d.putMeta((String) m.get("key"), (String) m.get("value"));
            }
            for (Map<String, Object> b : (List<Map<String, Object>>) root.get("blobs")) {
                byte[] content = Base64.getDecoder()
                        .decode((String) b.get("contentBase64"));
                String sha = d.putBlob(content, (String) b.get("mediaType"));
                if (!sha.equals(b.get("sha256"))) {
                    throw new IllegalStateException("blob identity mismatch after import");
                }
            }
            for (Map<String, Object> s : (List<Map<String, Object>>) root.get("sources")) {
                Models.SourceVersion v = new Models.SourceVersion();
                v.materialId = (String) s.get("materialId");
                v.type = Models.MaterialType.fromCode((String) s.get("type"));
                v.versionNo = ((Number) s.get("versionNo")).intValue();
                v.blobSha = (String) s.get("blobSha");
                v.importEventId = (String) s.get("importEventId");
                v.originalFilename = (String) s.get("originalFilename");
                v.note = (String) s.get("note");
                d.addSourceVersion(v);
            }
            for (Map<String, Object> b : (List<Map<String, Object>>) root.get("branches")) {
                Models.Branch br = new Models.Branch();
                br.branchId = (String) b.get("branchId");
                br.name = (String) b.get("name");
                br.parentBranchId = (String) b.get("parentBranchId");
                br.baseEventId = (String) b.get("baseEventId");
                br.headEventId = (String) b.get("headEventId");
                br.purpose = (String) b.get("purpose");
                br.createdAt = ((Number) b.get("createdAt")).longValue();
                d.insertBranch(br);
            }
            for (Map<String, Object> e : (List<Map<String, Object>>) root.get("events")) {
                Models.Event ev = new Models.Event();
                ev.eventId = (String) e.get("eventId");
                ev.branchId = (String) e.get("branchId");
                ev.seq = ((Number) e.get("seq")).intValue();
                ev.parentEventId = (String) e.get("parentEventId");
                ev.kind = (String) e.get("kind");
                ev.payloadJson = (String) e.get("payloadJson");
                ev.authorNote = (String) e.get("authorNote");
                ev.revertedOfEventId = (String) e.get("revertedOfEventId");
                ev.createdAt = ((Number) e.get("createdAt")).longValue();
                ev.inputFingerprint = (String) e.get("inputFingerprint");
                insertPreservingIdentity(d, ev);
            }
            for (Map<String, Object> r : (List<Map<String, Object>>) root.get("derived")) {
                Models.DerivedRecord rec = new Models.DerivedRecord();
                rec.derivedId = (String) r.get("derivedId");
                rec.branchId = (String) r.get("branchId");
                rec.eventId = (String) r.get("eventId");
                rec.kind = (String) r.get("kind");
                rec.candidateKey = (String) r.get("candidateKey");
                rec.fingerprint = (String) r.get("fingerprint");
                rec.json = (String) r.get("json");
                d.putDerived(rec);
            }
            return null;
        });
    }

    private void insertPreservingIdentity(Db db, Models.Event e) {
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO event(event_id, branch_id, seq, parent_event_id, kind, "
                        + "payload_json, author_note, reverted_of_event_id, created_at, "
                        + "input_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.eventId);
            ps.setString(2, e.branchId);
            ps.setInt(3, e.seq);
            ps.setString(4, e.parentEventId);
            ps.setString(5, e.kind);
            ps.setString(6, e.payloadJson);
            ps.setString(7, e.authorNote);
            ps.setString(8, e.revertedOfEventId);
            ps.setLong(9, e.createdAt);
            ps.setString(10, e.inputFingerprint);
            ps.executeUpdate();
        } catch (java.sql.SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public byte[] exportForHttp(Db db) {
        return exportAll(db);
    }
}
