package polarmueller.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import polarmueller.core.*;

import java.util.*;

/** Joins versioned sources into derivation-layer artifacts with diagnostics. */
public final class DerivationService {
    private final Store store;

    public DerivationService(Store store) { this.store = store; }

    public record Params(String stokesOrder, String rotationConvention) {
        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("stokes_order", stokesOrder);
            m.put("rotation_convention", rotationConvention);
            return m;
        }
    }

    /* ---------- normalization (derived layer only; raw bytes stay untouched) ---------- */

    static int[] permutation(String fromOrder, String toOrder) {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) p[i] = fromOrder.indexOf(toOrder.charAt(i));
        return p;
    }

    /** Canonicalize a Stokes vector into the requested order/convention. */
    static double[] canonicalStokes(double[] v, String declaredOrder, String declaredRotation,
                                    String wantOrder, String wantRotation, List<String> diagnostics) {
        double[] out = v.clone();
        if (!declaredOrder.equals(wantOrder)) {
            int[] p = permutation(declaredOrder, wantOrder);
            double[] perm = new double[4];
            for (int i = 0; i < 4; i++) {
                if (p[i] < 0) {
                    diagnostics.add("unconvertible_stokes_component:" + wantOrder.charAt(i)
                            + " not present in declared order " + declaredOrder + "; value left in place");
                    perm[i] = v[i];
                } else perm[i] = v[p[i]];
            }
            out = perm;
        }
        if (!declaredRotation.equals(wantRotation)) {
            // handedness flip: U and V change sign between conventions
            out[2] = -out[2];
            out[3] = -out[3];
        }
        return out;
    }

    /** Wavelength normalization to nm; unconvertible units stay in place with a diagnostic. */
    static double wavelengthNm(double value, String units, List<String> diagnostics) {
        return switch (units == null ? "" : units) {
            case "nm" -> value;
            case "angstrom" -> value / 10.0;
            case "um" -> value * 1000.0;
            default -> {
                diagnostics.add("unconvertible_unit:wavelength units '" + units
                        + "' cannot be converted to nm; value left in original position");
                yield value;
            }
        };
    }

    /* ---------- design report ---------- */

    public String designReport(String branch, long expectedHead, Params params) {
        Store.SourceDoc enc = must("state_encoding");
        List<String> diagnostics = new ArrayList<>();
        JsonNode encJson = parse(enc);
        double[][][] states = states(encJson, enc.frame(), params, diagnostics);
        int n = states.length;
        org.ejml.data.DMatrixRMaj w = new org.ejml.data.DMatrixRMaj(n, 16);
        for (int i = 0; i < n; i++) {
            double[] row = MatrixOps.kron(states[i][1], states[i][0]);
            for (int j = 0; j < 16; j++) w.set(i, j, row[j]);
        }
        MatrixOps.Svd svd = MatrixOps.svd(w);
        ObjectNode result = Store.JSON.createObjectNode();
        result.put("condition", svd.condition());
        result.put("rank", svd.rank());
        ArrayNode sv = result.putArray("singular_values");
        for (double s : svd.singularValues()) sv.add(s);
        result.put("n_states", n);
        result.put("wavelength_nm", wavelengthNm(latestWavelength(), latestWavelengthUnits(), diagnostics));

        List<Fingerprint.SourceRef> refs = List.of(ref(enc));
        String fp = Fingerprint.compute("design-report/v1", params.stokesOrder(),
                params.rotationConvention(), refs, params.asMap());
        ObjectNode judgment = Store.JSON.createObjectNode();
        judgment.put("rule", "conventions_in_fingerprint");
        judgment.put("stokes_order", params.stokesOrder());
        judgment.put("rotation_convention", params.rotationConvention());
        if (svd.rank() < 16 || !(svd.condition() < MuellerEstimation.COND_LIMIT)) {
            judgment.put("rule", "near_singular_design_not_inverted");
            judgment.put("verdict", "refused_inversion");
            judgment.put("condition", svd.condition());
            judgment.put("rank", svd.rank());
        }
        String status = (svd.rank() < 16 || !(svd.condition() < MuellerEstimation.COND_LIMIT))
                ? "singular_design" : "ok";
        return store.putDerivation(branch, expectedHead, "design_report",
                tokens(refs), params.asMap(), fp, status, result, diagnostics, judgment);
    }

    /* ---------- mueller estimate ---------- */

    public String muellerEstimate(String branch, long expectedHead, Params params) {
        Store.SourceDoc intensity = must("raw_intensity");
        Store.SourceDoc enc = must("state_encoding");
        Store.SourceDoc dark = must("dark_field");
        Store.SourceDoc inst = must("instrument_matrix");
        Store.SourceDoc cov = must("covariance");
        Store.SourceDoc wave = must("wavelength");
        Store.SourceDoc ref = must("reference_sample");

        List<String> diagnostics = new ArrayList<>();
        JsonNode encJson = parse(enc);
        double[][][] states = states(encJson, enc.frame(), params, diagnostics);
        int n = states.length;
        double[][] gen = new double[n][4];
        double[][] ana = new double[n][4];
        for (int i = 0; i < n; i++) { gen[i] = states[i][0]; ana[i] = states[i][1]; }

        double[] inten = doubles(parse(intensity).get("values"));
        double[] darkV = doubles(parse(dark).get("values"));
        double[][] covM = covariance(parse(cov), n);
        double[][] instM = matrix(parse(inst).get("matrix"));
        double wl = wavelengthNm(parse(wave).get("value").asDouble(), wave.units(), diagnostics);
        diagnostics.add("wavelength_nm=" + wl);
        diagnostics.add("reference_sample=" + ref.id() + "@" + ref.version());

        MuellerEstimation.Result r = MuellerEstimation.estimate(
                new MuellerEstimation.Input(gen, ana, inten, darkV, covM, instM));
        diagnostics.addAll(r.diagnostics());

        ObjectNode result = Store.JSON.createObjectNode();
        result.put("status", r.status());
        result.put("condition", r.condition());
        result.put("rank", r.rank());
        result.put("min_coherency_eigen", r.minCoherencyEigen());
        if (r.m() != null) {
            ArrayNode m = result.putArray("m");
            for (double v : r.m()) m.add(v);
            ArrayNode e = result.putArray("errors");
            for (double v : r.errors()) e.add(v);
            ArrayNode ce = result.putArray("coherency_eigenvalues");
            for (double v : Physicality.coherencyEigenvalues(r.m())) ce.add(v);
        }
        ArrayNode sv = result.putArray("singular_values");
        for (double s : r.singularValues()) sv.add(s);

        List<Fingerprint.SourceRef> refs = List.of(ref(intensity), ref(enc), ref(dark),
                ref(inst), ref(cov), ref(wave), ref(ref));
        String fp = Fingerprint.compute(MuellerEstimation.RULE_VERSION, params.stokesOrder(),
                params.rotationConvention(), refs, params.asMap());

        ObjectNode judgment = Store.JSON.createObjectNode();
        judgment.put("rule", "conventions_in_fingerprint");
        judgment.put("stokes_order", params.stokesOrder());
        judgment.put("rotation_convention", params.rotationConvention());
        if (r.status().equals("singular_design")) {
            judgment.put("rule", "near_singular_design_not_inverted");
            judgment.put("verdict", "refused_inversion");
            judgment.put("condition", r.condition());
            judgment.put("rank", r.rank());
        } else if (r.status().equals("nonphysical")) {
            judgment.put("rule", "nonphysical_candidate_flagged_not_projected");
            judgment.put("verdict", "flagged_not_projected");
            judgment.put("min_coherency_eigen", r.minCoherencyEigen());
        }
        return store.putDerivation(branch, expectedHead, "mueller_estimate",
                tokens(refs), params.asMap(), fp, r.status(), result, diagnostics, judgment);
    }

    /* ---------- decomposition ---------- */

    public String decompose(String branch, long expectedHead, String estimateId, Params params) {
        Store.Derivation est = store.derivation(estimateId);
        if (est == null || !est.kind().equals("mueller_estimate"))
            throw new IllegalArgumentException("no mueller_estimate " + estimateId);
        if (est.result().get("m") == null)
            throw new IllegalStateException("estimate " + estimateId + " has no matrix (singular design)");
        double[] m = new double[16];
        JsonNode mj = est.result().get("m");
        for (int i = 0; i < 16; i++) m[i] = mj.get(i).asDouble();
        boolean nonPhysical = est.status().equals("nonphysical");
        List<Decomposition.Candidate> cands = Decomposition.decompose(m, nonPhysical);

        ObjectNode result = Store.JSON.createObjectNode();
        ArrayNode arr = result.putArray("candidates");
        for (Decomposition.Candidate c : cands) {
            ObjectNode cn = arr.addObject();
            cn.put("name", c.name());
            cn.put("status", c.status());
            cn.put("diattenuation", c.diattenuation());
            cn.put("residual_norm", c.residualNorm());
            cn.set("diattenuator", Store.JSON.valueToTree(c.diattenuator()));
            cn.set("retarder", Store.JSON.valueToTree(c.retarder()));
            cn.set("depolarizer", Store.JSON.valueToTree(c.depolarizer()));
            ArrayNode dg = cn.putArray("diagnostics");
            c.diagnostics().forEach(dg::add);
        }
        List<String> inputs = new ArrayList<>();
        inputs.add("der:" + estimateId);
        inputs.addAll(est.inputs());
        String fp = Fingerprint.compute(Decomposition.RULE_VERSION, params.stokesOrder(),
                params.rotationConvention(),
                List.of(new Fingerprint.SourceRef("der:" + estimateId, 1, est.fingerprint())),
                params.asMap());
        ObjectNode judgment = Store.JSON.createObjectNode();
        judgment.put("rule", nonPhysical
                ? "nonphysical_candidate_flagged_not_projected" : "multiple_candidates_retained");
        judgment.put("verdict", nonPhysical ? "flagged_not_projected" : "candidates_kept");
        judgment.put("candidate_count", cands.size());
        List<String> diagnostics = new ArrayList<>();
        if (nonPhysical) diagnostics.add("decomposition of a flagged non-physical estimate; shown for inspection only");
        return store.putDerivation(branch, expectedHead, "decomposition",
                inputs, params.asMap(), fp, nonPhysical ? "flagged" : "ok", result, diagnostics, judgment);
    }

    /* ---------- helpers ---------- */

    private Store.SourceDoc must(String kind) {
        Store.SourceDoc d = store.latestSource(kind);
        if (d == null) throw new IllegalStateException("missing source kind " + kind);
        return d;
    }

    private static JsonNode parse(Store.SourceDoc d) {
        try { return Store.JSON.readTree(new String(d.raw(), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new RuntimeException("cannot parse source " + d.id(), e); }
    }

    private static Fingerprint.SourceRef ref(Store.SourceDoc d) {
        return new Fingerprint.SourceRef(d.id(), d.version(), d.contentHash());
    }

    private static List<String> tokens(List<Fingerprint.SourceRef> refs) {
        return refs.stream().map(Fingerprint.SourceRef::token).toList();
    }

    private static double[] doubles(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).asDouble();
        return out;
    }

    private static double[][] matrix(JsonNode arr) {
        double[][] out = new double[arr.size()][];
        for (int i = 0; i < arr.size(); i++) out[i] = doubles(arr.get(i));
        return out;
    }

    private static double[][] covariance(JsonNode cov, int n) {
        if (cov.has("matrix")) return matrix(cov.get("matrix"));
        double[] diag = doubles(cov.get("diagonal"));
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = diag[i];
        return m;
    }

    private double[][][] states(JsonNode encJson, String frame, Params params, List<String> diagnostics) {
        String declaredOrder = "IQUV";
        String declaredRotation = "optical_ccw";
        if (frame != null) {
            for (String part : frame.split(";")) {
                if (part.startsWith("stokes:")) declaredOrder = part.substring(7);
                if (part.startsWith("rotation:")) declaredRotation = part.substring(9);
            }
        }
        JsonNode states = encJson.get("states");
        double[][][] out = new double[states.size()][2][4];
        for (int i = 0; i < states.size(); i++) {
            out[i][0] = canonicalStokes(doubles(states.get(i).get("generator")),
                    declaredOrder, declaredRotation, params.stokesOrder(), params.rotationConvention(), diagnostics);
            out[i][1] = canonicalStokes(doubles(states.get(i).get("analyzer")),
                    declaredOrder, declaredRotation, params.stokesOrder(), params.rotationConvention(), diagnostics);
        }
        return out;
    }

    private double latestWavelength() {
        return parse(must("wavelength")).get("value").asDouble();
    }

    private String latestWavelengthUnits() {
        return must("wavelength").units();
    }
}
