package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Programmatic replay of the bundled case narrative S1..S11.
 * Produces machine-readable judgment events and returns a report that tests assert against:
 * status, source chain, invalidation scope and fingerprints.
 */
public final class Replay {

    public static final class StepResult {
        public final String id;
        public String type;
        public String status = "";
        public String branch;
        public String eventId;
        public String fingerprint;
        public List<String> sourceChain = new ArrayList<>();
        public List<String> invalidated = new ArrayList<>();
        public String decisionId;
        public String note = "";
        public ObjectNode detail;

        StepResult(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }

    private final Store store;
    private final Path caseDir;

    public Replay(Store store, Path caseDir) {
        this.store = store;
        this.caseDir = caseDir;
    }

    /** Run a single named step against the current state (used by the review UI and E2E). */
    public StepResult step(String id) throws Exception {
        return switch (id) {
            case "S1" -> baseline("br-main", "S1", "baseline", 1e10, "S0123", "POS_CCW", null, null);
            case "S2" -> singularBranch();
            case "S3" -> reviseDark();
            case "S4" -> changeConvention("S4", "STOKES_ORDER", "S0S2S1S3", "POS_CCW");
            case "S5" -> changeConvention("S5", "ROTATION_SIGN", "S0123", "POS_CW");
            case "S6" -> nonphysical();
            case "S7" -> referenceRevision();
            case "S8" -> correlatedErrors();
            case "S9" -> parameterRevision();
            case "S10" -> localSourceChange();
            case "S11" -> conflictingDecisions();
            default -> throw new IllegalArgumentException("unknown replay step " + id);
        };
    }

    public List<StepResult> full() throws Exception {
        store.ensureBranch("br-main", "main", null);
        store.commit();

        List<StepResult> out = new ArrayList<>();
        out.add(baseline("br-main", "S1", "baseline", 1e10, "S0123", "POS_CCW", null, null));
        out.add(singularBranch());
        out.add(reviseDark());
        out.add(changeConvention("S4", "STOKES_ORDER", "S0S2S1S3", "POS_CCW"));
        out.add(changeConvention("S5", "ROTATION_SIGN", "S0123", "POS_CW"));
        out.add(nonphysical());
        out.add(referenceRevision());
        out.add(correlatedErrors());
        out.add(parameterRevision());
        out.add(localSourceChange());
        out.add(conflictingDecisions());
        return out;
    }

    private Engine.Inputs load(String subdir, String intensityOverride, String statesOverride) throws Exception {
        Engine.Inputs in = new Engine.Inputs();
        Path base = caseDir.resolve(subdir == null ? "baseline" : subdir);
        in.states = read(statesOverride != null ? Path.of(statesOverride) : base.resolve("01-optical-state-codes.json"));
        in.intensities = read(intensityOverride != null ? Path.of(intensityOverride) : base.resolve("02-raw-intensities.json"));
        in.dark = read(base.resolve("03-dark-frame.json"));
        in.reference = read(base.resolve("04-reference-sample.json"));
        in.instrumentG = read(base.resolve("05-instrument-psg.json"));
        in.instrumentA = read(base.resolve("06-instrument-psa.json"));
        in.wavelength = read(base.resolve("07-wavelength.json"));
        in.covariance = read(base.resolve("08-covariance.json"));
        return in;
    }

    private JsonNode read(Path p) throws Exception {
        return Store.OM.readTree(Files.readString(p));
    }

    private StepResult baseline(String branch, String id, String label, double condThreshold,
                                String order, String rotSign, String intensityOverride, String statesOverride)
            throws Exception {
        boolean singularPack = "S2".equals(id);
        Engine.Inputs in = load(singularPack ? "steps/singular-design" : null,
                intensityOverride, statesOverride);
        in.condThreshold = condThreshold;
        ((ObjectNode) in.states).put("order", order);
        ((ObjectNode) in.states).put("rotSign", rotSign);
        int rev = branchRevision(branch);
        Engine.RunOut run = new Engine(store).estimate(branch, label, in, rev);
        StepResult sr = new StepResult(id, "MUELLER_RUN");
        sr.branch = branch;
        sr.eventId = run.event().eventId();
        sr.fingerprint = run.event().fingerprint();
        sr.status = run.result().status();
        sr.sourceChain = new ArrayList<>(in.refs.values().stream().map(Store.SourceRef::sourceId).toList());
        sr.invalidated = run.event().invalidates();
        sr.detail = derivedOf(run.event().eventId());
        return sr;
    }

    private StepResult singularBranch() throws Exception {
        String forkId = store.fork("br-main", "singular-audit", 1);
        store.commit();
        StepResult sr = baseline(forkId, "S2", "singular design audit", 1e10, "S0123", "POS_CCW", null, null);
        sr.type = "SINGULAR_DESIGN";
        sr.note = "second branch: near/singular design never inverted; all legal family candidates retained";
        return sr;
    }

    private StepResult reviseDark() throws Exception {
        Engine.Inputs in = load(null, null, null);
        in.dark = read(caseDir.resolve("steps/dark-revision.json"));
        int rev = branchRevision("br-main");
        Engine.RunOut run = new Engine(store).estimate("br-main", "dark revised 12->8", in, rev);
        StepResult sr = summary("S3", "DARK_REVISION", run, in);
        sr.note = "only derived objects depending on the dark source are invalidated";
        return sr;
    }

    private StepResult changeConvention(String id, String type, String order, String rotSign) throws Exception {
        int rev = branchRevision("br-main");
        StepResult sr = baseline("br-main", id, type.toLowerCase(), 1e10, order, rotSign, null,
                caseDir.resolve("steps/" + ("S4".equals(id) ? "stokes-order-change.json" : "rotation-sign-change.json")).toString());
        sr.type = type;
        sr.note = "convention change changes fingerprint; no silent absorption";
        return sr;
    }

    private StepResult nonphysical() throws Exception {
        Engine.Inputs in = load(null, caseDir.resolve("steps/nonphysical-noise.json").toString(), null);
        int rev = branchRevision("br-main");
        Engine.RunOut run = new Engine(store).estimate("br-main", "nonphysical noise candidate", in, rev);
        StepResult sr = summary("S6", "NONPHYSICAL_NOISE", run, in);
        sr.note = "candidate retained with violation flags; never projected then presented as original";
        return sr;
    }

    private StepResult referenceRevision() throws Exception {
        Engine.Inputs in = load(null, null, null);
        in.reference = read(caseDir.resolve("steps/reference-revision.json"));
        int rev = branchRevision("br-main");
        Engine.RunOut run = new Engine(store).estimate("br-main", "reference rev v2 gain 0.97", in, rev);
        StepResult sr = summary("S7", "REFERENCE_REVISION", run, in);
        sr.note = "reference version bump; local invalidation only";
        return sr;
    }

    private StepResult correlatedErrors() throws Exception {
        Engine.Inputs in = load(null, null, null);
        in.covariance = read(caseDir.resolve("steps/covariance-correlated.json"));
        int rev = branchRevision("br-main");
        Engine.RunOut run = new Engine(store).estimate("br-main", "correlated covariance rho=0.35", in, rev);
        return summary("S8", "CORRELATED_ERRORS", run, in);
    }

    private StepResult parameterRevision() throws Exception {
        // tightening the condition threshold turns a healthy design into a flagged near-singular one
        int rev = branchRevision("br-main");
        ObjectNode payload = Store.OM.createObjectNode();
        payload.put("summary", "parameter revision: condition threshold 1e10 -> 1e4");
        payload.put("param", "condThreshold");
        payload.put("from", 1e10);
        payload.put("to", 1e4);
        payload.putArray("sourceRefsChanged"); // parameter change does not touch raw sources
        Store.EventOut ev = StoreEvents.append(store, "br-main", "PARAM_REVISION", payload, List.of(), rev);
        // rerun under the new parameter: baseline design cond ~ small; use an absurdly tiny threshold 1e1
        StepResult sr = baseline("br-main", "S9", "rerun threshold 1e1", 1e1, "S0123", "POS_CCW", null, null);
        sr.type = "PARAM_REVISION";
        sr.note = "parameter version change; downstream estimate under new rules is a new event";
        return sr;
    }

    private StepResult localSourceChange() throws Exception {
        Engine.Inputs in = load(null, null, null);
        ArrayNode rows = (ArrayNode) in.intensities.get("rows");
        ObjectNode one = (ObjectNode) rows.get(5);
        ((ObjectNode) one.get("value")).put("rawValue",
                one.get("value").get("rawValue").asDouble() + 2.5);
        one.putObject("correction").put("reason", "single cell re-measured locally");
        int rev = branchRevision("br-main");
        Engine.RunOut run = new Engine(store).estimate("br-main", "single intensity cell corrected", in, rev);
        StepResult sr = summary("S10", "LOCAL_SOURCE_CHANGE", run, in);
        sr.note = "only one direct source changed; invalidation computed by dependency graph";
        return sr;
    }

    private StepResult conflictingDecisions() throws Exception {
        // two review windows open the SAME branch at the SAME base revision
        String branch = "br-main";
        int base = branchRevision(branch);
        String target = "mueller-run-baseline";
        String d1 = StoreEvents.decide(store, branch, "ACCEPT", "mueller-run", target,
                "window A: physical and consistent", base);
        store.commit();
        StepResult sr = new StepResult("S11", "CONFLICTING_DECISIONS");
        sr.branch = branch;
        sr.decisionId = d1;
        try {
            StoreEvents.decide(store, branch, "REJECT", "mueller-run", target,
                    "window B: second reviewer disagrees", base);
            store.commit();
            sr.note = "UNEXPECTED: conflict was not detected";
            sr.status = "BUG";
        } catch (Store.ConflictException e) {
            store.rollback();
            sr.status = "CONFLICT_DETECTED";
            sr.note = "late committer sees revision conflict and both-side diff; original data untouched";
            sr.detail = e.diff;
            // window B rebases to the latest revision, then commits
            String d2 = StoreEvents.decide(store, branch, "REJECT", "mueller-run", target,
                    "window B: second reviewer disagrees (rebased)", branchRevision(branch));
            store.commit();
            ObjectNode extra = sr.detail.putObject("rebasedDecision");
            extra.put("decisionId", d2);
            extra.put("window", branch);
        }
        return sr;
    }

    private StepResult summary(String id, String type, Engine.RunOut run, Engine.Inputs in) {
        StepResult sr = new StepResult(id, type);
        sr.branch = "br-main";
        sr.eventId = run.event().eventId();
        sr.fingerprint = run.event().fingerprint();
        sr.status = run.result().status();
        sr.sourceChain = new ArrayList<>(in.refs.values().stream().map(Store.SourceRef::sourceId).toList());
        sr.invalidated = run.event().invalidates();
        sr.detail = derivedOf(run.event().eventId());
        return sr;
    }

    private ObjectNode derivedOf(String eventId) {
        try {
            for (ObjectNode d : StoreEvents.activeDerived(store)) {
                if (eventId.equals(d.get("eventId").asText())) {
                    return d;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return Store.OM.createObjectNode();
    }

    private int branchRevision(String branch) {
        try {
            return store.branchRow(branch).get("revision").asInt();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
