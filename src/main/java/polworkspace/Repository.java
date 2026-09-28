package polworkspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Repository implements AutoCloseable {
    private final Connection connection;

    public Repository(Connection connection) { this.connection = connection; }

    public static Repository open(String path) { return new Repository(Database.open(path)); }

    @Override public void close() throws SQLException { connection.close(); }
    public Connection connection() { return connection; }

    public static class ConflictException extends RuntimeException {
        public final Json.Obj details;
        public ConflictException(Json.Obj details) { super("Branch revision conflict"); this.details = details; }
    }

    @Transactional
    public String importCasePackage(Json.Obj input) {
        String batchId = "batch-" + UUID.randomUUID();
        String caseId = input.getStr("case_id");
        try {
            String expected = input.getStr("schema");
            if (expected == null || !expected.startsWith("polarimetric-mueller-room-case/"))
                throw new IllegalArgumentException("unsupported case schema");
            Json.Arr sources = input.getArr("sources");
            Json.Arr branches = input.getArr("branches");
            if (sources == null || sources.isEmpty() || branches == null || branches.isEmpty())
                throw new IllegalArgumentException("case package requires sources and branches");
            for (Object sourceObject : sources) insertSource((Json.Obj) sourceObject, batchId, null);
            insertBatch(batchId, caseId, "COMMITTED", sources.size(), null, input);
            for (Object branchObject : branches) insertConfiguredBranch((Json.Obj) branchObject);
            return batchId;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private void insertBatch(String batchId, String caseId, String status, int count, String error, Json.Obj manifest) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO import_batches(id,case_id,status,source_count,error,raw_manifest_json) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, batchId);
            ps.setString(2, caseId);
            ps.setString(3, status);
            ps.setInt(4, count);
            ps.setString(5, error);
            ps.setString(6, Json.write(manifest));
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void insertSource(Json.Obj source, String batchId, String supersedes) {
        String id = required(source, "id");
        String type = required(source, "source_type");
        String version = required(source, "version");
        String role = required(source, "role");
        String canonical = Json.write(source);
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO sources(id,source_type,version,role,material_id,unit,coordinate_basis,representation,canonical_json,sha256,
              diagnostics_json,normalization_diagnostics_json,supersedes_source_id,import_batch_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO NOTHING
            """)) {
            int i = 1;
            ps.setString(i++, id); ps.setString(i++, type); ps.setString(i++, version); ps.setString(i++, role);
            ps.setString(i++, source.getStr("material_id") == null ? "sample-PM-001" : source.getStr("material_id"));
            ps.setString(i++, source.getStr("unit")); ps.setString(i++, source.getStr("coordinate_basis"));
            ps.setString(i++, "json"); ps.setString(i++, canonical); ps.setString(i++, sha256(canonical));
            ps.setString(i++, Json.write(source.getOrDefault("diagnostics", Json.arr())));
            ps.setString(i++, Json.write(source.getOrDefault("normalization_diagnostics", Json.arr())));
            ps.setString(i++, supersedes); ps.setString(i++, batchId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void insertConfiguredBranch(Json.Obj branch) {
        String key = required(branch, "branch_key");
        String parentKey = branch.getStr("parent_branch_key");
        String branchId = "branch-" + key;
        String parentId = parentKey == null || parentKey.isBlank() ? null : "branch-" + parentKey;
        insertBranch(branchId, key, branch.getStr("title"), parentId);
        Json.Arr refs = branch.getArr("source_refs");
        for (Object refObject : refs) {
            Json.Obj ref = (Json.Obj) refObject;
            addBranchSource(branchId, ref.getStr("source_id"));
        }
        Json.Obj eventPayload = Json.obj().str("reason", branch.getStr("event_type"))
                .obj("source_refs", refs)
                .str("machine_judgement", branch.getStr("machine_judgement"));
        String eventId = appendEvent(branchId, "SCENARIO_REPLAY", eventPayload, branch.getStr("machine_judgement"), null);
        RunResult run = createRun(branchId, eventId, branch.getStr("title"), branch.getStr("event_type"));
        applyAcceptedCandidate(branchId, run, "retained_minimum_norm_candidate");
    }

    private void insertBranch(String id, String key, String title, String parentId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO branches(id,branch_key,title,parent_branch_id) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
            ps.setString(1, id); ps.setString(2, key); ps.setString(3, title); ps.setString(4, parentId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private void addBranchSource(String branchId, String sourceId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO branch_sources(branch_id,source_id,source_type)
            SELECT ?,?,source_type FROM sources WHERE id=?
            ON CONFLICT(branch_id,source_id) DO NOTHING
            """)) {
            ps.setString(1, branchId); ps.setString(2, sourceId); ps.setString(3, sourceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public record RunResult(String runId, Json.Obj summary, Json.Arr candidates) {}

    @Transactional
    public RunResult createRun(String branchId, String eventId, String label, String reason) {
        Json.Obj assembled = assembleSources(branchId);
        Physics.Observation observation = Physics.buildObservation(assembled);
        Matrix design = Matrix.of(observation.design());
        Matrix.LeastSquaresResult fit = Matrix.truncatedLeastSquares(design, observation.corrected(), observation.covariance(), Physics.SINGULAR_THRESHOLD);
        String runId = "run-" + UUID.randomUUID();
        String fingerprint = fingerprint(runId, assembled, fit);
        double[][] mueller = vectorToMatrix(fit.solution());
        Physics.PhysicalReport physical = Physics.checkPhysical(mueller);
        Json.Obj physicalJson = physicalToJson(physical);
        Json.Arr diagnostics = Json.arr();
        for (String item : observation.diagnostics()) diagnostics.add(item);
        if (fit.underdetermined()) diagnostics.add("TRUNCATED_SUBSPACE_SOLUTION_RETAINED_NULLSPACE_NO_INVERSE");
        insertRun(runId, branchId, eventId, label, fit, assembled, mueller, physicalJson, diagnostics, fingerprint);
        insertRunSources(runId, branchId, reason, assembled);
        Json.Arr candidates = insertCandidates(runId, fit, mueller, physical);
        Json.Obj summary = runSummary(runId);
        return new RunResult(runId, summary, candidates);
    }

    private RunResult createRunForBranch(String branchId, String reason, String eventType, String judgement) {
        Json.Obj refs = branchSourceRefs(branchId);
        String eventId = appendEvent(branchId, eventType,
                Json.obj().str("reason", reason).obj("source_refs", refs).str("machine_judgement", judgement), judgement, null);
        RunResult result = createRun(branchId, eventId, reason, reason);
        applyAcceptedCandidate(branchId, result, "retained_minimum_norm_candidate");
        return result;
    }
