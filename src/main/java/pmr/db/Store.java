package pmr.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thin JDBC data access layer for the polarization review workspace. */
public final class Store implements AutoCloseable {

    private final Connection c;

    public Store(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.c = DriverManager.getConnection("jdbc:sqlite:" + path);
            this.c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            int v = Schema.version(c);
            if (v == 0) {
                Schema.fresh(c);
                c.commit();
            } else if (v != Schema.CURRENT_VERSION) {
                throw new SQLException("unsupported schema version " + v);
            }
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    private Store(Connection c) {
        this.c = c;
    }

    /** Opens an existing database without auto-migrating (used by migration tests). */
    public static Store raw(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(false);
            return new Store(conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Connection connection() {
        return c;
    }

    public void commit() {
        try {
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollbackOnly() {
        try {
            c.rollback();
        } catch (SQLException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            c.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- blobs ----------------

    /** Returns sha256, inserting the raw bytes at most once. */
    public String putBlob(byte[] bytes, String mediaType) {
        String sha = pmr.util.Hashing.sha256Hex(bytes);
        if (!getBlob(sha).isPresent()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO blob(sha256, bytes, media_type, size) VALUES(?,?,?,?)")) {
                ps.setString(1, sha);
                ps.setBytes(2, bytes);
                ps.setString(3, mediaType);
                ps.setLong(4, bytes.length);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return sha;
    }

    public Optional<byte[]> getBlob(String sha) {
        try (PreparedStatement ps = c.prepareStatement("SELECT bytes FROM blob WHERE sha256 = ?")) {
            ps.setString(1, sha);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- material ----------------

    public void insertMaterial(String id, String name, String description, String createdAt) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO material(id,name,description,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setString(4, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listMaterials() {
        return query("SELECT id,name,description,created_at FROM material ORDER BY id");
    }

    public Optional<Map<String, Object>> getMaterial(String id) {
        return queryOne(
                "SELECT id,name,description,created_at FROM material WHERE id = ?", id);
    }

    // ---------------- artifacts ----------------

    public void insertArtifact(ArtifactRow a) {
        try (PreparedStatement ps = c.prepareStatement(
                """
                INSERT INTO artifact(id,material_id,slot,version,kind,unit,coordinate_basis,
                    representation,raw_text,blob_sha,content,artifact_order,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, a.id);
            ps.setString(2, a.materialId);
            ps.setString(3, a.slot);
            ps.setInt(4, a.version);
            ps.setString(5, a.kind);
            ps.setString(6, a.unit);
            ps.setString(7, a.coordinateBasis);
            ps.setString(8, a.representation);
            ps.setString(9, a.rawText);
            ps.setString(10, a.blobSha);
            ps.setString(11, a.content);
            ps.setInt(12, a.order);
            ps.setString(13, a.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listArtifacts(String materialId) {
        return query(
                """
                SELECT id,material_id,slot,version,kind,unit,coordinate_basis,representation,
                       raw_text,blob_sha,content,artifact_order,created_at
                FROM artifact WHERE material_id = ?
                ORDER BY artifact_order, slot, version
                """, materialId);
    }

    public Optional<Map<String, Object>> getArtifact(String materialId, String slot, int version) {
        return queryOne(
                """
                SELECT id,material_id,slot,version,kind,unit,coordinate_basis,representation,
                       raw_text,blob_sha,content,artifact_order,created_at
                FROM artifact WHERE material_id = ? AND slot = ? AND version = ?
                """, materialId, slot, version);
    }

    public int maxArtifactVersion(String materialId, String slot) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(version),0) FROM artifact WHERE material_id=? AND slot=?")) {
            ps.setString(1, materialId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- branches ----------------

    public void insertBranch(BranchRow b) {
        try (PreparedStatement ps = c.prepareStatement(
                """
                INSERT INTO branch(id,material_id,label,purpose,parent_branch_id,
                    forked_from_event_id,head_event_id,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, b.id);
            ps.setString(2, b.materialId);
            ps.setString(3, b.label);
            ps.setString(4, b.purpose);
            ps.setString(5, b.parentBranchId);
            ps.setString(6, b.forkedFromEventId);
            ps.setString(7, b.headEventId);
            ps.setString(8, b.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateBranchHead(String branchId, String headEventId) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE branch SET head_event_id=? WHERE id=?")) {
            ps.setString(1, headEventId);
            ps.setString(2, branchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Map<String, Object>> getBranch(String id) {
        return queryOne(
                """
                SELECT id,material_id,label,purpose,parent_branch_id,forked_from_event_id,
                       head_event_id,created_at
                FROM branch WHERE id = ?
                """, id);
    }

    public List<Map<String, Object>> listBranches(String materialId) {
        return query(
                """
                SELECT id,material_id,label,purpose,parent_branch_id,forked_from_event_id,
                       head_event_id,created_at
                FROM branch WHERE material_id = ? ORDER BY created_at, id
                """, materialId);
    }

    // ---------------- rule versions ----------------

    public void insertRuleVersion(RuleRow r) {
        try (PreparedStatement ps = c.prepareStatement(
                """
                INSERT INTO rule_version(id,key,version,fingerprint,spec,created_at)
                VALUES(?,?,?,?,?,?)
                """)) {
            ps.setString(1, r.id);
            ps.setString(2, r.key);
            ps.setInt(3, r.version);
            ps.setString(4, r.fingerprint);
            ps.setString(5, r.spec);
            ps.setString(6, r.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listRuleVersions() {
        return query("SELECT id,key,version,fingerprint,spec,created_at FROM rule_version ORDER BY key,version");
    }

    public Optional<Map<String, Object>> getRuleVersion(String key, int version) {
        return queryOne(
                "SELECT id,key,version,fingerprint,spec,created_at FROM rule_version WHERE key=? AND version=?",
                key, version);
    }

    // ---------------- events ----------------

    public int nextEventSeq(String branchId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(seq),-1)+1 FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void insertEvent(EventRow e) {
        try (PreparedStatement ps = c.prepareStatement(
                """
                INSERT INTO event(id,branch_id,seq,type,payload,basis_event_id,created_at)
                VALUES(?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, e.id);
            ps.setString(2, e.branchId);
            ps.setInt(3, e.seq);
            ps.setString(4, e.type);
            ps.setString(5, e.payload);
            ps.setString(6, e.basisEventId);
            ps.setString(7, e.createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listEvents(String branchId) {
        return query(
                """
                SELECT id,branch_id,seq,type,payload,basis_event_id,created_at
                FROM event WHERE branch_id=? ORDER BY seq
                """, branchId);
    }

    public Optional<Map<String, Object>> getEvent(String id) {
        return queryOne(
                """
                SELECT id,branch_id,seq,type,payload,basis_event_id,created_at
                FROM event WHERE id = ?
                """, id);
    }

    public int countEvents(String branchId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM event WHERE branch_id=?")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- diagnostics ----------------

    public void insertDiagnostic(DiagRow d) {
        try (PreparedStatement ps = c.prepareStatement(
                """
                INSERT INTO diagnostic(material_id,branch_id,scope,severity,code,message,context)
                VALUES(?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, d.materialId);
            ps.setString(2, d.branchId);
            ps.setString(3, d.scope);
            ps.setString(4, d.severity);
            ps.setString(5, d.code);
            ps.setString(6, d.message);
            ps.setString(7, d.context);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Map<String, Object>> listDiagnostics(String materialId) {
        return query(
                """
                SELECT id,material_id,branch_id,scope,severity,code,message,context
                FROM diagnostic WHERE material_id=? ORDER BY id
                """, materialId);
    }

    // ---------------- helpers ----------------

    private List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(row(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Map<String, Object>> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            m.put(md.getColumnLabel(i), rs.getObject(i));
        }
        return m;
    }

    // ---------------- row records ----------------

    public record ArtifactRow(String id, String materialId, String slot, int version, String kind,
                              String unit, String coordinateBasis, String representation,
                              String rawText, String blobSha, String content, int order,
                              String createdAt) {}

    public record BranchRow(String id, String materialId, String label, String purpose,
                            String parentBranchId, String forkedFromEventId, String headEventId,
                            String createdAt) {}

    public record RuleRow(String id, String key, int version, String fingerprint, String spec,
                          String createdAt) {}

    public record EventRow(String id, String branchId, int seq, String type, String payload,
                           String basisEventId, String createdAt) {}

    public record DiagRow(String materialId, String branchId, String scope, String severity,
                          String code, String message, String context) {}
}
