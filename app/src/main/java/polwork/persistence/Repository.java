package polwork.persistence;

import polwork.domain.*;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single write-path repository. Every mutation runs in one transaction: a
 * failed import rolls the whole batch back, so no half-record survives.
 *
 * Append events only. Reinterpretations open a new branch. A commit carries
 * the head the actor saw ({@code baseHead}); a stale commit is rejected with
 * VERSION_CONFLICT and the diff of the two sides.
 */
public final class Repository implements AutoCloseable {

    public static final class ConflictException extends RuntimeException {
        public final String currentHeadUid;

        public ConflictException(String currentHeadUid, String message) {
            super(message);
            this.currentHeadUid = currentHeadUid;
        }
    }

    private final Connection cx;

    public Repository(Connection cx) {
        this.cx = cx;
    }

    public static Repository open(String path) {
        try {
            return new Repository(new Db(path).connect());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Connection connection() {
        return cx;
    }

    public void transaction(RunnableEx body) {
        try {
            cx.setAutoCommit(false);
            body.run();
            cx.commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } catch (Exception e) {
            rollback();
            throw new IllegalStateException(e);
        } finally {
            resetAutoCommit();
        }
    }

    public <T> T transaction(java.util.function.Supplier<T> body) {
        @SuppressWarnings("unchecked")
        Object[] out = new Object[1];
        transaction(() -> out[0] = body.get());
        return (T) out[0];
    }

    @FunctionalInterface
    public interface RunnableEx {
        void run() throws Exception;
    }

    private void rollback() {
        try {
            cx.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void resetAutoCommit() {
        try {
            cx.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    // ------------------------------------------------------------- blobs

    public void putBlob(ByteContent content) {
        try (var ps = cx.prepareStatement(
                "INSERT OR IGNORE INTO blobs(sha, media_type, bytes, received_at) VALUES (?,?,?,?)")) {
            ps.setString(1, content.sha256);
            ps.setString(2, content.mediaType);
            ps.setBytes(3, content.bytes);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<ByteContent> getBlob(String sha) {
        try (var ps = cx.prepareStatement("SELECT media_type, bytes FROM blobs WHERE sha=?")) {
            ps.setString(1, sha);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ByteContent(rs.getBytes(2), rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ----------------------------------------------------- source versions

    public void putSourceVersion(SourceVersion sv) {
        try (var ps = cx.prepareStatement("""
                INSERT INTO source_versions
                  (uid, kind, version_id, material_id, wavelength_id, blob_sha,
                   payload_json, convention_tag, imported_at, origin_label)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, sv.uid);
            ps.setString(2, sv.kind.code());
            ps.setString(3, sv.versionId);
            ps.setString(4, sv.materialId);
            ps.setString(5, sv.wavelengthId);
            ps.setString(6, sv.blobSha);
            ps.setString(7, sv.payloadJson);
            ps.setString(8, sv.conventionTag);
            ps.setString(9, sv.importedAt.toString());
            ps.setString(10, "import");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<SourceVersion> sourceVersions(String materialId) {
        List<SourceVersion> out = new ArrayList<>();
        try (var ps = cx.prepareStatement(
                "SELECT id,uid,kind,version_id,material_id,wavelength_id,blob_sha,payload_json,convention_tag,imported_at "
                        + "FROM source_versions WHERE material_id IS ? OR material_id=? ORDER BY id")) {
            ps.setString(1, materialId);
            ps.setString(2, materialId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSource(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public List<SourceVersion> allSourceVersions() {
        List<SourceVersion> out = new ArrayList<>();
        try (var st = cx.createStatement();
             var rs = st.executeQuery(
                     "SELECT id,uid,kind,version_id,material_id,wavelength_id,blob_sha,payload_json,convention_tag,imported_at "
                             + "FROM source_versions ORDER BY id")) {
            while (rs.next()) out.add(readSource(rs));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<SourceVersion> sourceByUid(String uid) {
        try (var ps = cx.prepareStatement(
                "SELECT id,uid,kind,version_id,material_id,wavelength_id,blob_sha,payload_json,convention_tag,imported_at "
                        + "FROM source_versions WHERE uid=?")) {
            ps.setString(1, uid);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSource(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private SourceVersion readSource(ResultSet rs) throws SQLException {
        return new SourceVersion(
                rs.getLong(1), rs.getString(2), SourceKind.fromCode(rs.getString(3)),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), Instant.parse(rs.getString(10)));
    }

    // ------------------------------------------------------------ branches

    public Branch createBranch(String uid, String name, String materialId) {
        try (var ps = cx.prepareStatement(
                "INSERT INTO branches(uid,name,material_id,head_event_uid,created_at) VALUES (?,?,?,?,?)")) {
            Instant now = Instant.now();
            ps.setString(1, uid);
            ps.setString(2, name);
            ps.setString(3, materialId);
            ps.setString(4, null);
            ps.setString(5, now.toString());
            ps.executeUpdate();
            return new Branch(0, uid, name, materialId, null, now);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Branch> branchByUid(String uid) {
        try (var ps = cx.prepareStatement(
                "SELECT id,uid,name,material_id,head_event_uid,created_at FROM branches WHERE uid=?")) {
            ps.setString(1, uid);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readBranch(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Branch> branches() {
        List<Branch> out = new ArrayList<>();
        try (var st = cx.createStatement();
             var rs = st.executeQuery(
                     "SELECT id,uid,name,material_id,head_event_uid,created_at FROM branches ORDER BY id")) {
            while (rs.next()) out.add(readBranch(rs));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private Branch readBranch(ResultSet rs) throws SQLException {
        String head = rs.getString(5);
        return new Branch(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.wasNull() ? null : head, Instant.parse(rs.getString(6)));
    }

    // -------------------------------------------------------------- events

    public long nextOrd(String branchUid) {
        try (var ps = cx.prepareStatement(
                "SELECT COALESCE(MAX(ord)+1,0) FROM events WHERE branch_uid=?")) {
            ps.setString(1, branchUid);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Append an event with optimistic concurrency. {@code baseHeadUid} is the
     * head the committer saw; null base is allowed only on an empty branch.
     */
    public Event appendEvent(String eventUid, String branchUid, String baseHeadUid,
                             String type, String payloadJson) {
        Branch branch = branchByUid(branchUid)
                .orElseThrow(() -> new IllegalArgumentException("unknown branch " + branchUid));
        if (baseHeadUid == null) {
            if (branch.headEventUid != null) {
                throw new ConflictException(branch.headEventUid,
                        "branch already has events; a base head is required");
            }
        } else if (!baseHeadUid.equals(branch.headEventUid)) {
            throw new ConflictException(branch.headEventUid,
                    "branch moved since your view: base=" + baseHeadUid
                            + ", current=" + branch.headEventUid);
        }
        long ord = nextOrd(branchUid);
        Instant now = Instant.now();
        try (var ps = cx.prepareStatement("""
                INSERT INTO events(ord,uid,branch_uid,parent_uid,base_head_uid,type,payload_json,at)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            ps.setLong(1, ord);
            ps.setString(2, eventUid);
            ps.setString(3, branchUid);
            ps.setString(4, branch.headEventUid);
            ps.setString(5, baseHeadUid);
            ps.setString(6, type);
            ps.setString(7, payloadJson);
            ps.setString(8, now.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        updateHead(branchUid, eventUid);
        return new Event(0, ord, eventUid, branchUid, branch.headEventUid,
                baseHeadUid, type, payloadJson, now);
    }

    public void updateHead(String branchUid, String eventUid) {
        try (var ps = cx.prepareStatement("UPDATE branches SET head_event_uid=? WHERE uid=?")) {
            ps.setString(1, eventUid);
            ps.setString(2, branchUid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Event> events(String branchUid) {
        List<Event> out = new ArrayList<>();
        try (var ps = cx.prepareStatement(
                "SELECT id,ord,uid,branch_uid,parent_uid,base_head_uid,type,payload_json,at,undone_by "
                        + "FROM events WHERE branch_uid=? ORDER BY ord")) {
            ps.setString(1, branchUid);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    Event e = new Event(rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), Instant.parse(rs.getString(9)));
                    out.add(e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public Optional<Event> eventByUid(String uid) {
        try (var ps = cx.prepareStatement(
                "SELECT id,ord,uid,branch_uid,parent_uid,base_head_uid,type,payload_json,at,undone_by "
                        + "FROM events WHERE uid=?")) {
            ps.setString(1, uid);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Event(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), Instant.parse(rs.getString(9))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------- derived output

    public void putDerivedResult(String branchUid, String fingerprint,
                                 String producedByEventUid, String ruleSet,
                                 String resultJson, String invalidationScope) {
        try (var ps = cx.prepareStatement("""
                INSERT INTO derived_results
                  (fingerprint, branch_uid, produced_by_event_uid, invalidated_by_event_uid,
                   rule_set_version, result_json, invalidation_scope)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, fingerprint);
            ps.setString(2, branchUid);
            ps.setString(3, producedByEventUid);
            ps.setString(4, null);
            ps.setString(5, ruleSet);
            ps.setString(6, resultJson);
            ps.setString(7, invalidationScope);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void invalidateDerivedForBranch(String branchUid, String causeEventUid,
                                           List<String> affectedFingerprints, String scope) {
        try (var ps = cx.prepareStatement(
                "UPDATE derived_results SET invalidated_by_event_uid=? "
                        + "WHERE branch_uid=? AND fingerprint=? AND invalidated_by_event_uid IS NULL")) {
            for (String fp : affectedFingerprints) {
                ps.setString(1, causeEventUid);
                ps.setString(2, branchUid);
                ps.setString(3, fp);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        try (var ps = cx.prepareStatement("""
                INSERT INTO invalidation_edges(cause_event_uid, affected_fingerprint, affected_kind, branch_uid)
                VALUES (?,?,?,?)
                """)) {
            for (String fp : affectedFingerprints) {
                ps.setString(1, causeEventUid);
                ps.setString(2, fp);
                ps.setString(3, scope);
                ps.setString(4, branchUid);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<DerivedRow> derivedResults(String branchUid) {
        List<DerivedRow> out = new ArrayList<>();
        try (var ps = cx.prepareStatement("""
                SELECT fingerprint, produced_by_event_uid, invalidated_by_event_uid,
                       rule_set_version, result_json, invalidation_scope
                FROM derived_results WHERE branch_uid=? ORDER BY id
                """)) {
            ps.setString(1, branchUid);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DerivedRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public record DerivedRow(String fingerprint, String producedByEventUid,
                             String invalidatedByEventUid, String ruleSet,
                             String resultJson, String scope) {}

    public List<String> invalidationCausesForFingerprint(String branchUid, String fingerprint) {
        List<String> out = new ArrayList<>();
        try (var ps = cx.prepareStatement(
                "SELECT cause_event_uid FROM invalidation_edges WHERE branch_uid=? AND affected_fingerprint=?")) {
            ps.setString(1, branchUid);
            ps.setString(2, fingerprint);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------------------------------------------------------- raw queries

    public int count(String table) {
        try (var st = cx.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            cx.close();
        } catch (SQLException ignored) {
        }
    }
}
