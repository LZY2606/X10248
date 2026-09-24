package org.polarworkbench.services;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.polarworkbench.math.Matrices;
import org.polarworkbench.math.PolarMath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorkbenchService {
    private final Database database;

    public WorkbenchService(Path databasePath) {
        this.database = new Database(databasePath);
    }

    public WorkbenchService(Database database) {
        this.database = database;
    }

    public Database database() {
        return database;
    }

    public boolean isEmpty() {
        return database.transaction(connection -> {
            try (ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM event")) {
                return rs.next() && rs.getInt(1) == 0;
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    public synchronized void resetDatabaseFile(Path path) {
        close();
        try {
            Files.deleteIfExists(path);
            for (String suffix : List.of("-journal", "-wal", "-shm")) {
                Files.deleteIfExists(Path.of(path + suffix));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot reset database", ex);
        }
    }

    public void close() {
        database.close();
    }

    public void installSample() {
        database.transactionVoid(connection -> {
            ensureNoEvents(connection);
            importInitialSources(connection, "baseline", "main", "Baseline Mueller measurement",
                    CasePack.baselineSources(), null);
            appendAnalysis(connection, "main");

            branchFromHead(connection, "singular-audit", "Rank-deficient design null space",
                    "singular-audit-created");
            replaceSource(connection, "singular-audit", "optical",
                    CasePack.opticalSource("singular", "v2", "I,Q,U,V", "PLUS_THETA", false));
            replaceSource(connection, "singular-audit", "intensity",
                    CasePack.intensitySource("singular", "v2", CasePack.BASELINE_M, 10,
                            "I,Q,U,V", "PLUS_THETA"));
            appendAnalysis(connection, "singular-audit");

            branchFromHead(connection, "near-singular-audit", "Near-singular but full rank design",
                    "near-singular-audit-created");
            replaceSource(connection, "near-singular-audit", "optical",
                    CasePack.opticalSource("near-singular", "v2", "I,Q,U,V", "PLUS_THETA", false));
            replaceSource(connection, "near-singular-audit", "intensity",
                    CasePack.intensitySource("near-singular", "v2", CasePack.BASELINE_M, 10,
                            "I,Q,U,V", "PLUS_THETA"));
            appendAnalysis(connection, "near-singular-audit");

            branchFromHead(connection, "stokes-order-audit", "Stokes component order re-interpretation",
                    "stokes-order-audit-created");
            replaceSource(connection, "stokes-order-audit", "optical",
                    CasePack.opticalSource("baseline", "v2", "I,Q,V,U", "PLUS_THETA", true));
            replaceSource(connection, "stokes-order-audit", "intensity",
                    CasePack.intensitySource("baseline", "v2", CasePack.BASELINE_M, 10,
                            "I,Q,V,U", "PLUS_THETA"));
            appendAnalysis(connection, "stokes-order-audit");

            branchFromHead(connection, "rotation-sign-audit", "Rotation sign convention audit",
                    "rotation-sign-audit-created");
            replaceSource(connection, "rotation-sign-audit", "optical",
                    CasePack.opticalSource("baseline", "v2", "I,Q,U,V", "MINUS_THETA", true));
            appendAnalysis(connection, "rotation-sign-audit");

            branchFromHead(connection, "dark-revision-audit", "Dark offset revision local dependency",
                    "dark-revision-audit-created");
            replaceSource(connection, "dark-revision-audit", "dark", CasePack.darkSource("v2", 14));
            appendAnalysis(connection, "dark-revision-audit");

            branchFromHead(connection, "unphysical-audit", "Noisy nonphysical Mueller candidate",
                    "unphysical-audit-created");
            replaceSource(connection, "unphysical-audit", "intensity",
                    CasePack.intensitySource("baseline", "v2", CasePack.UNPHYSICAL_M, 10,
                            "I,Q,U,V", "PLUS_THETA"));
            appendAnalysis(connection, "unphysical-audit");
            accept(connection, "unphysical-audit", "initial-adopted-unphysical-as-working-candidate",
                    "Adopted for comparison while retaining explicit Cloude PSD violation.",
                    "unphysical-solution", null);
            branchFromHead(connection, "unphysical-rejection-review",
                    "Conflicting human decision preserves rejected interpretation",
                    "unphysical-rejection-review-created", "unphysical-audit", headEventId(connection, "unphysical-audit"));
            reject(connection, "unphysical-rejection-review",
                    "rejected-nonprojected-unphysical-candidate",
                    "Reject because the unprojected candidate has a negative Cloude eigenvalue; it must not be projected and relabelled.",
                    "unphysical-solution", null);

            branchFromHead(connection, "reference-instrument-audit", "Reference and instrument matrix revision",
                    "reference-instrument-audit-created");
            replaceSource(connection, "reference-instrument-audit", "reference",
                    CasePack.referenceSource("v2", 0.98));
            replaceSource(connection, "reference-instrument-audit", "instrument",
                    CasePack.instrumentSource("v2", 1.02));
            appendAnalysis(connection, "reference-instrument-audit");

            branchFromHead(connection, "correlated-error-audit", "Correlated covariance error propagation",
                    "correlated-error-audit-created");
            replaceSource(connection, "correlated-error-audit", "covariance",
                    CasePack.covarianceSource("v2", true));
            appendAnalysis(connection, "correlated-error-audit");

            branchFromHead(connection, "wavelength-parameter-audit", "Wavelength parameter revision",
                    "wavelength-parameter-audit-created");
            replaceSource(connection, "wavelength-parameter-audit", "wavelength",
                    CasePack.wavelengthSource("v2", 633.0));
            appendAnalysis(connection, "wavelength-parameter-audit");
        });
    }

    private void ensureNoEvents(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM event");
             ResultSet rs = statement.executeQuery()) {
            if (rs.next() && rs.getInt(1) != 0) {
                throw new IllegalArgumentException("Sample installation is allowed only on an empty local database");
            }
        }
    }

    private void importInitialSources(Connection connection, String branch, String branchName, String purpose,
                                      Map<String, Map<String, Object>> sources, String parentBranch) throws SQLException {
        createBranchRow(connection, branch, branchName, purpose, parentBranch, null);
        String parent = null;
        for (String key : List.of("intensity", "optical", "dark", "reference", "instrument", "wavelength", "covariance")) {
            parent = appendSourceEvent(connection, branch, parent, sources.get(key));
        }
        updateBranchCreated(connection, branch, firstEventId(connection, branch));
    }

    private void createBranchRow(Connection connection, String branch, String name, String purpose,
                                 String parentBranch, String baseEvent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO branch(branch_id,name,parent_branch_id,base_event_id,purpose,created_event_id) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, branch);
            statement.setString(2, name);
            statement.setString(3, parentBranch);
            statement.setString(4, baseEvent);
            statement.setString(5, purpose);
            statement.setString(6, "pending-" + branch);
            statement.executeUpdate();
        }
    }

    private void updateBranchCreated(Connection connection, String branch, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE branch SET created_event_id=? WHERE branch_id=?")) {
            statement.setString(1, eventId);
            statement.setString(2, branch);
            statement.executeUpdate();
        }
    }

    private void branchFromHead(Connection connection, String branch, String purpose, String createdEventId) throws SQLException {
        branchFromHead(connection, branch, purpose, createdEventId, "main", headEventId(connection, "main"));
    }

    private void branchFromHead(Connection connection, String branch, String purpose, String createdEventId,
                                String parentBranch, String baseEvent) throws SQLException {
        createBranchRow(connection, branch, branch.replace('-', ' '), purpose, parentBranch, baseEvent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("branchId", branch);
        payload.put("parentBranchId", parentBranch);
        payload.put("baseEventId", baseEvent);
        payload.put("purpose", purpose);
        appendEvent(connection, branch, "BRANCH_CREATED", createdEventId, payload, null, parentBranch, baseEvent);
        String eventId = eventId(branch, createdEventId);
        updateBranchCreated(connection, branch, eventId);
    }

    private String appendSourceEvent(Connection connection, String branch, String parent,
                                     Map<String, Object> source) throws SQLException {
        String key = (String) source.get("sourceKey");
        String version = (String) source.get("version");
        String eventKey = "source-" + key + "-" + version;
        Map<String, Object> payload = new LinkedHashMap<>(source);
        payload.put("interpretation", "source_versions_are_immutable_raw_and_normalized_separated");
        appendEvent(connection, branch, "SOURCE_REVISION", eventKey, payload, parent, null, null);
        return eventId(branch, eventKey);
    }

    private EventRow appendEvent(Connection connection, String branch, String type, String stableKey,
                                 Object payload, String explicitParent, String parentBranch,
                                 String baseEvent) throws SQLException {
        String parent = explicitParent != null ? explicitParent : headEventId(connection, branch);
        int sequence = nextSequence(connection, branch);
        String id = eventId(branch, stableKey);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO event(event_id,branch_id,seq,parent_event_id,type,payload_json,created_at) VALUES(?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, branch);
            statement.setInt(3, sequence);
            statement.setString(4, parent);
            statement.setString(5, type);
            statement.setString(6, Json.write(payload));
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        }
        if (type.equals("SOURCE_REVISION")) {
            insertSourceVersion(connection, branch, id, payload);
        }
        return new EventRow(id, branch, sequence, parent, type, payload);
    }

    private int nextSequence(Connection connection, String branch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(seq),0)+1 FROM event WHERE branch_id=?")) {
            statement.setString(1, branch);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    private String eventId(String branch, String stableKey) {
        return "evt-" + Ids.stable(branch, stableKey);
    }

    private String firstEventId(Connection connection, String branch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM event WHERE branch_id=? ORDER BY seq LIMIT 1")) {
            statement.setString(1, branch);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String headEventId(Connection connection, String branch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM event WHERE branch_id=? ORDER BY seq DESC LIMIT 1")) {
            statement.setString(1, branch);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void insertSourceVersion(Connection connection, String branch, String eventId,
                                     Object rawPayload) throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        String key = (String) payload.get("sourceKey");
        String version = (String) payload.get("version");
        Object raw = payload.get("raw");
        Map<String, Object> normalized = normalizeSource(key, raw);
        String fingerprint = Ids.stable(Json.write(normalized), String.valueOf(payload.get("unitOrBasis")));
        String versionId = "srcver-" + Ids.stable(branch, key, version, fingerprint);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO source_version(source_key,version_id,source_type,event_id,branch_id,raw_json,normalized_json,unit_or_basis,fingerprint) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, key);
            statement.setString(2, versionId);
            statement.setString(3, (String) payload.get("sourceType"));
            statement.setString(4, eventId);
            statement.setString(5, branch);
            statement.setString(6, Json.write(raw));
            statement.setString(7, Json.write(normalized));
            statement.setString(8, String.valueOf(payload.get("unitOrBasis")));
            statement.setString(9, fingerprint);
            statement.executeUpdate();
        }
    }

    private Map<String, Object> normalizeSource(String key, Object rawObject) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) rawObject;
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("sourceKey", key);
        normalized.put("unitOrBasis", raw.get("unit") != null ? raw.get("unit") : raw.get("unitOrBasis"));
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        switch (key) {
            case "intensity" -> {
                normalized.put("values", raw.get("valuesCount"));
                normalized.put("layout", raw.get("layout"));
                normalized.put("generatorStateIds", raw.get("generatorStateIds"));
                normalized.put("analyzerStateIds", raw.get("analyzerStateIds"));
                normalized.put("numericUnit", "count");
                addTextDiagnostic(diagnostics, "integrationTimeText", raw.get("integrationTimeText"));
                addTextDiagnostic(diagnostics, "cameraThermalNote", raw.get("cameraThermalNote"));
            }
            case "optical" -> {
                normalized.put("states", raw.get("states"));
                normalized.put("stokesOrder", raw.get("stokesOrder"));
                normalized.put("rotationSignConvention", raw.get("rotationSignConvention"));
                normalized.put("coordinateBasis", "laboratory_lab_frame");
            }
            case "dark" -> {
                normalized.put("offset", raw.get("valuesCount"));
                normalized.put("numericUnit", "count");
            }
            case "reference" -> {
                normalized.put("transmittance", raw.get("transmittance"));
                normalized.put("numericUnit", "dimensionless");
            }
            case "instrument" -> {
                normalized.put("matrix", raw.get("measuredInstrumentMatrix"));
                normalized.put("numericUnit", "dimensionless");
            }
            case "wavelength" -> {
                normalized.put("wavelengthNm", raw.get("wavelengthNm"));
                normalized.put("numericUnit", "nm");
                addTextDiagnostic(diagnostics, "wavelengthText", raw.get("wavelengthText"));
            }
            case "covariance" -> {
                normalized.put("matrix", raw.get("measurementCovariance"));
                normalized.put("numericUnit", "count^2");
            }
            default -> diagnostics.add(Map.of("field", key, "status", "UNCONVERTIBLE_VALUE_RETAINED_AT_ORIGIN"));
        }
        normalized.put("diagnostics", diagnostics);
        return normalized;
    }

    private void addTextDiagnostic(List<Map<String, Object>> diagnostics, String field, Object value) {
        if (value != null) {
            diagnostics.add(Map.of("field", field, "value", value,
                    "status", "UNCONVERTIBLE_NUMERIC_VALUE_RETAINED_IN_RAW_LAYER"));
        }
    }

    private void replaceSource(Connection connection, String branch, String key,
                               Map<String, Object> source) throws SQLException {
        Map<String, Object> previous = latestSource(connection, branch, key);
        String parent = appendSourceEvent(connection, branch, null, source);
        Map<String, Object> invalidation = new LinkedHashMap<>();
        invalidation.put("sourceKey", key);
        invalidation.put("previousVersionFingerprint", previous == null ? null : previous.get("fingerprint"));
        invalidation.put("changedVersionEventId", parent);
        invalidation.put("scopePolicy", "dependency_graph_local_invalidation_no_global_silent_replace");
        appendEvent(connection, branch, "DEPENDENCY_INVALIDATED", "invalidate-" + key + "-" + source.get("version"),
                invalidation, parent, null, null);
    }

    private Map<String, Object> latestSource(Connection connection, String branch, String key) throws SQLException {
        String sql = """
                SELECT sv.* FROM source_version sv
                JOIN branch b ON b.branch_id=sv.branch_id
                WHERE sv.source_key=? AND (
                  sv.branch_id=?
                  OR (b.parent_branch_id IS NOT NULL AND sv.branch_id=b.parent_branch_id
                      AND CAST(SUBSTR(sv.event_id,5) AS INTEGER) >= 0)
                )
                ORDER BY sv.branch_id=? DESC, sv.event_id DESC LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, branch);
            statement.setString(3, branch);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? sourceRow(rs) : null;
            }
        }
    }

    private Map<String, Object> sourceRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceKey", rs.getString("source_key"));
        map.put("versionId", rs.getString("version_id"));
        map.put("eventId", rs.getString("event_id"));
        map.put("branchId", rs.getString("branch_id"));
        map.put("raw", Json.read(rs.getString("raw_json"), new TypeReference<Map<String, Object>>() {
        }));
        map.put("normalized", Json.read(rs.getString("normalized_json"), new TypeReference<Map<String, Object>>() {
        }));
        map.put("unitOrBasis", rs.getString("unit_or_basis"));
        map.put("fingerprint", rs.getString("fingerprint"));
        return map;
    }

    private void appendAnalysis(Connection connection, String branch) throws SQLException {
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        for (String key : List.of("intensity", "optical", "dark", "reference", "instrument", "wavelength", "covariance")) {
            Map<String, Object> source = latestSource(connection, branch, key);
            if (source == null) {
                throw new IllegalStateException("Missing separated source version: " + key + " for " + branch);
            }
            sources.put(key, source);
        }
        Map<String, Object> analysis = computeAnalysis(branch, sources);
        @SuppressWarnings("unchecked")
        String fingerprint = (String) analysis.get("fingerprint");
        String artifactId = "art-" + Ids.stable(branch, fingerprint);
        String stableKey = "analysis-" + fingerprint.substring(0, 16);
        EventRow event = appendEvent(connection, branch, "ANALYSIS_COMPUTED", stableKey,
                analysisSummary(analysis, artifactId), null, null, null);
        insertArtifact(connection, branch, event, artifactId, analysis, fingerprint, sources);
    }

    private Map<String, Object> analysisSummary(Map<String, Object> analysis, String artifactId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("artifactId", artifactId);
        summary.put("fingerprint", analysis.get("fingerprint"));
        summary.put("rank", analysis.get("rank"));
        summary.put("conditionNumber", analysis.get("conditionNumber"));
        summary.put("solverStatus", analysis.get("solverStatus"));
        summary.put("physicallyRealizable", analysis.get("physicallyRealizable"));
        summary.put("minimumCloudeEigenvalue", analysis.get("minimumCloudeEigenvalue"));
        return summary;
    }

    private Map<String, Object> computeAnalysis(String branch, Map<String, Map<String, Object>> sources) {
        Map<String, Object> intensityNorm = normalized(sources.get("intensity"));
        Map<String, Object> opticalNorm = normalized(sources.get("optical"));
        Map<String, Object> darkNorm = normalized(sources.get("dark"));
        Map<String, Object> referenceNorm = normalized(sources.get("reference"));
        Map<String, Object> instrumentNorm = normalized(sources.get("instrument"));
        Map<String, Object> wavelengthNorm = normalized(sources.get("wavelength"));
        Map<String, Object> covarianceNorm = normalized(sources.get("covariance"));
        String stokesOrder = String.valueOf(opticalNorm.get("stokesOrder"));
        String rotationSign = String.valueOf(opticalNorm.get("rotationSignConvention"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opticalStates = (List<Map<String, Object>>) opticalNorm.get("states");
        List<String> generatorIds = stringList(intensityNorm.get("generatorStateIds"));
        List<String> analyzerIds = stringList(intensityNorm.get("analyzerStateIds"));
        List<double[]> generators = orderedStokes(opticalStates, generatorIds, rotationSign, stokesOrder);
        List<double[]> analyzers = orderedStokes(opticalStates, analyzerIds, rotationSign, stokesOrder);
        RealMatrix design = PolarMath.designMatrix(generators, analyzers);

        List<Double> rawCounts = doubleList(intensityNorm.get("values"));
        double dark = ((Number) darkNorm.get("offset")).doubleValue();
        double incidentScale = ((Number) rawMap(sources.get("intensity")).get("incidentScaleCounts")).doubleValue();
        double[] response = new double[16];
        for (int index = 0; index < 16; index++) {
            response[index] = (rawCounts.get(index) - dark) / incidentScale;
        }

        double[][] covarianceCounts = matrix(covarianceNorm.get("matrix"), 16, 16);
        RealMatrix covariance = new Array2DRowRealMatrix(covarianceCounts).scalarMultiply(1.0 / (incidentScale * incidentScale));
        Matrices.LeastSquaresResult fit = Matrices.weightedLeastSquares(design, response, covariance);
        RealMatrix rawFit = Matrices.unflatten(fit.solution(), 4, 4);

        double[][] measuredInstrument = matrix(instrumentNorm.get("matrix"), 4, 4);
        RealMatrix inverseInstrument = MatrixUtils.inverse(new Array2DRowRealMatrix(measuredInstrument));
        double reference = ((Number) referenceNorm.get("transmittance")).doubleValue();
        RealMatrix corrected = PolarMath.scaleMueller(rawFit, inverseInstrument, reference);
        RealMatrix correctedCovariance = null;
        if (fit.covariance() != null) {
            RealMatrix correction = inverseInstrument.scalarMultiply(1.0 / reference);
            correctedCovariance = correction.multiply(Matrices.unflatten16(fit.covariance())).multiply(correction.transpose());
        }

        PolarMath.PhysicalReport physical = PolarMath.physicalReport(corrected);
        double[] predicted = design.operate(Matrices.flatten(corrected));
        double[] residuals = new double[16];
        double residualSumSquares = 0;
        for (int index = 0; index < 16; index++) {
            residuals[index] = response[index] - predicted[index];
            residualSumSquares += residuals[index] * residuals[index];
        }

        String fingerprint = Ids.stable(branch, stokesOrder, rotationSign,
                sources.get("intensity").get("fingerprint").toString(),
                sources.get("optical").get("fingerprint").toString(),
                sources.get("dark").get("fingerprint").toString(),
                sources.get("reference").get("fingerprint").toString(),
                sources.get("instrument").get("fingerprint").toString(),
                sources.get("wavelength").get("fingerprint").toString(),
                sources.get("covariance").get("fingerprint").toString(),
                "solver:svd-relative-1e-10-near-1e8");
        String status = fit.rankDeficient() ? "RANK_DEFICIENT_NULL_SPACE_RETAINED"
                : fit.nearSingular() ? "NEAR_SINGULAR_PSEUDOINVERSE_UNCERTAINTY_UNRELIABLE"
                : "FULL_RANK_WEIGHTED_LEAST_SQUARES";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactIdStem", "mueller-estimate");
        result.put("fingerprint", fingerprint);
        result.put("solverStatus", status);
        result.put("rank", fit.rank());
        result.put("nullity", 16 - fit.rank());
        result.put("conditionNumber", fit.condition());
        result.put("singularValues", fit.singularValues());
        result.put("effectiveSingularThreshold", fit.effectiveSingularThreshold());
        result.put("diagnostics", fit.diagnostics());
        result.put("stokesOrder", stokesOrder);
        result.put("rotationSignConvention", rotationSign);
        result.put("wavelength", displayValue("wavelength.nm", wavelengthNorm.get("wavelengthNm"), "nm",
                sources.get("wavelength").get("versionId").toString()));
        result.put("designMatrix", displayMatrix("design", Matrices.values(design), "dimensionless",
                sourceIds(sources, "optical", "intensity")));
        result.put("mueller", displayMatrix("mueller", Matrices.values(corrected), "dimensionless",
                sourceIds(sources, "intensity", "optical", "dark", "reference", "instrument", "covariance")));
        result.put("uncertainty", covarianceDisplay(correctedCovariance));
        result.put("physicalResiduals", physicalDisplay(physical));
        result.put("physicallyRealizable", physical.physicallyRealizable());
        result.put("minimumCloudeEigenvalue", physical.minimumEigenvalue());
        result.put("residuals", displayVector("fit.residual", residuals, "dimensionless",
                sourceIds(sources, "intensity", "covariance")));
        result.put("residualSumSquares", displayValue("fit.rss", residualSumSquares, "dimensionless^2",
                sourceIds(sources, "intensity", "covariance")));
        result.put("fitCandidates", fitCandidates(fit, corrected, correctedCovariance, physical, sources));
        result.put("decompositionCandidates", decompositionDisplay(corrected));
        result.put("sourceVersionIds", sourceVersionIdMap(sources));
        result.put("rawPreservation", Map.of("rawValuesRemainAtSource", true,
                "normalizationLayerOnly", true, "noIrreversibleImportCleaning", true));
        return result;
    }

    private void insertArtifact(Connection connection, String branch, EventRow event, String artifactId,
                                Map<String, Object> analysis, String fingerprint,
                                Map<String, Map<String, Object>> sources) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO artifact(artifact_id,branch_id,event_id,artifact_type,json,fingerprint,valid_until_event_id) VALUES(?,?,?,?,?,?,NULL)")) {
            statement.setString(1, artifactId);
            statement.setString(2, branch);
            statement.setString(3, event.id());
            statement.setString(4, "MUELLER_ESTIMATE");
            statement.setString(5, Json.write(analysis));
            statement.setString(6, fingerprint);
            statement.executeUpdate();
        }
        for (String key : List.of("intensity", "optical", "dark", "reference", "instrument", "wavelength", "covariance")) {
            Map<String, Object> source = sources.get(key);
            String edgeId = "edge-" + Ids.stable(artifactId, key, source.get("versionId").toString());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dependency_edge(dependency_edge_id,artifact_id,source_key,version_id,event_id,local_scope) VALUES(?,?,?,?,?,?)")) {
                statement.setString(1, edgeId);
                statement.setString(2, artifactId);
                statement.setString(3, key);
                statement.setString(4, source.get("versionId").toString());
                statement.setString(5, source.get("eventId").toString());
                statement.setString(6, dependencyScope(key));
                statement.executeUpdate();
            }
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fitCandidates = (List<Map<String, Object>>) analysis.get("fitCandidates");
        candidates.addAll(fitCandidates);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decompositionCandidates = (List<Map<String, Object>>) analysis.get("decompositionCandidates");
        candidates.addAll(decompositionCandidates);
        for (Map<String, Object> candidate : candidates) {
            String candidateId = "cand-" + Ids.stable(artifactId, candidate.get("stableId").toString());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO candidate(candidate_id,artifact_id,stable_key,kind,json) VALUES(?,?,?,?,?)")) {
                statement.setString(1, candidateId);
                statement.setString(2, artifactId);
                statement.setString(3, candidate.get("stableId").toString());
                statement.setString(4, candidate.get("kind").toString());
                statement.setString(5, Json.write(candidate));
                statement.executeUpdate();
            }
        }
    }

    private String dependencyScope(String key) {
        return switch (key) {
            case "dark", "intensity", "covariance" -> "local_response_error_chain";
            case "reference", "instrument" -> "calibration_chain";
            case "optical" -> "measurement_design_chain";
            case "wavelength" -> "parameter_identity_chain";
            default -> "global";
        };
    }

    private Map<String, Object> normalized(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) source.get("normalized");
        return map;
    }

    private Map<String, Object> rawMap(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) source.get("raw");
        return map;
    }

    private List<String> stringList(Object object) {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) object;
        return list;
    }

    private List<Double> doubleList(Object object) {
        @SuppressWarnings("unchecked")
        List<Number> list = (List<Number>) object;
        List<Double> values = new ArrayList<>();
        for (Number number : list) {
            values.add(number.doubleValue());
        }
        return values;
    }

    private double[][] matrix(Object object, int rows, int columns) {
        @SuppressWarnings("unchecked")
        List<List<Number>> list = (List<List<Number>>) object;
        double[][] matrix = new double[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                matrix[row][column] = list.get(row).get(column).doubleValue();
            }
        }
        return matrix;
    }

    private List<double[]> orderedStokes(List<Map<String, Object>> states, List<String> ids,
                                         String sign, String order) {
        Map<String, double[]> byId = new LinkedHashMap<>();
        for (Map<String, Object> state : states) {
            String id = String.valueOf(state.get("stateId"));
            String name = String.valueOf(state.get("name"));
            double angle = ((Number) state.get("angleDegrees")).doubleValue();
            byId.put(id, PolarMath.stokesFromRaw(name, angle, sign, order,
                    String.valueOf(state.get("angleUnit"))));
        }
        List<double[]> result = new ArrayList<>();
        for (String id : ids) {
            if (!byId.containsKey(id)) {
                throw new IllegalArgumentException("Optical state referenced by intensity layout is missing: " + id);
            }
            result.add(byId.get(id));
        }
        return result;
    }

    private String sourceIds(Map<String, Map<String, Object>> sources, String... keys) {
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            ids.add(String.valueOf(sources.get(key).get("versionId")));
        }
        return String.join(";", ids);
    }

    private Map<String, String> sourceVersionIdMap(Map<String, Map<String, Object>> sources) {
        Map<String, String> map = new LinkedHashMap<>();
        sources.forEach((key, value) -> map.put(key, String.valueOf(value.get("versionId"))));
        return map;
    }

    private Map<String, Object> displayValue(String id, Object value, String unit, String sourceIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stableValueId", id);
        map.put("value", value);
        map.put("unit", unit);
        map.put("sourceVersionIds", sourceIds);
        return map;
    }

    private Map<String, Object> displayMatrix(String id, double[][] matrix, String unit, String sourceIds) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int row = 0; row < matrix.length; row++) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (int column = 0; column < matrix[row].length; column++) {
                values.add(displayValue(id + "." + row + "." + column, matrix[row][column], unit, sourceIds));
            }
            rows.add(values);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stableValueId", id);
        map.put("rows", rows);
        map.put("unit", unit);
        map.put("sourceVersionIds", sourceIds);
        return map;
    }

    private Map<String, Object> displayVector(String id, double[] vector, String unit, String sourceIds) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < vector.length; index++) {
            values.add(displayValue(id + "." + index, vector[index], unit, sourceIds));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stableValueId", id);
        map.put("values", values);
        map.put("unit", unit);
        map.put("sourceVersionIds", sourceIds);
        return map;
    }

    private Map<String, Object> covarianceDisplay(RealMatrix covariance) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stableValueId", "mueller.covariance16");
        map.put("available", covariance != null);
        if (covariance != null) {
            map.put("matrix", displayMatrix("mueller.covariance16", covariance.getData(), "dimensionless^2",
                    "covariance;design;calibration"));
        } else {
            map.put("diagnostic", "uncertainty propagation withheld because the design is singular or near singular");
            List<Map<String, Object>> elements = new ArrayList<>();
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 4; column++) {
                    elements.add(Map.of("stableValueId", "mueller.error." + row + "." + column,
                            "value", null, "unit", "dimensionless",
                            "status", "NOT_UNIQUELY_DETERMINED_NO_FORCED_INVERSE"));
                }
            }
            map.put("elementErrors", elements);
        }
        return map;
    }

    private Map<String, Object> physicalDisplay(PolarMath.PhysicalReport physical) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stableValueId", "physical.residuals");
        map.put("physicallyRealizable", physical.physicallyRealizable());
        map.put("projectionPolicy", "violations_are_flagged_unprojected_candidate_is_not_relabelled");
        map.put("cloudeEigenvalues", displayVector("physical.cloude.eigenvalues", physical.eigenvalues(),
                "dimensionless", "mueller"));
        map.put("coherencyMatrix", displayMatrix("physical.cloude.coherency", complexMatrix(physical.coherency()),
                "dimensionless", "mueller"));
        map.put("passiveFirstColumnResiduals", displayVector("physical.passive.firstColumn",
                physical.passiveFirstColumnResiduals(), "dimensionless", "mueller"));
        map.put("normalizedDiattenuationResidual", displayValue("physical.normalized.residual",
                physical.normalizedResidual(), "dimensionless", "mueller"));
        map.put("minimumEigenvalue", displayValue("physical.cloude.minEigenvalue",
                physical.minimumEigenvalue(), "dimensionless", "mueller"));
        return map;
    }

    private double[][] complexMatrix(org.apache.commons.math3.complex.Complex[][][] complex) {
        double[][] real = new double[complex.length][complex[0].length];
        for (int row = 0; row < complex.length; row++) {
            for (int column = 0; column < complex[0].length; column++) {
                org.apache.commons.math3.complex.Complex value = complex[row][column];
                real[row][column] = value.getReal() + (Math.abs(value.getImaginary()) < 1e-14 ? 0.0 : Double.NaN);
            }
        }
        return real;
    }

    private List<Map<String, Object>> fitCandidates(Matrices.LeastSquaresResult fit, RealMatrix corrected,
                                                    RealMatrix correctedCovariance,
                                                    PolarMath.PhysicalReport physical,
                                                    Map<String, Map<String, Object>> sources) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Object> leastSquares = new LinkedHashMap<>();
        leastSquares.put("stableId", "svd-minimum-norm");
        leastSquares.put("kind", "FIT_CANDIDATE");
        leastSquares.put("label", "SVD minimum-norm Mueller candidate");
        leastSquares.put("matrix", displayMatrix("candidate.svd-minimum-norm", corrected.getData(),
                "dimensionless", sourceIds(sources, "intensity", "optical", "dark", "reference", "instrument")));
        leastSquares.put("physicallyRealizable", physical.physicallyRealizable());
        leastSquares.put("minimumCloudeEigenvalue", physical.minimumEigenvalue());
        leastSquares.put("uncertaintyAvailable", correctedCovariance != null);
        candidates.add(leastSquares);

        if (!fit.rankDeficient()) {
            return candidates;
        }
        for (int index = 0; index < fit.nullBasis().length; index++) {
            double[] positive = fit.solution().clone();
            double[] negative = fit.solution().clone();
            double scale = 0.05;
            for (int j = 0; j < positive.length; j++) {
                positive[j] += scale * fit.nullBasis()[index][j];
                negative[j] -= scale * fit.nullBasis()[index][j];
            }
            RealMatrix positiveM = transformCandidate(positive, corrected, fit.solution());
            RealMatrix negativeM = transformCandidate(negative, corrected, fit.solution());
            for (String sign : new String[]{"plus", "minus"}) {
                RealMatrix candidateM = sign.equals("plus") ? positiveM : negativeM;
                PolarMath.PhysicalReport candidatePhysical = PolarMath.physicalReport(candidateM);
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("stableId", "nullspace-" + index + "-" + sign);
                candidate.put("kind", "LEGAL_NULLSPACE_CANDIDATE");
                candidate.put("label", "Retained affine null-space member v" + index + " " + sign);
                candidate.put("matrix", displayMatrix("candidate.nullspace-" + index + "-" + sign,
                        candidateM.getData(), "dimensionless", "rank-deficient-design"));
                candidate.put("nullBasisVectorIndex", index);
                candidate.put("affineFamily", "m_solution + null(design) coefficients are non-unique");
                candidate.put("physicallyRealizable", candidatePhysical.physicallyRealizable());
                candidate.put("minimumCloudeEigenvalue", candidatePhysical.minimumEigenvalue());
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private RealMatrix transformCandidate(double[] vector, RealMatrix correctedReference, double[] solutionVector) {
        RealMatrix rawCandidate = Matrices.unflatten(vector, 4, 4);
        RealMatrix rawReference = Matrices.unflatten(solutionVector, 4, 4);
        return correctedReference.add(rawCandidate.subtract(rawReference));
    }

    private List<Map<String, Object>> decompositionDisplay(RealMatrix mueller) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (PolarMath.DecompositionCandidate candidate : PolarMath.decompositions(mueller)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stableId", candidate.stableId());
            map.put("kind", "DECOMPOSITION_CANDIDATE");
            map.put("label", candidate.formula());
            map.put("labels", candidate.labels());
            map.put("matrix", displayMatrix("decomposition." + candidate.stableId(), candidate.matrix(),
                    "dimensionless", "mueller"));
            PolarMath.PhysicalReport report = PolarMath.physicalReport(
                    new Array2DRowRealMatrix(candidate.matrix()));
            map.put("physicallyRealizable", report.physicallyRealizable());
            map.put("minimumCloudeEigenvalue", report.minimumEigenvalue());
            candidates.add(map);
        }
        return candidates;
    }
