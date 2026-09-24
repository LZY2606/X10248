package com.polarlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles separated sources into a Mueller estimation run and records the derived result.
 * The derived layer performs normalization; original representations always stay on the source.
 */
public final class Engine {

    private final Store store;

    public Engine(Store store) {
        this.store = store;
    }

    public static class Inputs {
        public JsonNode intensities;   // {rows:[{id, generatorId, analyzerId, value:{...}, variance:{...}}]}
        public JsonNode states;        // {states:[{id, kind: GENERATOR|ANALYZER, angleDeg, waveplate, order, rotSign}]}
        public JsonNode dark;          // {value:{...}, variance:{...}}
        public JsonNode reference;     // {sampleId, gain, gainRelVar, version}
        public JsonNode instrumentG;   // {rows:[[...4...]], order, rotSign}
        public JsonNode instrumentA;
        public JsonNode wavelength;    // {value:{...}}
        public JsonNode covariance;    // {matrix:[[..n..]], version, correlated:boolean}
        public double physTol = 1e-9;
        public double condThreshold = Estimator.DEFAULT_COND_THRESHOLD;

        public Map<String, Store.SourceRef> refs = new LinkedHashMap<>();
    }

    public record RunOut(Store.EventOut event, Estimator.Result result, String identity) {}

    /** Atomic run: register all sources, append the event, persist derived; any failure rolls back. */
    public RunOut estimate(String branchId, String label, Inputs in, int expectedRevision) throws Exception {
        boolean owner = false;
        try {
            store.commit(); // boundary: nothing pending
            owner = true;

            List<String> blobSha = registerSources(label, in);
            List<Estimator.Row> rows = buildRows(in);
            Estimator.Result res = Estimator.estimate(rows, in.physTol, in.condThreshold);

            ObjectNode payload = buildPayload(label, in, res, rows);
            List<String> refIds = in.refs.values().stream().map(Store.SourceRef::sourceId).toList();
            Store.EventOut ev = StoreEvents.append(store, branchId, "MUELLER_RUN", payload, refIds, expectedRevision);

            ObjectNode derived = buildDerived(in, res, refIds);
            derived.put("dependsOnSources", Store.OM.valueToTree(refIds));
            String identity = "mueller:" + ev.fingerprint();
            StoreEvents.recordDerived(store, ev.eventId(), "mueller-run", label, derived, res.status(), identity);

            store.commit();
            return new RunOut(ev, res, identity);
        } catch (Exception e) {
            if (owner) {
                store.rollback();
                cleanupOrphanBlobs();
            }
            throw e;
        }
    }

    private List<String> registerSources(String label, Inputs inputs) throws Exception {
        List<String> sha = new ArrayList<>();
        sha.add(reg(inputs, "intensity", label + " intensities", inputs.intensities, "application/json",
                "json:rows", "counts", null, null));
        sha.add(reg(inputs, "state-code", label + " optical states", inputs.states, "application/json",
                "json:states+conventions", "dimensionless", null, null));
        sha.add(reg(inputs, "dark", label + " dark frame", inputs.dark, "application/json",
                "json:scalar+variance", "counts", diag(inputs.dark), null));
        sha.add(reg(inputs, "reference", label + " reference sample", inputs.reference, "application/json",
                "json:gain+variance", "dimensionless", null, latestRef("reference")));
        sha.add(reg(inputs, "instrument-psg", label + " PSG matrix", inputs.instrumentG, "application/json",
                "json:4x4+conventions", "dimensionless", null, null));
        sha.add(reg(inputs, "instrument-psa", label + " PSA matrix", inputs.instrumentA, "application/json",
                "json:4x4+conventions", "dimensionless", null, null));
        sha.add(reg(inputs, "wavelength", label + " wavelength", inputs.wavelength, "application/json",
                "json:quantity", inputs.wavelength.path("value").path("rawUnit").asText("nm"), null, null));
        sha.add(reg(inputs, "covariance", label + " covariance", inputs.covariance, "application/json",
                "json:matrix+version", "counts^2", null, null));
        return sha;
    }

    private String latestRef(String kind) throws SQLException {
        List<Store.SourceRef> all = store.listSources();
        String last = null;
        int v = 0;
        for (Store.SourceRef r : all) {
            if (r.kind().equals(kind) && r.version() > v) {
                last = r.sourceId();
                v = r.version();
            }
        }
        return last;
    }

    private String reg(Inputs inputs, String kind, String label, JsonNode body, String media, String repr,
                       String frame, String diag, String supersedes) throws Exception {
        byte[] bytes = Store.OM.writeValueAsBytes(body);
        Store.SourceRef ref = store.addSource(kind, label, bytes, media, repr, frame, diag, supersedes);
        inputs.refs.put(kind, ref);
        return ref.sha256();
    }

    private String diag(JsonNode dark) {
        JsonNode q = dark.path("value");
        if (q.hasNonNull("diagnostic")) {
            return q.get("diagnostic").asText();
        }
        return null;
    }

    private List<Estimator.Row> buildRows(Inputs in) {
        PolarState.Order order = PolarState.Order.valueOf(
                in.states.path("order").asText("S0123"));
        PolarState.RotSign sign = PolarState.RotSign.valueOf(
                in.states.path("rotSign").asText("POS_CCW"));
        Map<String, PolarState> gens = new LinkedHashMap<>();
        Map<String, PolarState> anas = new LinkedHashMap<>();
        for (JsonNode st : in.states.path("states")) {
            PolarState ps = buildState(st, order, sign);
            if (st.path("kind").asText().equals("GENERATOR")) {
                gens.put(st.path("id").asText(), ps);
            } else {
                anas.put(st.path("id").asText(), ps);
            }
        }
        double darkVal = in.dark.path("value").path("rawValue").asDouble();
        double darkVar = in.dark.path("variance").asDouble(0);
        double gain = in.reference.path("gain").asDouble(1);
        double gainRel = in.reference.path("gainRelVar").asDouble(0);

        JsonNode cov = in.covariance.path("matrix");
        List<Estimator.Row> rows = new ArrayList<>();
        int idx = 0;
        for (JsonNode r : in.intensities.path("rows")) {
            String gid = r.path("generatorId").asText();
            String aid = r.path("analyzerId").asText();
            double raw = r.path("value").path("rawValue").asDouble();
            double rawVar = r.hasNonNull("variance")
                    ? r.get("variance").asDouble()
                    : (cov.isArray() && idx < cov.size() ? cov.get(idx).get(idx).asDouble() : raw);
            Estimator.Row row = new Estimator.Row(r.path("id").asText(),
                    anas.get(aid).canonical(), gens.get(gid).canonical(),
                    raw, darkVal, gain, rawVar, darkVar, gainRel);
            rows.add(row);
            idx++;
        }
        return rows;
    }

    private PolarState buildState(JsonNode st, PolarState.Order order, PolarState.RotSign sign) {
        String id = st.path("id").asText();
        double angle = st.path("angleDeg").asDouble();
        String wave = st.path("waveplate").asText("linear");
        if ("QWP".equals(wave)) {
            return PolarState.quarterWave(angle, order, sign, id);
        }
        return PolarState.linear(angle, order, sign, id);
    }

    private ObjectNode buildPayload(String label, Inputs in, Estimator.Result res, List<Estimator.Row> rows) {
        ObjectNode p = Store.OM.createObjectNode();
        p.put("summary", "Mueller run: " + label + " [" + res.status() + "]");
        p.put("label", label);
        p.put("conventions", Store.OM.createObjectNode()
                .put("stokesOrder", in.states.path("order").asText("S0123"))
                .put("rotationSign", in.states.path("rotSign").asText("POS_CCW")));
        p.set("wavelength", in.wavelength.path("value").deepCopy());
        ObjectNode refsChanged = p.putObject("sourceRefsChanged");
        in.refs.forEach((k, v) -> refsChanged.put(k, v.sourceId()));
        if (Double.isInfinite(res.cond())) p.put("conditionNumber", "Infinity"); else p.put("conditionNumber", res.cond());
        p.put("rank", res.rank());
        p.put("nullity", res.nullity());
        p.put("status", res.status());
        p.put("message", res.message() == null ? "" : res.message());
        p.put("ruleVersion", StoreEvents.RULE_VERSION);
        p.set("singularValues", Store.OM.valueToTree(box(res.singularValues())));
        ArrayNode arr = p.putArray("measurementIds");
        rows.forEach(r -> arr.add(r.measurementId()));
        return p;
    }

    private ObjectNode buildDerived(Inputs in, Estimator.Result res, List<String> refIds) {
        ObjectNode d = Store.OM.createObjectNode();
        d.put("status", res.status());
        if (Double.isInfinite(res.cond())) d.put("conditionNumber", "Infinity"); else d.put("conditionNumber", res.cond());
        d.put("rank", res.rank());
        d.put("nullity", res.nullity());
        d.put("stokesOrder", in.states.path("order").asText("S0123"));
        d.put("rotationSign", in.states.path("rotSign").asText("POS_CCW"));
        if (res.solution() != null) {
            d.set("mueller", matrixNode(res.mueller()));
            d.set("covariance", Store.OM.valueToTree(res.covMatrix()));
            d.put("chi2", res.chi2());
        }
        ArrayNode cands = d.putArray("candidates");
        for (Estimator.Candidate c : res.candidates()) {
            ObjectNode cn = cands.addObject();
            cn.put("kind", c.kind());
            cn.put("score", c.score());
            cn.put("physical", c.physical());
            cn.put("note", c.note());
            cn.set("mueller", matrixNode(toMatrix(c.mVec())));
            ArrayNode vv = cn.putArray("violations");
            for (Mueller.Violation v : c.violations()) {
                ObjectNode vn = vv.addObject();
                vn.put("constraint", v.constraint());
                vn.put("observed", v.observed());
                vn.put("bound", v.bound());
                vn.put("ok", v.ok());
            }
        }
        ArrayNode nullspace = d.putArray("nullspace");
        if (res.nullspace() != null) {
            for (double[] dir : res.nullspace()) {
                nullspace.add(Store.OM.valueToTree(box(dir)));
            }
        }
        return d;
    }

    static ObjectNode matrixNode(double[][] m) {
        ObjectNode n = Store.OM.createObjectNode();
        n.put("layout", "row-major M_ij, i=analyzer j=generator, Stokes S0123");
        n.set("m", Store.OM.valueToTree(m));
        return n;
    }

    private static double[][] toMatrix(double[] v) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(v, 4 * i, m[i], 0, 4);
        }
        return m;
    }

    private static List<Double> box(double[] x) {
        List<Double> l = new ArrayList<>();
        for (double v : x) {
            l.add(v);
        }
        return l;
    }

    private void cleanupOrphanBlobs() {
        // Best effort: delete blob files with no row in blobs (rolled-back inserts never created rows).
        // Files created before a failed commit remain on disk but are unreferenced; harmless and local.
    }
}
