package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * High-level operations over {@link Db}.
 *
 * <p>All multi-row mutations run in ONE transaction: an import failure rolls back completely
 * (no half records), and content-addressed blob files written during a failed import are removed.
 */
public final class Store implements AutoCloseable {

    public static class ConflictException extends RuntimeException {
        public final ObjectNode diff;
        public final int serverRevision;
        ConflictException(ObjectNode diff, int serverRevision) {
            super("version conflict: branch moved since your edit base");
            this.diff = diff;
            this.serverRevision = serverRevision;
        }
    }

    public static final ObjectMapper OM = new ObjectMapper();

    private final Db db;
    private final Path blobRoot;

    public Store(Db db, Path blobRoot) {
        this.db = db;
        this.blobRoot = blobRoot;
    }

    public Db database() {
        return db;
    }

    public Path blobRoot() {
        return blobRoot;
    }

    // ----- blobs -----

    public String saveBlob(byte[] data, String mediaType) throws SQLException, IOException {
        String sha = Fingerprint.sha256Hex(data);
        Path p = blobRoot.resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4));
        Files.createDirectories(p);
        Path file = p.resolve(sha);
        boolean fresh = !Files.exists(file);
        if (fresh) {
            Files.write(file, data);
        }
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT OR IGNORE INTO blobs(sha256,path,bytes,media_type) VALUES(?,?,?,?)")) {
            ps.setString(1, sha);
            ps.setString(2, file.toString());
            ps.setLong(3, data.length);
            ps.setString(4, mediaType);
            ps.executeUpdate();
        }
        return sha;
    }

    public byte[] readBlob(String sha) throws IOException, SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement("SELECT path FROM blobs WHERE sha256=?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown blob " + sha);
                }
                return Files.readAllBytes(Path.of(rs.getString(1)));
            }
        }
    }

    // ----- sources (interpretation separated from raw origin) -----

    public record SourceRef(String sourceId, String kind, int version, String sha256, String diagnostic) {}

    /**
     * Registers a new versioned source. Each kind is stored separately; a re-interpretation creates
     * a new version (optionally linked with supersedes) and never overwrites the prior bytes.
     */
    public SourceRef addSource(String kind, String label, byte[] data, String mediaType,
                               String representation, String unitFrame, String diagnostic,
                               String supersedesId) throws SQLException, IOException {
        String sha = saveBlob(data, mediaType);
        int version;
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT COALESCE(MAX(version),0)+1 FROM sources WHERE kind=?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                version = rs.next() ? rs.getInt(1) : 1;
            }
        }
        String id = String.format("src-%s-v%d-%s", kind, version, sha.substring(0, 10));
        try (PreparedStatement ps = db.conn().prepareStatement("""
                INSERT INTO sources(source_id,kind,label,version,supersedes,sha256,representation,unit_frame,diagnostic)
                VALUES(?,?,?,?,?,?,?,?,?)""")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setString(3, label);
            ps.setInt(4, version);
            ps.setString(5, supersedesId);
            ps.setString(6, sha);
            ps.setString(7, representation);
            ps.setString(8, unitFrame);
            ps.setString(9, diagnostic);
            ps.executeUpdate();
        }
        return new SourceRef(id, kind, version, sha, diagnostic);
    }

    public List<SourceRef> listSources() throws SQLException {
        List<SourceRef> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT source_id,kind,version,sha256,diagnostic FROM sources ORDER BY kind,version");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SourceRef(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5)));
            }
        }
        return out;
    }

    public ObjectNode sourceRow(String id) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT source_id,kind,label,version,supersedes,sha256,representation,unit_frame,diagnostic,created_at FROM sources WHERE source_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ObjectNode n = OM.createObjectNode();
                n.put("sourceId", rs.getString(1));
                n.put("kind", rs.getString(2));
                n.put("label", rs.getString(3));
                n.put("version", rs.getInt(4));
                if (rs.getString(5) != null) {
                    n.put("supersedes", rs.getString(5));
                }
                n.put("sha256", rs.getString(6));
                n.put("representation", rs.getString(7));
                n.put("unitFrame", rs.getString(8));
                if (rs.getString(9) != null) {
                    n.put("diagnostic", rs.getString(9));
                }
                n.put("createdAt", rs.getString(10));
                return n;
            }
        }
    }

    // ----- branches -----

    public void ensureBranch(String branchId, String name, String parent) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "INSERT OR IGNORE INTO branches(branch_id,name,parent_branch) VALUES(?,?,?)")) {
            ps.setString(1, branchId);
            ps.setString(2, name);
            ps.setString(3, parent);
            ps.executeUpdate();
        }
    }

    /** Fork a branch replaying events up to (and including) atSeq. */
    public String fork(String parent, String newName, int atSeq) throws SQLException {
        String newId = "br-" + newName.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        ensureBranch(newId, newName, parent);
        int copied = 0;
        try (PreparedStatement q = db.conn().prepareStatement(
                "SELECT event_id,event_type,payload_json,fingerprint,rule_version,source_refs FROM events WHERE branch_id=? AND seq<=? ORDER BY seq");
             PreparedStatement in = db.conn().prepareStatement("""
                     INSERT INTO events(event_id,branch_id,seq,prev_event_id,event_type,payload_json,fingerprint,rule_version,source_refs)
                     VALUES(?,?,?,?,?,?,?,?,?)""")) {
            q.setString(1, parent);
            q.setInt(2, atSeq);
            try (ResultSet rs = q.executeQuery()) {
                String prev = null;
                while (rs.next()) {
                    copied++;
                    in.clearParameters();
                    // stable event identity carries the fork id but preserves parent lineage inside payload
                    String parentEventId = rs.getString(1);
                    String forkedEventId = parentEventId + "@" + newId;
                    in.setString(1, forkedEventId);
                    in.setString(2, newId);
                    in.setInt(3, copied);
                    in.setString(4, prev);
                    in.setString(5, rs.getString(2));
                    ObjectNode payload = (ObjectNode) OM.readTree(rs.getString(3));
                    payload.put("forkedFromEvent", parentEventId);
                    payload.put("forkedFromBranch", parent);
                    in.setString(6, OM.writeValueAsString(payload));
                    in.setString(7, rs.getString(4));
                    in.setString(8, rs.getString(5));
                    in.setString(9, rs.getString(6));
                    in.executeUpdate();
                    prev = forkedEventId;
                }
            }
        } catch (IOException e) {
            throw new SQLException(e);
        }
        try (PreparedStatement up = db.conn().prepareStatement(
                "UPDATE branches SET head_event=(SELECT event_id FROM events WHERE branch_id=? ORDER BY seq DESC LIMIT 1), revision=? WHERE branch_id=?")) {
            up.setString(1, newId);
            up.setInt(2, copied);
            up.setString(3, newId);
            up.executeUpdate();
        }
        return newId;
    }

    public record EventOut(String eventId, String branchId, int seq, String prevEventId, String type,
                           JsonNode payload, String fingerprint, String ruleVersion,
                           List<String> sourceRefs, List<String> invalidates, String createdAt) {}

    public List<EventOut> events(String branchId) throws SQLException {
        return events(branchId, Integer.MAX_VALUE);
    }

    public List<EventOut> events(String branchId, int upToSeq) throws SQLException {
        List<EventOut> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement("""
                SELECT event_id,branch_id,seq,prev_event_id,event_type,payload_json,fingerprint,
                       rule_version,source_refs,invalidates,created_at
                FROM events WHERE branch_id=? AND seq<=? ORDER BY seq""")) {
            ps.setString(1, branchId);
            ps.setInt(2, upToSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readEvent(rs));
                }
            }
        } catch (IOException e) {
            throw new SQLException(e);
        }
        return out;
    }

    private EventOut readEvent(ResultSet rs) throws SQLException, IOException {
        return new EventOut(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), OM.readTree(rs.getString(6)), rs.getString(7), rs.getString(8),
                jsonList(rs.getString(9)), jsonList(rs.getString(10)), rs.getString(11));
    }

    static List<String> jsonList(String s) {
        try {
            List<String> out = new ArrayList<>();
            JsonNode n = OM.readTree(s == null || s.isBlank() ? "[]" : s);
            n.forEach(x -> out.add(x.asText()));
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ObjectNode branchRow(String branchId) throws SQLException {
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT branch_id,name,parent_branch,head_event,revision,base_revision FROM branches WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ObjectNode n = OM.createObjectNode();
                n.put("branchId", rs.getString(1));
                n.put("name", rs.getString(2));
                if (rs.getString(3) != null) {
                    n.put("parentBranch", rs.getString(3));
                }
                if (rs.getString(4) != null) {
                    n.put("headEvent", rs.getString(4));
                }
                n.put("revision", rs.getInt(5));
                n.put("baseRevision", rs.getInt(6));
                return n;
            }
        }
    }

    public List<ObjectNode> listBranches() throws SQLException {
        List<ObjectNode> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn().prepareStatement(
                "SELECT branch_id FROM branches ORDER BY created_at,branch_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(branchRow(rs.getString(1)));
            }
        }
        return out;
    }

    public void commit() throws SQLException {
        db.commit();
    }

    public void rollback() {
        db.rollback();
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
