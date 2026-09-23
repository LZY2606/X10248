package org.polarroom.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.polarroom.core.Conventions;
import org.polarroom.core.Decomposition;
import org.polarroom.core.LinAlg;
import org.polarroom.core.Models;
import org.polarroom.core.Mueller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs branch views and computes the Mueller estimation pipeline.
 *
 * <p>Nothing here mutates sources. Every derived result is stored against the
 * event whose input versions produced it, so later revisions never overwrite
 * old conclusions; the engine reports which objects a revision invalidates.
 */
public final class Engine {

    public static final String ALGORITHM_VERSION = "polarroom-fit-1.0";

    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public Engine(Db db) {
        this.db = db;
    }

    /** Roles used to bind the seven separated material kinds in a view. */
    public static final List<String> ROLES = List.of(
            "intensity", "optical_state", "dark", "reference",
            "instrument", "wavelength", "covariance");

    public AnalysisView viewAt(String branchId, String eventId) {
        List<Models.Event> prefix = eventId == null
                ? List.of() : db.eventsUntil(branchId, eventId);
        Map<String, Integer> versions = new LinkedHashMap<>();
        Map<String, Object> params = defaultRuleParams();
        String ruleVersion = "rules-1.0";
        String stokesOrder = "S0,S1,S2,S3";
        int rotationSign = 1;
        for (Models.Event e : prefix) {
            Map<String, Object> p = payload(e);
            switch (e.kind) {
                case "role_binding":
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> bindings =
                            mapper.convertValue(p.get("versions"), Map.class);
                    for (Map.Entry<String, Integer> en : bindings.entrySet()) {
                        versions.put(en.getKey(), en.getValue());
                    }
                    break;
                case "rule_update":
                    ruleVersion = str(p.get("ruleVersion"), ruleVersion);
                    if (p.get("stokesOrder") != null) {
                        stokesOrder = (String) p.get("stokesOrder");
                    }
                    if (p.get("rotationSign") != null) {
                        rotationSign = ((Number) p.get("rotationSign")).intValue();
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pp =
                            mapper.convertValue(p.getOrDefault("parameters", Map.of()), Map.class);
                    params.putAll(pp);
                    break;
                default:
                    break;
            }
        }
        Map<String, Models.RulingState> rulings = db.rulingStateAt(prefix);
        return new AnalysisView(branchId, eventId, prefix, versions, params,
                ruleVersion, stokesOrder, rotationSign, rulings);
    }

    public static Map<String, Object> defaultRuleParams() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rankRelTol", 1e-9);
        m.put("nearSingularLog10Cond", 8.0);
        m.put("applyReferenceGain", true);
        m.put("applyInstrumentCalibration", true);
        return m;
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : o.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Models.Event e) {
        try {
            return mapper.readValue(e.payloadJson, Map.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ------------------------------------------------------------- compute

    /** Everything the review page renders for a branch view, with stable ids. */
    public static final class Computation {
        public Map<String, Object> summary = new LinkedHashMap<>();
        public Mueller.FitResult fit;
        public Mueller.PhysicalReport physical;
        public List<Map<String, Object>> candidates = new ArrayList<>();
        public List<Map<String, Object>> measurements = new ArrayList<>();
        public List<String> diagnostics = new ArrayList<>();
        public Map<String, String> inputBlobByRole = new LinkedHashMap<>();
        public String fitFingerprint;
        public boolean nearSingular;
    }

    public Computation compute(AnalysisView view) {
        Computation c = new Computation();

        Models.SourceVersion intensityV = version(view, "intensity");
        Models.SourceVersion stateV = version(view, "optical_state");
        Models.SourceVersion darkV = version(view, "dark");
        Models.SourceVersion refV = version(view, "reference");
        Models.SourceVersion instrV = version(view, "instrument");
        Models.SourceVersion waveV = version(view, "wavelength");
        Models.SourceVersion covV = version(view, "covariance");

        SourceParser.Parsed statesP = SourceParser.parse(Models.MaterialType.OPTICAL_STATE,
                db.getBlob(stateV.blobSha));
        SourceParser.Parsed intensityP = SourceParser.parse(Models.MaterialType.INTENSITY,
                db.getBlob(intensityV.blobSha));
        SourceParser.Parsed darkP = SourceParser.parse(Models.MaterialType.DARK,
                db.getBlob(darkV.blobSha));
        SourceParser.Parsed refP = SourceParser.parse(Models.MaterialType.REFERENCE,
                db.getBlob(refV.blobSha));
        SourceParser.Parsed instrP = SourceParser.parse(Models.MaterialType.INSTRUMENT,
                db.getBlob(instrV.blobSha));
        SourceParser.Parsed waveP = SourceParser.parse(Models.MaterialType.WAVELENGTH,
                db.getBlob(waveV.blobSha));
        SourceParser.Parsed covP = SourceParser.parse(Models.MaterialType.COVARIANCE,
                db.getBlob(covV.blobSha));

        c.inputBlobByRole.put("intensity", intensityV.blobSha);
        c.inputBlobByRole.put("optical_state", stateV.blobSha);
        c.inputBlobByRole.put("dark", darkV.blobSha);
        c.inputBlobByRole.put("reference", refV.blobSha);
        c.inputBlobByRole.put("instrument", instrV.blobSha);
        c.inputBlobByRole.put("wavelength", waveV.blobSha);
        c.inputBlobByRole.put("covariance", covV.blobSha);
        c.diagnostics.addAll(statesP.diagnostics);
        c.diagnostics.addAll(intensityP.diagnostics);
        c.diagnostics.addAll(darkP.diagnostics);
        c.diagnostics.addAll(covP.diagnostics);

        boolean calibrate = Boolean.TRUE.equals(view.ruleParams.get("applyInstrumentCalibration"));
        boolean refGain = Boolean.TRUE.equals(view.ruleParams.get("applyReferenceGain"));

        Map<String, double[]> stokesByState = new LinkedHashMap<>();
        for (Models.StateRow sr : statesP.states) {
            stokesByState.put(sr.stateId,
                    applyInstrumentToState(instrP, sr.canonicalStokes, calibrate, false));
        }
        Map<String, double[]> analyzerByState = new LinkedHashMap<>();
        for (Models.StateRow sr : statesP.states) {
            analyzerByState.put(sr.stateId,
                    applyInstrumentToState(instrP, sr.canonicalStokes, calibrate, true));
        }

        List<Models.IntensityRow> rows = new ArrayList<>(intensityP.intensities);
        rows.sort((a, b) -> Integer.compare(a.idx, b.idx));
        int n = rows.size();
        double[][] h = new double[n][16];
        double[] y = new double[n];
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            Models.IntensityRow r = rows.get(i);
            double[] g = stokesByState.get(r.generatorStateId);
            double[] a = analyzerByState.get(r.analyserStateId);
            if (g == null || a == null || !r.value.convertible) {
                c.diagnostics.add("row " + r.idx + " excluded: missing state or unconvertible");
                continue;
            }
            used[i] = true;
            h[i] = Mueller.designRow(a, g);
            double dark = darkP.dark.getOrDefault(r.idx,
                    average(darkP.dark.values()));
            double v = r.value.value - dark;
            double[] gain = refP.gains.get(r.idx);
            if (refGain && gain != null && gain[0] > 0) {
                v = v / gain[0];
            }
            y[i] = v;

            Map<String, Object> rowInfo = new LinkedHashMap<>();
            rowInfo.put("idx", r.idx);
            rowInfo.put("generator", r.generatorStateId);
            rowInfo.put("analyser", r.analyserStateId);
            rowInfo.put("rawValue", r.value.toMap());
            rowInfo.put("dark", round(dark));
            rowInfo.put("referenceGain", gain == null ? null : round(gain[0]));
            rowInfo.put("referenceGainSigma", gain == null ? null : round(gain[1]));
            rowInfo.put("correctedSignal", round(v));
            rowInfo.put("used", true);
            c.measurements.add(rowInfo);
        }
        int usedCount = 0;
        for (boolean u : used) {
            if (u) {
                usedCount++;
            }
        }
        double[][] hUse = new double[usedCount][16];
        double[] yUse = new double[usedCount];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                hUse[k] = h[i];
                yUse[k] = y[i];
                k++;
            }
        }

        double[][] cov = adaptCovariance(covP.covariance, used);
        double tol = ((Number) view.ruleParams.get("rankRelTol")).doubleValue();
        Mueller.FitResult fit = Mueller.weightedFit(hUse, yUse, cov, tol);
        c.fit = fit;
        c.physical = Mueller.checkPhysical(fit.m);
        double log10Cond = Double.isInfinite(fit.condition)
                ? 1e9 : Math.log10(fit.condition);
        c.nearSingular = !fit.unique || log10Cond
                >= ((Number) view.ruleParams.get("nearSingularLog10Cond")).doubleValue();

        Map<String, String> inputVersions = new LinkedHashMap<>();
        inputVersions.put("intensity", intensityV.materialId + "#v" + intensityV.versionNo);
        inputVersions.put("optical_state", stateV.materialId + "#v" + stateV.versionNo);
        inputVersions.put("dark", darkV.materialId + "#v" + darkV.versionNo);
        inputVersions.put("reference", refV.materialId + "#v" + refV.versionNo);
        inputVersions.put("instrument", instrV.materialId + "#v" + instrV.versionNo);
        inputVersions.put("wavelength", waveV.materialId + "#v" + waveV.versionNo);
        inputVersions.put("covariance", covV.materialId + "#v" + covV.versionNo);
        c.fitFingerprint = Conventions.fingerprint(ALGORITHM_VERSION, view.ruleVersion,
                view.ruleParams, inputVersions, view.stokesOrder, view.rotationSign, "fit");

        c.summary.put("measurements", n);
        c.summary.put("usedMeasurements", usedCount);
        c.summary.put("rank", fit.rank);
        c.summary.put("nullity", fit.nullity);
        c.summary.put("unique", fit.unique);
        c.summary.put("condition", fit.condition);
        c.summary.put("log10Condition", log10Cond);
        c.summary.put("chiSquared", round(fit.chiSquared));
        c.summary.put("dof", fit.dof);
        c.summary.put("nearSingular", c.nearSingular);
        c.summary.put("rankRelTol", tol);
        c.summary.put("physicallyRealizable", c.physical.physicallyRealizable);
        c.summary.put("passive", c.physical.passive);
        c.summary.put("psdResidual", round(c.physical.psdResidual));
        c.summary.put("hermiticityResidual", round(c.physical.hermiticityResidual));
        c.summary.put("passivityResidual", round(c.physical.passivityResidual));
        c.summary.put("depolarizationIndex", round(c.physical.depolarizationIndex));
        c.summary.put("wavelength", waveP.wavelength.toMap());
        c.summary.put("stokesOrder", view.stokesOrder);
        c.summary.put("rotationSign", view.rotationSign);
        c.summary.put("fitFingerprint", c.fitFingerprint);

        addCandidates(c, view, inputVersions, fit);
        return c;
    }

    private void addCandidates(Computation c, AnalysisView view,
                               Map<String, String> inputVersions,
                               Mueller.FitResult fit) {
        addDecompositionCandidate(c, view, inputVersions, "decomp_forward",
                Decomposition.forwardLuChipman(fit.m));
        addDecompositionCandidate(c, view, inputVersions, "decomp_backward",
                Decomposition.backwardLuChipman(fit.m));
        addDecompositionCandidate(c, view, inputVersions, "decomp_cloude",
                Decomposition.cloudeTargets(fit.m));

        if (!fit.unique) {
            for (int i = 0; i < fit.nullspace.size(); i++) {
                Map<String, Object> cand = new LinkedHashMap<>();
                double[] dir = fit.nullspace.get(i);
                cand.put("candidateKey", "nullvec_" + i);
                cand.put("kind", "nullspace");
                cand.put("description",
                        "design matrix null direction " + i + ": M is not identifiable along it");
                cand.put("vector", vectorList(dir));
                cand.put("norm", round(LinAlg.dot(dir, dir)));
                cand.put("fingerprint", Conventions.fingerprint(ALGORITHM_VERSION,
                        view.ruleVersion, view.ruleParams, inputVersions,
                        view.stokesOrder, view.rotationSign, "nullvec_" + i));
                c.candidates.add(cand);
            }
        }
    }

    private void addDecompositionCandidate(Computation c, AnalysisView view,
                                           Map<String, String> inputVersions,
                                           String key, Decomposition.Result r) {
        Map<String, Object> cand = new LinkedHashMap<>();
        cand.put("candidateKey", key);
        cand.put("kind", "decomposition");
        cand.put("valid", r.valid);
        cand.put("diagnostic", r.diagnostic);
        cand.put("roundTripResidual", round(r.roundTripResidual));
        List<Map<String, Object>> factors = new ArrayList<>();
        for (Map.Entry<String, Decomposition.Factor> en : r.factors.entrySet()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", en.getKey());
            fm.put("matrix", matrixList(en.getValue().matrix));
            fm.put("scalars", en.getValue().scalars);
            factors.add(fm);
        }
        cand.put("factors", factors);
        cand.put("fingerprint", Conventions.fingerprint(ALGORITHM_VERSION, view.ruleVersion,
                view.ruleParams, inputVersions, view.stokesOrder, view.rotationSign, key));
        c.candidates.add(cand);
    }

    private Models.SourceVersion version(AnalysisView view, String role) {
        Integer no = view.versionOf(role);
        if (no == null) {
            throw new IllegalStateException("role " + role + " is not bound at this event");
        }
        Models.SourceVersion v = db.getSourceVersion(role, no);
        if (v == null) {
            throw new IllegalStateException("missing source " + role + " v" + no);
        }
        return v;
    }

    private double[] applyInstrumentToState(SourceParser.Parsed instr, double[] s,
                                            boolean calibrate, boolean analyzer) {
        if (!calibrate) {
            return s;
        }
        double[][] w = analyzer ? instr.wAnalyser : instr.wGenerator;
        return org.polarroom.core.LinAlg.multiply(w, s);
    }

    private static double[][] adaptCovariance(double[][] raw, boolean[] used) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < used.length; i++) {
            if (used[i]) {
                idx.add(i);
            }
        }
        int m = idx.size();
        double[][] out = new double[m][m];
        if (raw != null && raw.length >= used.length) {
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < m; j++) {
                    out[i][j] = raw[idx.get(i)][idx.get(j)];
                }
            }
        }
        for (int i = 0; i < m; i++) {
            if (out[i][i] <= 0) {
                out[i][i] = 1.0;
            }
        }
        return out;
    }

    private static double average(java.util.Collection<Double> v) {
        double s = 0;
        for (double d : v) {
            s += d;
        }
        return v.isEmpty() ? 0 : s / v.size();
    }

    private static double round(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return d;
        }
        return Math.round(d * 1e10) / 1e10;
    }

    private static List<List<Double>> matrixList(double[][] m) {
        List<List<Double>> out = new ArrayList<>();
        for (double[] row : m) {
            List<Double> l = new ArrayList<>();
            for (double v : row) {
                l.add(round(v));
            }
            out.add(l);
        }
        return out;
    }

    private static List<Double> vectorList(double[] v) {
        List<Double> l = new ArrayList<>();
        for (double d : v) {
            l.add(round(d));
        }
        return l;
    }
}
