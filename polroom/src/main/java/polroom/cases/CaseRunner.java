package polroom.cases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import polroom.domain.Conventions;
import polroom.domain.Fingerprint;
import polroom.domain.Json;
import polroom.engine.Calibration;
import polroom.engine.Estimator;
import polroom.store.ConflictException;
import polroom.store.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports the bundled package and replays its scripted boundary scenarios.
 * Every replay step leaves input snippets, judgment status and affected
 * objects in the event chain.
 */
public final class CaseRunner {

    public record BranchRefs(String branchId, Map<String, Repository.SourceVersion> sources,
                             Map<String, String> derivationFingerprints) {
    }

    private final Repository repository;
    private String materialId;
    private final Map<String, String> branches = new HashMap<>();
    private final List<Map<String, Object>> replayLog = new ArrayList<>();

    public CaseRunner(Repository repository) {
        this.repository = repository;
    }

    public String materialId() {
        return materialId;
    }

    public String branchId(String name) {
        return branches.get(name);
    }

    public List<Map<String, Object>> replayLog() {
        return replayLog;
    }

    public String importBaseline(CasePackage pkg, String actor) {
        materialId = repository.createMaterial(pkg.manifest.path("title").asText("Mueller lab"),
                actor);
        String main = repository.createBranch(materialId, "main", null, null, actor);
        branches.put("main", main);
        long rev = repository.revision(main);

        JsonNode sources = pkg.sources();
        for (String kind : Repository.SOURCE_KINDS) {
            JsonNode node = sources.path(kind);
            Repository.SourceVersion source = repository.addSource(materialId, kind,
                    node.path("unit").asText(null),
                    node.path("coordinateBasis").asText(null),
                    node.toString(), node.deepCopy(), null, new ArrayList<>(), actor,
                    "baseline " + kind + " imported", main, rev);
            rev = repository.revision(main);
            Map<String, Object> imported = new LinkedHashMap<>();
            imported.put("op", "SOURCE_BASELINE");
            imported.put("kind", kind);
            imported.put("fingerprint", source.fingerprint());
            imported.put("version", source.version());
            replayLog.add(imported);
        }
        computeAndRecord(main, sources, "baseline full-rank measurement", actor,
                "SHV", "RIGHT_HANDED", 0.0, 0.0, 1.0, null);
        return materialId;
    }

    public Map<String, Object> replayStep(JsonNode step, String actor) {
        String op = step.path("op").asText();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("seq", step.path("seq").asInt());
        record.put("op", op);
        switch (op) {
            case "ADD_SCENARIO_SOURCES" -> addScenario(step, actor, record);
            case "REVISE_SOURCE" -> reviseSource(step, actor, record);
            case "REVISE_CONVENTION" -> reviseConvention(step, actor, record);
            case "CANDIDATE_REVIEW" -> candidateReview(step, actor, record);
            case "CONFLICTING_DECISIONS" -> conflictingDecisions(step, actor, record);
            default -> throw new IllegalStateException("unknown replay op " + op);
        }
        replayLog.add(record);
        return record;
    }

    private void addScenario(JsonNode step, String actor, Map<String, Object> record) {
        String scenarioName = step.path("scenario").asText();
        CasePackage pkg = CasePackage.loadBundled();
        JsonNode scenario = pkg.scenario(scenarioName);
        String branchName = step.path("branch").asText();
        String parentName = step.path("forkFrom").asText("main");
        String branch = forkBranch(branchName, parentName, actor);
        long rev = repository.revision(branch);

        List<Estimator.MeasurementSpec> specs = scenarioSpecs(scenario);
        ObjectNode rawPayload = Json.object();
        rawPayload.putArray("values").addAll(doubleArray(scenario.path("raw")));
        rawPayload.put("unit", "count");
        rawPayload.put("coordinateBasis", "scenario " + scenarioName);
        Repository.SourceVersion raw = repository.addSource(materialId, "raw_intensity",
                "count", "scenario " + scenarioName, rawPayload.toString(), rawPayload,
                null, List.of(), actor, "scenario raw " + scenarioName, branch, rev);
        rev = repository.revision(branch);

        ObjectNode darkPayload = Json.object();
        darkPayload.putArray("values").addAll(doubleArray(scenario.path("dark")));
        darkPayload.put("unit", "count");
        Repository.SourceVersion dark = repository.addSource(materialId, "dark_frame",
                "count", "scenario " + scenarioName, darkPayload.toString(), darkPayload,
                null, List.of(), actor, "scenario dark " + scenarioName, branch, rev);
        rev = repository.revision(branch);

        ObjectNode statePayload = Json.object();
        ArrayNode stateArray = statePayload.putArray("states");
        for (String roleName : new String[]{"states_psg", "states_psa"}) {
            String role = roleName.equals("states_psg") ? "PSG" : "PSA";
            for (JsonNode stateNode : scenario.path(roleName)) {
                ObjectNode state = stateArray.addObject();
                state.put("role", role);
                state.put("code", stateNode.path("code").asText());
                state.put("stokesOrder", "SHV");
                state.put("rotationConvention", "RIGHT_HANDED");
            }
        }
        Repository.SourceVersion states = repository.addSource(materialId, "optical_state",
                "normalized Stokes (dimensionless)", "scenario", statePayload.toString(),
                statePayload, null, List.of(), actor,
                "scenario states " + scenarioName, branch, rev);
        rev = repository.revision(branch);

        JsonNode baselineSources = pkg.sources();
        Estimator.Result estimation = runEstimate(specs, baselineSources,
                "SHV", "RIGHT_HANDED");
        record.put("status", estimation.status.name());
        record.put("rank", estimation.rank);
        record.put("condition", estimation.conditionNumber);
        record.put("candidateCount", estimation.candidates.size());

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("rawSourceId", raw.sourceId());
        scope.put("darkSourceId", dark.sourceId());
        scope.put("stateSourceId", states.sourceId());
        scope.put("scenario", scenarioName);
        recordDerivation(branch, estimation, List.of(raw.fingerprint(), dark.fingerprint(),
                states.fingerprint()), "SHV", "RIGHT_HANDED", scope, actor,
                "scenario estimation " + scenarioName, rev);
    }

    private String forkBranch(String branchName, String parentName, String actor) {
        String parent = branches.get(parentName);
        Repository.Event head = null;
        if (parent != null) {
            List<Repository.Event> events = repository.events(parent);
            head = events.get(events.size() - 1);
        }
        String branch = repository.createBranch(materialId, branchName, parent,
                head == null ? null : head.eventId(), actor);
        branches.put(branchName, branch);
        return branch;
    }

    private List<Estimator.MeasurementSpec> scenarioSpecs(JsonNode scenario) {
        List<double[]> psgs = expandCodes(scenario.path("states_psg"), Conventions.StokesOrder.SHV,
                Conventions.RotationConvention.RIGHT_HANDED);
        List<double[]> psas = expandCodes(scenario.path("states_psa"), Conventions.StokesOrder.SHV,
                Conventions.RotationConvention.RIGHT_HANDED);
        List<Estimator.MeasurementSpec> specs = new ArrayList<>();
        JsonNode raw = scenario.path("raw");
        JsonNode dark = scenario.path("dark");
        int k = 0;
        for (double[] psa : psas) {
            for (double[] psg : psgs) {
                Estimator.MeasurementSpec spec = new Estimator.MeasurementSpec();
                spec.psg = psg;
                spec.psa = psa;
                spec.intensity = raw.path(k).asDouble();
                spec.dark = dark.path(k).asDouble();
                spec.label = "k" + k;
                specs.add(spec);
                k++;
            }
        }
        return specs;
    }

    private List<double[]> expandCodes(JsonNode codes, Conventions.StokesOrder order,
                                       Conventions.RotationConvention rotation) {
        List<double[]> vectors = new ArrayList<>();
        for (JsonNode node : codes) {
            String code = node.path("code").asText();
            double angle = switch (code) {
                case "V" -> 90;
                case "P" -> 45;
                case "M" -> -45;
                default -> 0;
            };
            vectors.add(Estimator.decodeState(code, angle, 0, order, List.of("I", "Q", "U", "V"),
                    rotation));
        }
        return vectors;
    }

    private Estimator.Result runEstimate(List<Estimator.MeasurementSpec> specs,
                                         JsonNode baselineSources,
                                         String stokesOrder, String rotationConvention) {
        JsonNode covarianceNode = baselineSources.path("covariance").path("matrix");
        Array2DRowRealMatrix covariance = null;
        if (covarianceNode.isArray() && covarianceNode.size() == specs.size()) {
            covariance = new Array2DRowRealMatrix(readMatrix(covarianceNode));
        }
        double tol = CasePackage.loadBundled().manifest.path("svdTolerance").asDouble(1e-10);
        double nearCond = CasePackage.loadBundled().manifest
                .path("nearSingularCondition").asDouble(1e6);
        return Estimator.estimate(specs, covariance, tol, nearCond);
    }

    private void reviseSource(JsonNode step, String actor, Map<String, Object> record) {
        String kind = step.path("kind").asText();
        String scenarioName = step.path("scenario").asText();
        String branchName = step.path("branch").asText("main");
        String branch = branches.get(branchName);
        JsonNode scenario = CasePackage.loadBundled().scenario(scenarioName);
        long rev = repository.revision(branch);

        List<Repository.SourceVersion> prior = repository.sources(materialId).stream()
                .filter(s -> s.kind().equals(kind) && s.version() == 1).toList();
        if (prior.isEmpty()) {
            throw new IllegalStateException("no v1 " + kind + " to revise");
        }
        Repository.SourceVersion superseded = prior.get(0);

        JsonNode payload = scenario.deepCopy();
        String unit = scenario.path("unit").asText(superseded.unit());
        String coordinateBasis = "revised " + kind;
        Repository.SourceVersion revised = repository.addSource(materialId, kind,
                unit, coordinateBasis, payload.toString(), payload, superseded.sourceId(),
                List.of(), actor, kind + " revision (" + scenarioName + ")", branch, rev);
        rev = repository.revision(branch);

        invalidateDependingOn(branch, List.of(superseded.fingerprint()), actor, rev,
                kind + " revised", revised);
        record.put("newVersion", revised.version());
        record.put("supersedes", superseded.sourceId());
        record.put("fingerprint", revised.fingerprint());
    }

    private void reviseConvention(JsonNode step, String actor, Map<String, Object> record) {
        String branchName = step.path("branch").asText();
        String parentName = step.path("forkFrom").asText("main");
        if (!branches.containsKey(branchName)) {
            forkBranch(branchName, parentName, actor);
        }
        String branch = branches.get(branchName);
        String scenarioName = step.path("scenario").asText();
        JsonNode scenario = CasePackage.loadBundled().scenario(scenarioName);
        String order = scenario.path("order").asText("SHV");
        String rotation = scenario.path("convention").asText("RIGHT_HANDED");
        long rev = repository.revision(branch);

        ObjectNode conventionSource = Json.object();
        conventionSource.put("stokesOrder", order);
        conventionSource.put("rotationConvention", rotation);
        Repository.SourceVersion conventionVersion = repository.addSource(materialId,
                "optical_state", "normalized Stokes (dimensionless)",
                "convention revision " + order + "/" + rotation,
                conventionSource.toString(), conventionSource, null, List.of(),
                actor, "convention revision", branch, rev);
        rev = repository.revision(branch);

        JsonNode sources = CasePackage.loadBundled().sources();
        String summary = "convention " + order + "/" + rotation;
        computeAndRecord(branch, sources, summary, actor, order, rotation, 0.0, 0.0, 1.0,
                conventionVersion.fingerprint());
        record.put("stokesOrder", order);
        record.put("rotationConvention", rotation);
        record.put("fingerprint", conventionVersion.fingerprint());
    }

    private void candidateReview(JsonNode step, String actor, Map<String, Object> record) {
        String branch = branches.get(step.path("branch").asText("main"));
        String scenarioName = step.path("scenario").asText();
        JsonNode unphysical = CasePackage.loadBundled().scenario(scenarioName).path("matrix");
        long rev = repository.revision(branch);
        ObjectNode payload = Json.object();
        payload.put("matrix", unphysical);
        payload.put("note", "noisy candidate stored raw; violations flagged, never projected");
        Repository.SourceVersion source = repository.addSource(materialId, "raw_intensity",
                "dimensionless Mueller candidate", "candidate review", payload.toString(),
                payload, null, List.of(), actor, "unphysical candidate recorded", branch, rev);
        rev = repository.revision(branch);

        Estimator.Candidate candidate = new Estimator.Candidate();
        candidate.id = "noisy-candidate";
        candidate.matrix = readMatrix(unphysical);
        candidate.physical = polroom.domain.PhysicalConstraints.check(
                new Array2DRowRealMatrix(candidate.matrix));
        candidate.feasible = candidate.physical.physicallyRealizable;
        candidate.decompositions = polroom.domain.LuChipman.candidates(
                new Array2DRowRealMatrix(candidate.matrix));

        ObjectNode eventPayload = Json.object();
        eventPayload.put("candidateId", candidate.id);
        eventPayload.put("feasible", candidate.feasible);
        ArrayNode violations = eventPayload.putArray("violations");
        candidate.physical.violations.forEach(v -> {
            ObjectNode node = violations.addObject();
            node.put("rule", v.rule());
            node.put("value", v.value());
            node.put("bound", v.bound());
            node.put("detail", v.detail());
        });
        eventPayload.put("projected", false);
        eventPayload.put("sourceFingerprint", source.fingerprint());
        Repository.Event event = repository.appendEvent(branch, "CANDIDATE_FLAGGED", actor,
                "unphysical candidate flagged without projection", eventPayload, rev);
        record.put("eventId", event.eventId());
        record.put("feasible", candidate.feasible);
        record.put("violations", candidate.physical.violations.size());
    }

    private void conflictingDecisions(JsonNode step, String actor, Map<String, Object> record) {
        JsonNode a = step.path("branchA");
        JsonNode b = step.path("branchB");
        String baseBranch = branches.get("main");
        long revisionA = repository.revision(baseBranch);
        Repository.Event accept = repository.decide(baseBranch,
                a.path("candidate").asText(), "ACCEPTED", "window A accepts", actor, revisionA);
        long revisionAfterA = repository.revision(baseBranch);

        ConflictException conflict = null;
        try {
            repository.decide(baseBranch, b.path("candidate").asText(), "REJECTED",
                    "window B rejects using stale revision", actor, revisionA);
        } catch (ConflictException e) {
            conflict = e;
        }
        record.put("conflict", conflict != null);
        record.put("serverRevision", revisionAfterA);
        record.put("acceptedEventId", accept.eventId());
        if (conflict != null) {
            record.put("conflictActualRevision", conflict.actualRevision);
            record.put("conflictExpectedRevision", conflict.expectedRevision);
        }
        // stale window retries with the new revision and its own diff recorded
        Repository.Event retry = repository.decide(baseBranch, b.path("candidate").asText(),
                "REJECTED", "window B retries after seeing window A diff", actor,
                revisionAfterA);
        record.put("retryEventId", retry.eventId());
        Repository.Event undone = repository.undoLastDecision(baseBranch, actor,
                repository.revision(baseBranch));
        record.put("undoEventId", undone.eventId());
        record.put("undoRestores", true);
    }

    private void invalidateDependingOn(String branch, List<String> oldFingerprints, String actor,
                                       long revision, String reason,
                                       Repository.SourceVersion newSource) {
        for (Repository.Event event : repository.events(branch)) {
            if (!"DERIVATION_RECORDED".equals(event.kind)) {
                continue;
            }
            JsonNode scope = event.payload.path("scope");
            boolean affected = false;
            for (String fingerprint : oldFingerprints) {
                affected |= scope.toString().contains(fingerprint);
            }
            if (!affected) {
                continue;
            }
            String derivationFingerprint = event.payload.path("fingerprint").asText();
            Map<String, Object> scopeMap = new LinkedHashMap<>();
            scopeMap.put("reason", reason);
            scopeMap.put("oldSourceFingerprints", oldFingerprints);
            scopeMap.put("newSourceFingerprint", newSource.fingerprint());
            scopeMap.put("affectedBranch", branch);
            repository.invalidateDerivation(derivationFingerprint, branch, revision, actor,
                    reason + " invalidates derived Mueller", scopeMap);
            revision = repository.revision(branch);
        }
    }

    private void computeAndRecord(String branch, JsonNode sources, String summary, String actor,
                                  String stokesOrder, String rotationConvention,
                                  double darkOffset, double gainOffset, double gainScale,
                                  String extraConventionFingerprint) {
        long rev = repository.revision(branch);
        List<Repository.SourceVersion> branchSources = latestSourcesFor(sources, stokesOrder,
                rotationConvention, darkOffset, gainOffset, gainScale);
        List<String> fingerprints = new ArrayList<>(branchSources.stream()
                .map(Repository.SourceVersion::fingerprint).toList());
        if (extraConventionFingerprint != null) {
            fingerprints.add(extraConventionFingerprint);
        }

        List<Estimator.MeasurementSpec> specs = baselineSpecs(branchSources, stokesOrder,
                rotationConvention);
        Estimator.Result result = runEstimate(specs, sources, stokesOrder, rotationConvention);
        recordDerivation(branch, result, fingerprints, stokesOrder, rotationConvention,
                Map.of("summary", summary), actor, summary, rev);
    }

    private List<Repository.SourceVersion> latestSourcesFor(JsonNode sources,
                                                             String stokesOrder,
                                                             String rotationConvention,
                                                             double darkOffset,
                                                             double gainOffset, double gainScale) {
        List<Repository.SourceVersion> latest = new ArrayList<>();
        for (String kind : Repository.SOURCE_KINDS) {
            List<Repository.SourceVersion> found = repository.sources(materialId).stream()
                    .filter(s -> s.kind().equals(kind)).toList();
            if (!found.isEmpty()) {
                latest.add(found.get(found.size() - 1));
            } else {
                JsonNode node = sources.path(kind);
                latest.add(new Repository.SourceVersion("synthetic-" + kind, 1, materialId, kind,
                        Fingerprint.sha256(node.toString()), node.path("unit").asText(""),
                        node.path("coordinateBasis").asText(""), node.toString(), node.deepCopy(),
                        null, "synthetic", "synthetic", List.of()));
            }
        }
        return latest;
    }

    private List<Estimator.MeasurementSpec> baselineSpecs(List<Repository.SourceVersion> sources,
                                                          String stokesOrder,
                                                          String rotationConvention) {
        Map<String, Repository.SourceVersion> byKind = new LinkedHashMap<>();
        sources.forEach(s -> byKind.put(s.kind(), s));
        JsonNode stateSource = byKind.get("optical_state").payload();
        JsonNode rawSource = byKind.get("raw_intensity").payload();
        JsonNode darkSource = byKind.get("dark_frame").payload();
        List<JsonNode> stateNodes = new ArrayList<>();
        stateSource.path("states").elements().forEachRemaining(stateNodes::add);
        List<double[]> psgs = new ArrayList<>();
        List<double[]> psas = new ArrayList<>();
        Conventions.StokesOrder order = Conventions.STOKES_BY_ID
                .getOrDefault(statesOrderOf(stateNodes, stokesOrder), Conventions.StokesOrder.SHV);
        Conventions.RotationConvention rotation = Conventions.ROTATION_BY_ID
                .getOrDefault(rotationConvention, Conventions.RotationConvention.RIGHT_HANDED);
        for (JsonNode node : stateNodes) {
            String role = node.path("role").asText();
            double[] vector;
            if (node.has("stokes")) {
                vector = new double[4];
                for (int i = 0; i < 4; i++) {
                    vector[i] = node.path("stokes").path(i).asDouble();
                }
            } else {
                vector = Estimator.decodeState(node.path("code").asText(),
                        node.path("angleDeg").asDouble(0),
                        node.path("ellipticityDeg").asDouble(0),
                        order, List.of("I", "Q", "U", "V"), rotation);
            }
            if ("PSG".equals(role)) {
                psgs.add(vector);
            } else {
                psas.add(vector);
            }
        }
        List<Estimator.MeasurementSpec> specs = new ArrayList<>();
        JsonNode raw = rawSource.path("values");
        JsonNode dark = darkSource.path("values");
        int k = 0;
        for (double[] psa : psas) {
            for (double[] psg : psgs) {
                Estimator.MeasurementSpec spec = new Estimator.MeasurementSpec();
                spec.psg = psg;
                spec.psa = psa;
                spec.intensity = raw.path(k).asDouble();
                spec.dark = dark.path(k).asDouble();
                spec.label = "k" + k;
                specs.add(spec);
                k++;
            }
        }
        return specs;
    }

    private String statesOrderOf(List<JsonNode> stateNodes, String fallback) {
        for (JsonNode node : stateNodes) {
            if (node.has("stokesOrder")) {
                return node.path("stokesOrder").asText();
            }
        }
        return fallback;
    }

    private void recordDerivation(String branch, Estimator.Result result,
                                  List<String> sourceFingerprints, String stokesOrder,
                                  String rotationConvention, Map<String, Object> scope,
                                  String actor, String summary, long revision) {
        ObjectNode resultNode = Json.MAPPER.valueToTree(result);
        ObjectNode basis = Json.object();
        basis.put("ruleVersion", CasePackage.loadBundled().manifest.path("ruleVersion").asText());
        basis.set("sourceFingerprints", Json.MAPPER.valueToTree(sourceFingerprints.stream().sorted().toList()));
        basis.put("stokesOrder", stokesOrder);
        basis.put("rotationConvention", rotationConvention);
        String fingerprint = Fingerprint.derivation(
                CasePackage.loadBundled().manifest.path("ruleVersion").asText(),
                sourceFingerprints, stokesOrder, rotationConvention, Map.of());
        ObjectNode params = Json.object();
        params.put("svdTolerance",
                CasePackage.loadBundled().manifest.path("svdTolerance").asDouble());
        repository.recordDerivation(fingerprint,
                CasePackage.loadBundled().manifest.path("ruleVersion").asText(),
                stokesOrder, rotationConvention, basis, params, resultNode,
                branch, revision, actor, summary, scope);
    }

    private static ArrayNode doubleArray(JsonNode values) {
        ArrayNode array = Json.object().arrayNode();
        values.elements().forEachRemaining(v -> array.add(v.asDouble()));
        return array;
    }

    private static double[][] readMatrix(JsonNode matrix) {
        double[][] out = new double[matrix.size()][];
        for (int i = 0; i < matrix.size(); i++) {
            JsonNode row = matrix.path(i);
            out[i] = new double[row.size()];
            for (int j = 0; j < row.size(); j++) {
                out[i][j] = row.path(j).asDouble();
            }
        }
        return out;
    }
}
