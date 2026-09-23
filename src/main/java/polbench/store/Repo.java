package polbench.store;

import polbench.model.Records.*;
import polbench.model.Enums.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Repo {
    private final Db db;
    public Repo(Db db) { this.db = db; }

    public Connection conn() { return db.conn; }

    // ---------- materials ----------
    public void ensureMaterial(String materialId, String name) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT OR IGNORE INTO materials(material_id, name, created_at) VALUES(?,?,?)")) {
            ps.setString(1, materialId);
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<String[]> listMaterials() {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT material_id, name FROM materials ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- sources ----------
    public void insertSource(Source s) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO sources(id,material_id,kind,version,supersedes,unit,coordinate_basis," +
                "raw_representation,canonical_json,sha256,seq,convertible) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, s.id());
            ps.setString(2, s.materialId());
            ps.setString(3, s.kind().name());
            ps.setString(4, s.version());
            ps.setString(5, s.supersedes());
            ps.setString(6, s.unit());
            ps.setString(7, s.coordinateBasis());
            ps.setString(8, s.rawRepresentation());
            ps.setString(9, s.canonicalJson());
            ps.setString(10, s.sha256());
            ps.setLong(11, s.seq());
            ps.setInt(12, s.convertible() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Source getSource(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM sources WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSource(rs) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Source> sourcesOfMaterial(String materialId) {
        List<Source> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM sources WHERE material_id=? ORDER BY seq")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSource(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Source> sourcesOfKind(String materialId, SourceKind kind) {
        List<Source> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM sources WHERE material_id=? AND kind=? ORDER BY seq")) {
            ps.setString(1, materialId);
            ps.setString(2, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSource(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private Source mapSource(ResultSet rs) throws SQLException {
        return new Source(
                rs.getString("id"), rs.getString("material_id"),
                SourceKind.valueOf(rs.getString("kind")),
                rs.getString("version"), rs.getString("supersedes"),
                rs.getString("unit"),
                hasColumn(rs, "coordinate_basis") ? rs.getString("coordinate_basis") : null,
                rs.getString("raw_representation"), rs.getString("canonical_json"),
                rs.getString("sha256"), rs.getLong("seq"),
                rs.getInt("convertible") == 1);
    }

    private static boolean hasColumn(ResultSet rs, String col) {
        try {
            rs.findColumn(col);
            return true;
        } catch (SQLException e) { return false; }
    }

    // ---------- rule versions ----------
    public void insertRule(RuleVersion r) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO rule_versions(id,rule_name,version,parameters_json,sha256,seq) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, r.id());
            ps.setString(2, r.ruleName());
            ps.setString(3, r.version());
            ps.setString(4, r.parametersJson());
            ps.setString(5, r.sha256());
            ps.setLong(6, r.seq());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public RuleVersion getRule(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM rule_versions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new RuleVersion(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<RuleVersion> allRules() {
        List<RuleVersion> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM rule_versions ORDER BY seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                out.add(new RuleVersion(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------- branches ----------
    public void insertBranch(Branch b) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO branches(id,material_id,name,parent_branch_id,fork_seq,status,head_seq,created_by) " +
                "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, b.id());
            ps.setString(2, b.materialId());
            ps.setString(3, b.name());
            ps.setString(4, b.parentBranchId());
            if (b.forkSeq() == null) ps.setNull(5, Types.INTEGER); else ps.setLong(5, b.forkSeq());
            ps.setString(6, b.status().name());
            ps.setLong(7, b.headSeq());
            ps.setString(8, b.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Branch getBranch(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM branches WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long fork = (Long) rs.getObject("fork_seq");
                return new Branch(rs.getString("id"), rs.getString("material_id"),
                        rs.getString("name"), rs.getString("parent_branch_id"),
                        fork, BranchStatus.valueOf(rs.getString("status")),
                        rs.getLong("head_seq"), rs.getString("created_by"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Branch> branchesOf(String materialId) {
        List<Branch> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM branches WHERE material_id=? ORDER BY fork_seq IS NULL DESC, fork_seq, id")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long fork = (Long) rs.getObject("fork_seq");
                    out.add(new Branch(rs.getString("id"), rs.getString("material_id"),
                            rs.getString("name"), rs.getString("parent_branch_id"),
                            fork, BranchStatus.valueOf(rs.getString("status")),
                            rs.getLong("head_seq"), rs.getString("created_by")));
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public void updateBranchHead(String branchId, long headSeq, BranchStatus status) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE branches SET head_seq=?, status=? WHERE id=?")) {
            ps.setLong(1, headSeq);
            ps.setString(2, status.name());
            ps.setString(3, branchId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------- events ----------
    /** Append event with optimistic version control. baseVersion must equal current head. */
    public long appendEvent(Event e) {
        Branch b = getBranch(e.branchId());
        if (b != null && e.baseVersion() > 0 && b.headSeq() > e.baseVersion()) {
            throw new ConflictException(e.baseVersion(), b.headSeq());
        }
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO events(material_id,branch_id,type,subject_id,summary,detail_json," +
                "parent_event_ref,client_event_id,base_version,client_clock,author) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.materialId());
            ps.setString(2, e.branchId());
            ps.setString(3, e.type().name());
            ps.setString(4, e.subjectId());
            ps.setString(5, e.summary());
            ps.setString(6, e.detailJson());
            ps.setString(7, e.parentEventRef());
            ps.setString(8, e.clientEventId());
            ps.setLong(9, e.baseVersion());
            try { ps.setLong(10, e.clientClock()); } catch (Exception ex) { ps.setLong(10, 0L); }
            ps.setString(11, e.author() == null ? "system" : e.author());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long seq = keys.getLong(1);
                if (b != null) updateBranchHead(b.id(), seq, b.status());
                return seq;
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    public List<Event> eventsOfBranch(String branchId) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM events WHERE branch_id=? ORDER BY seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapEvent(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Event> eventsOfMaterial(String materialId) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM events WHERE material_id=? ORDER BY seq")) {
            ps.setString(1, materialId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapEvent(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Event getEvent(long seq) {
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM events WHERE seq=?")) {
            ps.setLong(1, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapEvent(rs) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Event mapEvent(ResultSet rs) throws SQLException {
        return new Event(rs.getLong("seq"), rs.getString("material_id"), rs.getString("branch_id"),
                EventType.valueOf(rs.getString("type")), rs.getString("subject_id"),
                rs.getString("summary"), rs.getString("detail_json"),
                rs.getString("parent_event_ref"), rs.getString("client_event_id"),
                rs.getLong("base_version"),
                hasColumn(rs, "client_clock") ? rs.getLong("client_clock") : 0L,
                rs.getString("author"));
    }

    // ---------- artifacts ----------
    public void insertArtifact(Artifact a) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO artifacts(id,material_id,branch_id,kind,status,payload_json,fingerprint," +
                "rule_version_id,source_ids_json,parent_artifacts_json,event_seq,display_key) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, a.id());
            ps.setString(2, a.materialId());
            ps.setString(3, a.branchId());
            ps.setString(4, a.kind().name());
            ps.setString(5, a.status());
            ps.setString(6, a.payloadJson());
            ps.setString(7, a.fingerprint());
            ps.setString(8, a.ruleVersionId());
            ps.setString(9, a.sourceIds() == null ? "[]" : Json.arr(a.sourceIds()));
            ps.setString(10, a.parentArtifactIds() == null ? "[]" : Json.arr(a.parentArtifactIds()));
            ps.setLong(11, a.eventSeq());
            ps.setString(12, a.displayKey());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Artifact getArtifact(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT * FROM artifacts WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapArtifact(rs) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Artifact> artifactsOfBranch(String branchId) {
        List<Artifact> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM artifacts WHERE branch_id=? ORDER BY event_seq, kind")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapArtifact(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public List<Artifact> artifactsByKind(String branchId, ArtifactKind kind) {
        List<Artifact> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM artifacts WHERE branch_id=? AND kind=? ORDER BY event_seq")) {
            ps.setString(1, branchId);
            ps.setString(2, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapArtifact(rs));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Invalidation scope: artifacts directly or transitively depending on a source/rule. */
    public List<Artifact> dependentsOf(String materialId, List<String> sourceIds) {
        List<Artifact> all = new ArrayList<>();
        for (Branch b : branchesOf(materialId)) all.addAll(artifactsOfBranch(b.id()));
        java.util.Set<String> live = new java.util.HashSet<>(sourceIds);
        List<Artifact> affected = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Artifact a : all) {
                if (affected.contains(a)) continue;
                boolean hit = false;
                for (String s : a.sourceIds()) if (live.contains(s)) { hit = true; break; }
                if (!hit)
                    for (Artifact x : affected)
                        if (a.parentArtifactIds().contains(x.id())) { hit = true; break; }
                if (hit) { affected.add(a); live.add(a.id()); changed = true; }
            }
        }
        return affected;
    }

    private Artifact mapArtifact(ResultSet rs) throws SQLException {
        return new Artifact(rs.getString("id"), rs.getString("material_id"), rs.getString("branch_id"),
                ArtifactKind.valueOf(rs.getString("kind")), rs.getString("status"),
                rs.getString("payload_json"), rs.getString("fingerprint"),
                rs.getString("rule_version_id"),
                Json.strList(rs.getString("source_ids_json")),
                Json.strList(rs.getString("parent_artifacts_json")),
                rs.getLong("event_seq"),
                hasColumn(rs, "display_key") ? rs.getString("display_key") : null);
    }

    // ---------- diagnostics ----------
    public void insertDiagnostic(Diagnostic d) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO diagnostics(id,material_id,branch_id,source_id,severity,code,message,event_seq) " +
                "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.id());
            ps.setString(2, d.materialId());
            ps.setString(3, d.branchId());
            ps.setString(4, d.sourceId());
            ps.setString(5, d.severity());
            ps.setString(6, d.code());
            ps.setString(7, d.message());
            if (d.eventSeq() <= 0) ps.setNull(8, Types.INTEGER); else ps.setLong(8, d.eventSeq());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<Diagnostic> diagnosticsOfBranch(String branchId) {
        List<Artifact> ignored = List.of();
        List<Diagnostic> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT * FROM diagnostics WHERE branch_id=? ORDER BY event_seq")) {
            ps.setString(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Diagnostic(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getLong(8)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }
}
