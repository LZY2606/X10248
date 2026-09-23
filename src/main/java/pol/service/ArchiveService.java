package pol.service;

import pol.store.Database;
import pol.store.Event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-room JSON archive. Identities are content-derived UUIDv5 and event
 * sequence/parent links are stored explicitly, so deleting the database and
 * re-importing restores the exact ids, ordering and source relationships.
 */
public final class ArchiveService {

    private final Room room;
    private final Database db;

    public ArchiveService(Room room) {
        this.room = room;
        this.db = room.database();
    }

    public Map<String, Object> exportAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "polar-matrix-room-archive");
        out.put("schemaVersion", Database.CURRENT_SCHEMA);
        out.put("exportedAt", room.now());

        List<Map<String, Object>> blobs = new ArrayList<>();
        try (var conn = db.connection();
             var ps = conn.prepareStatement("SELECT id,sha256,media_type,byte_count,data,imported_at FROM blobs ORDER BY id");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("id", rs.getString(1));
                b.put("sha256", rs.getString(2));
                b.put("mediaType", rs.getString(3));
                b.put("byteCount", rs.getLong(4));
                b.put("dataBase64", java.util.Base64.getEncoder().encodeToString(rs.getBytes(5)));
                b.put("importedAt", rs.getString(6));
                blobs.add(b);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        out.put("blobs", blobs);

        List<Map<String, Object>> events = new ArrayList<>();
        for (Event e : db.allEvents()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("seq", Integer.parseInt(e.seq));
            m.put("branch", e.branch);
            m.put("type", e.type);
            m.put("parentId", e.parentId);
            m.put("createdAt", e.createdAt);
            m.put("actor", e.actor);
            m.put("payload", Json.read(e.payloadJson, Map.class));
            m.put("fingerprint", e.fingerprint);
            m.put("sources", e.sources());
            m.put("directSourceId", e.directSourceId);
            m.put("revisionOfId", e.revisionOfId);
            events.add(m);
        }
        out.put("events", events);

        List<Map<String, Object>> branches = new ArrayList<>();
        for (Database.BranchRow b : db.listBranches()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name());
            m.put("headSeq", b.headSeq());
            m.put("headEventId", b.headEventId());
            m.put("parentBranch", b.parentBranch());
            m.put("createdAt", b.createdAt());
            m.put("description", b.description());
            branches.add(m);
        }
        out.put("branches", branches);
        return out;
    }

    @SuppressWarnings("unchecked")
    public void importAll(Map<String, Object> archive, boolean replace) {
        db.transaction(() -> {
            if (replace) {
                try (var st = db.connection().createStatement()) {
                    st.execute("DELETE FROM pointer_log");
                    st.execute("DELETE FROM decision_locks");
                    st.execute("DELETE FROM events");
                    st.execute("DELETE FROM branches");
                    st.execute("DELETE FROM blobs");
                }
            }

            List<?> blobs = (List<?>) archive.getOrDefault("blobs", List.of());
            for (Object o : blobs) {
                Map<String, Object> b = (Map<String, Object>) o;
                String id = (String) b.get("id");
                if (!db.blobExists(id)) {
                    byte[] data = java.util.Base64.getDecoder().decode((String) b.get("dataBase64"));
                    String sha = verifySha(id, data, (String) b.get("sha256"));
                    db.putBlob(id, sha, (String) b.get("mediaType"),
                            ((Number) b.get("byteCount")).longValue(), data, (String) b.get("importedAt"));
                }
            }

            List<?> branches = (List<?>) archive.getOrDefault("branches", List.of());
            for (Object o : branches) {
                Map<String, Object> b = (Map<String, Object>) o;
                if (db.getBranch((String) b.get("name")) == null) {
                    db.putBranch((String) b.get("name"), 0, null,
                            (String) b.get("parentBranch"), (String) b.get("createdAt"),
                            (String) b.get("description"));
                }
            }

            List<Map<String, Object>> events = new ArrayList<>();
            for (Object o : (List<?>) archive.getOrDefault("events", List.of())) {
                events.add((Map<String, Object>) o);
            }
            events.sort((x, y) -> {
                int bx = ((String) x.get("branch")).compareTo((String) y.get("branch"));
                if (bx != 0) {
                    return bx;
                }
                return Integer.compare(((Number) x.get("seq")).intValue(), ((Number) y.get("seq")).intValue());
            });

            for (Map<String, Object> em : events) {
                String branch = (String) em.get("branch");
                int seq = ((Number) em.get("seq")).intValue();
                if (db.findEvent((String) em.get("id")) != null) {
                    continue;
                }
                List<String> sources = new ArrayList<>();
                for (Object so : (List<?>) em.getOrDefault("sources", List.of())) {
                    sources.add((String) so);
                }
                Event e = new Event((String) em.get("id"), Integer.toString(seq), branch,
                        (String) em.get("type"), (String) em.get("parentId"),
                        (String) em.get("createdAt"), (String) em.get("actor"),
                        Json.write(em.get("payload")), (String) em.get("fingerprint"),
                        Json.write(sources), (String) em.get("directSourceId"),
                        (String) em.get("revisionOfId"));
                db.insertEvent(e, seq - 1);
            }

            for (Object o : branches) {
                Map<String, Object> b = (Map<String, Object>) o;
                db.setHead((String) b.get("name"), ((Number) b.get("headSeq")).intValue(),
                        (String) b.get("headEventId"));
            }
        });
    }

    private String verifySha(String id, byte[] data, String expected) {
        String actual = pol.domain.Ids.sha256(data);
        if (expected != null && !expected.equals(actual)) {
            throw new IllegalArgumentException("sha256 mismatch for blob " + id);
        }
        return actual;
    }
}
