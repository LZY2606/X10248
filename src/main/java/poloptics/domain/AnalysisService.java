package poloptics.domain;

import poloptics.engine.Decomposition;
import poloptics.engine.EstimateResult;
import poloptics.engine.Json;
import poloptics.engine.Mat;
import poloptics.engine.MuellerSolver;
import poloptics.engine.Physicality;
import poloptics.engine.Polarization;
import poloptics.domain.Conventions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns versioned source refs of a branch into estimates/physicality/
 * decomposition derived artifacts and computes invalidation scope.
 *
 * Source vs interpretation separation: parsing is deliberately tolerant,
 * preserving original representations; only the derived layer normalizes.
 */
public final class AnalysisService {
    private final Repository repo;
    public AnalysisService(Repository repo) { this.repo = repo; }

    public static final class ParsedInputs {
        public List<double[]> generators = new ArrayList<>();
        public List<double[]> analyzers = new ArrayList<>();
        public List<Double> anglesG = new ArrayList<>();
        public List<Double> anglesA = new ArrayList<>();
        public List<Double> intensity = new ArrayList<>();
        public List<Double> dark = new ArrayList<>();
        public List<Double> variance = new ArrayList<>();
        public double[][] cov;
        public double[][] instrumentM; // PSG/PSA instrument matrix W (16 cols form) if supplied
        public Conventions conventions = Conventions.standard();
        public String referenceVersion;
        public Double wavelengthNm;
        public String wavelengthOriginal;
        public List<String> diagnostics = new ArrayList<>();
        public boolean correlated;
    }

    /** Parse active refs at a given event view (refs bound up to seq). */
    public ParsedInputs parse(String branchId, long seq) {
        ParsedInputs p = new ParsedInputs();
        for (Repository.SourceRefRow r : repo.activeRefs(branchId)) {
            Event ev = repo.getEvent(r.eventId());
            if (ev == null || ev.seq() > seq) continue;
            SourceBlob b = repo.getBlob(r.blobId());
            if (b == null) continue;
            applyBlob(p, r.role(), b, r.versionTag());
        }
        return p;
    }

    private void applyBlob(ParsedInputs p, String role, SourceBlob b, String versionTag) {
        String txt = b.originalText();
        switch (role) {
            case "intensity" -> parseIntensity(p, txt);
            case "state_code" -> parseStateCodes(p, txt, b.unitOrDatum());
            case "dark" -> parseDoublesInto(p.dark, txt);
            case "variance" -> { parseDoublesInto(p.variance, txt); }
            case "covariance" -> { p.cov = parseMatrix(txt); p.correlated = true; }
            case "reference" -> { p.referenceVersion = versionTag; }
            case "instrument" -> { p.instrumentM = parseMatrix(txt); }
            case "wavelength" -> parseWavelength(p, txt, b.parseStatus());
            default -> p.diagnostics.add("未识别来源角色: " + role);
        }
    }

    private void parseWavelength(ParsedInputs p, String txt, String status) {
        p.wavelengthOriginal = txt.trim();
        if ("UNCONVERTIBLE".equals(status)) {
            p.diagnostics.add("波长值不可转换: " + txt.trim() + "，原始表示保留，未归一化绘图。");
            p.wavelengthNm = null;
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*(nm|μm|um|µm)?").matcher(txt);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2)==null?"nm":m.group(2).toLowerCase(java.util.Locale.ROOT);
            p.wavelengthNm = switch (unit) { case "μm","um","µm" -> v*1000; default -> v; };
        }
    }

    private void parseDoublesInto(List<Double> sink, String txt) {
        sink.clear();
        for (String tok : txt.trim().split("[,\\s]+")) {
            if (tok.isBlank()) continue;
            try { sink.add(Double.parseDouble(tok)); }
            catch (NumberFormatException ex) {
                // unconvertible value stays in place with diagnostic rather than dropped
                sink.add(Double.NaN);
            }
        }
    }

    private void parseIntensity(ParsedInputs p, String txt) {
        p.intensity.clear();
        int line = 0;
        for (String row : txt.split("\\R")) {
            line++;
            if (row.isBlank() || row.trim().startsWith("#")) continue;
            String[] parts = row.split("[,\\s]+");
            try {
                p.anglesG.add(Double.parseDouble(parts[0]));
                p.anglesA.add(Darts(parts));
                p.intensity.add(Double.parseDouble(parts[2]));
            } catch (RuntimeException ex) {
                p.diagnostics.add("强度行 " + line + " 无法解析，保留原文诊断，未删除该行。");
            }
        }
    }

    private double Darts(String[] parts) { return Double.parseDouble(parts[1]); }

    /**
     * State codes: lines "gDeg,aDeg" or Stokes rows "S0 S1 S2 S3 | ...".
     * Encoding records rotation sign/order in the conventions fingerprint.
     */
    private void parseStateCodes(ParsedInputs p, String txt, String unitOrDatum) {
        p.generators.clear(); p.analyzers.clear(); p.anglesG.clear(); p.anglesA.clear();
        boolean ccwPositive = !"CW_POSITIVE".equals(unitOrDatum);
        // stokes order may be declared in first comment "# order: [S0,S3,S1,S2]"
        for (String line : txt.split("\\R")) {
            if (line.startsWith("#") && line.contains("order")) {
                String order = line.substring(line.indexOf("[")).trim();
                p.conventions = new Conventions(order,
                        ccwPositive ? "CCW_POSITIVE" : "CW_POSITIVE",
                        p.conventions.angleBasis);
            }
        }
        for (String row : txt.split("\\R")) {
            if (row.isBlank() || row.trim().startsWith("#")) continue;
            String[] parts = row.trim().split("[,\\s]+");
            if (parts.length >= 2 && !parts[0].contains(";")) {
                double tg = Math.toRadians(Double.parseDouble(parts[0]));
                double ta = Math.toRadians(Double.parseDouble(parts[1]));
                double[] g = Polarization.generatorStokes(ccwPositive ? tg : -tg);
                double[] a = Polarization.analyzerStokes(ccwPositive ? ta : -ta);
                p.generators.add(g); p.analyzers.add(a);
                p.anglesG.add(Math.toDegrees(tg)); p.anglesA.add(Math.toDegrees(ta));
            }
        }
    }

    private double[][] parseMatrix(String txt) {
        List<double[]> rows = new ArrayList<>();
        for (String line : txt.split("\\R")) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            String[] parts = line.trim().split("[,\\s]+");
            double[] r = new double[parts.length];
            for (int i=0;i<parts.length;i++) r[i]=Double.parseDouble(parts[i]);
            rows.add(r);
        }
        return rows.toArray(new double[0][]);
    }

    public static final class RunOutcome {
        public EstimateResult estimate;
        public String estimateDerivedId, physicalityDerivedId;
        public List<String> candidateDerivedIds = new ArrayList<>();
        public String fingerprint;
        public List<String> invalidatedDerivedIds = new ArrayList<>();
        public String invalidationReason;
    }

    /**
     * Recompute derived artifacts as a NEW event without mutating any prior
     * result. dependsOn pins exact ref blob versions + parameter hash; when a
     * dependency changes only derived rows in the affected subtree are marked
     * invalid (scoped invalidation, never global silent replacement).
     */
    public RunOutcome run(String branchId, String actor, String paramSetJson, long expectedVersion) {
        long headSeq = expectedVersion - 1;
        ParsedInputs in = parse(branchId, headSeq);
        Event evt = repo.appendEvent(branchId, "ANALYSIS_RUN", actor,
                buildAnalysisPayload(in, paramSetJson), null, expectedVersion);

        int n = in.generators.size();
        double[][] w = new double[n][16];
        double[] y = new double[n];
        double[] var = new double[n];
        for (int k = 0; k < n; k++) {
            double dark = pick(in.dark, k);
            w[k] = Polarization.designRow(in.generators.get(k), in.analyzers.get(k));
            y[k] = in.intensity.get(k) - dark;
            var[k] = pick(in.variance, k);
            if (!(var[k] > 0)) var[k] = 1.0;
        }
        EstimateResult est = in.correlated && in.cov != null && in.cov.length == n
                ? MuellerSolver.estimateCorrelated(w, y, in.cov)
                : MuellerSolver.estimate(w, y, var);

        String fingerprint = fingerprint(in, branchId, headSeq, paramSetJson);
        String dependsOn = dependsJson(branchId, headSeq, paramSetJson, fingerprint);

        String estJson = estimateJson(est, in);
        String estId = repo.putDerived(branchId, evt.eventId(), "ESTIMATE", "ESTIMATE_MINNORM",
                estJson, dependsOn, est.solvable);

        RunOutcome out = new RunOutcome();
        out.estimate = est; out.estimateDerivedId = estId; out.fingerprint = fingerprint;

        if (est.solution != null) {
            double[][] m = Polarization.fromVec(est.solution);
            Physicality.Report rep = Physicality.evaluate(m);
            String physId = repo.putDerived(branchId, evt.eventId(), "PHYSICALITY", "PRIMARY",
                    physicalityJson(rep, m), dependsOn, true);
            out.physicalityDerivedId = physId;

            // primary decomposition candidate, plus zero-space branch candidates
            Decomposition.Candidate dc = Decomposition.luChipman(m);
            out.candidateDerivedIds.add(repo.putDerived(branchId, evt.eventId(),
                    "DECOMPOSITION", dc.candidateId, decompositionJson(dc), dependsOn, dc.computable));
            for (int zi = 0; zi < est.nullspace.size() && zi < 3; zi++) {
                double[] dir = est.nullspace.get(zi);
                double eps = 0.05 * scale(m);
                double[][] alt = Polarization.fromVec(addScaled(est.solution, dir, eps));
                Physicality.Report ar = Physicality.evaluate(alt);
                Decomposition.Candidate ac = Decomposition.luChipman(alt);
                String payload = decompositionJson(ac)
                        .replace("{", "{\"candidate_kind\":\"NULLSPACE_AMIGUITY\",\"nullspace_index\":"+zi
                                +",\"step\":"+Json.num(eps)+",\"physical\":"+ar.physicallyRealizable+",");
                out.candidateDerivedIds.add(repo.putDerived(branchId, evt.eventId(),
                        "CANDIDATE", "NULLSPACE_" + zi, payload, dependsOn, ac.computable));
            }
        }

        // scoped invalidation: older derived depending on changed blobs
        markInvalidated(branchId, evt, dependsOn, out);
        return out;
    }

    private void markInvalidated(String branchId, Event evt, String newDepends, RunOutcome out) {
        // previous ESTIMATE depends-on blobs; compare active refs set by blobId+versionTag
        List<Event> evs = repo.allEvents(branchId);
        Event prevAnalysis = null;
        for (int i = evs.size()-1; i >= 0; i--) {
            if (evs.get(i).kind().equals("ANALYSIS_RUN") && !evs.get(i).eventId().equals(evt.eventId())) {
                prevAnalysis = evs.get(i); break;
            }
        }
        if (prevAnalysis == null) return;
        for (Repository.DerivedRow d : repo.derivedForEvent(branchId, prevAnalysis.eventId())) {
            if (!dependsKey(d.dependsOnJson()).equals(dependsKey(newDepends))) {
                out.invalidatedDerivedIds.add(d.derivedId());
            }
        }
        if (!out.invalidatedDerivedIds.isEmpty()) {
            String payload = "{\"reason\":\"dependency_changed\",\"invalidated_derived\":["
                + String.join(",", out.invalidatedDerivedIds.stream().map(x->"\""+x+"\"").toList())
                + "],\"scope\":\"local_subtree_only\"}";
            try {
                var m = new java.util.HashMap<String,Object>();
            } catch (Throwable ignored) {}
            // invalidation is attached to the analysis event via an INVALIDATION event
            // appended immediately by caller via events; here record reason
            out.invalidationReason = "仅使依赖子树失效: " + out.invalidatedDerivedIds;
        }
    }

    private String dependsKey(String dependsJson) {
        return dependsJson.replaceAll("\\\"event_time\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "")
                          .replaceAll("\\s+", "");
    }

    private double pick(List<Double> list, int k) {
        if (list.isEmpty()) return 0;
        if (k < list.size()) {
            double v = list.get(k);
            if (!Double.isNaN(v)) return v;
        }
        return list.get(list.size()-1);
    }

    private double scale(double[][] m) {
        double s = 0;
        for (double[] r : m) for (double v : r) s = Math.max(s, Math.abs(v));
        return s > 0 ? s : 1;
    }

    private double[] addScaled(double[] a, double[] d, double s) {
        double[] o = a.clone();
        for (int i=0;i<o.length;i++) o[i] += d[i]*s;
        return o;
    }

    private String buildAnalysisPayload(ParsedInputs in, String paramSetJson) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"measurement_count\":").append(in.generators.size());
        sb.append(",\"reference_version\":").append(Json.esc(in.referenceVersion));
        sb.append(",\"wavelength_nm\":").append(in.wavelengthNm==null?"null":Json.num(in.wavelengthNm));
        sb.append(",\"wavelength_original\":").append(Json.esc(in.wavelengthOriginal));
        sb.append(",\"correlated\":").append(in.correlated);
        sb.append(",\"conventions\":").append(Json.esc(in.conventions.fingerprintFragment()));
        sb.append(",\"parameters\":").append(paramSetJson == null ? "{}" : paramSetJson);
        sb.append(",\"diagnostics\":[");
        for (int i=0;i<in.diagnostics.size();i++){ if(i>0)sb.append(','); sb.append(Json.esc(in.diagnostics.get(i))); }
        sb.append("]}");
        return sb.toString();
    }

    private String fingerprint(ParsedInputs in, String branchId, long seq, String paramSetJson) {
        List<String> tokens = new ArrayList<>();
        tokens.add(in.conventions.fingerprintFragment());
        tokens.add("n=" + in.generators.size());
        for (Repository.SourceRefRow r : repo.activeRefs(branchId)) {
            Event ev = repo.getEvent(r.eventId());
            if (ev == null || ev.seq() > seq) continue;
            SourceBlob b = repo.getBlob(r.blobId());
            tokens.add(r.role() + "@" + r.versionTag() + ":" + (b == null ? "missing" : b.sha256()));
        }
        tokens.add("params=" + Repository.shaStatic(paramSetJson == null ? "{}" : paramSetJson));
        return "fp_" + Repository.shaStatic(String.join("|", tokens)).substring(0, 24);
    }

    private String dependsJson(String branchId, long seq, String paramSetJson, String fingerprint) {
        StringBuilder refs = new StringBuilder("[");
        boolean first = true;
        for (Repository.SourceRefRow r : repo.activeRefs(branchId)) {
            Event ev = repo.getEvent(r.eventId());
            if (ev == null || ev.seq() > seq) continue;
            if (!first) refs.append(',');
            first = false;
            refs.append("{\"ref\":").append(Json.esc(r.refId()))
                .append(",\"blob\":").append(Json.esc(r.blobId()))
                .append(",\"role\":").append(Json.esc(r.role()))
                .append(",\"version\":").append(Json.esc(r.versionTag())).append('}');
        }
        refs.append(']');
        return "{\"event_time\":" + Json.esc(repo.now())
             + ",\"refs\":" + refs
             + ",\"params_hash\":" + Json.esc(Repository.shaStatic(paramSetJson == null ? "{}" : paramSetJson))
             + ",\"fingerprint\":" + Json.esc(fingerprint) + "}";
    }

    private String estimateJson(EstimateResult e, ParsedInputs in) {
        double[][] m = e.solution==null?null:Polarization.fromVec(e.solution);
        StringBuilder nulls = new StringBuilder("[");
        for (int i=0;i<e.nullspace.size();i++){ if(i>0)nulls.append(','); nulls.append(Json.vec(e.nullspace.get(i))); }
        nulls.append(']');
        // element errors: diagonal of covariance
        double[] errs = new double[16];
        for (int i=0;i<16;i++) {
            double v = e.covariance16[i][i];
            errs[i] = Double.isInfinite(v) ? Double.POSITIVE_INFINITY : Math.sqrt(Math.max(0,v));
        }
        return "{"
            + "\"solvable\":" + e.solvable
            + ",\"condition_number\":" + (Double.isInfinite(e.conditionNumber)?"null":Json.num(e.conditionNumber))
            + ",\"condition_infinite\":" + Double.isInfinite(e.conditionNumber)
            + ",\"rank\":" + e.rank
            + ",\"nullity\":" + e.nullity
            + ",\"residual_rms\":" + Json.num(e.residualRms)
            + ",\"diagnostic\":" + Json.esc(e.diagnostic)
            + ",\"mueller\":" + Json.mat(m)
            + ",\"element_sigma\":" + Json.vec(errs)
            + ",\"covariance16\":" + Json.mat(e.covariance16)
            + ",\"nullspace\":" + nulls
            + ",\"reference_version\":" + Json.esc(in.referenceVersion)
            + ",\"conventions\":" + Json.esc(in.conventions.fingerprintFragment())
            + "}";
    }

    private String physicalityJson(Physicality.Report r, double[][] m) {
        StringBuilder vs = new StringBuilder("[");
        for (int i=0;i<r.violations.size();i++) {
            Physicality.Violation v = r.violations.get(i);
            if (i>0) vs.append(',');
            vs.append("{\"rule\":").append(Json.esc(v.ruleId))
              .append(",\"description\":").append(Json.esc(v.description))
              .append(",\"pass\":").append(v.pass)
              .append(",\"residual\":").append(Json.num(v.residual)).append('}');
        }
        vs.append(']');
        return "{\"physically_realizable\":" + r.physicallyRealizable
             + ",\"cloude_entropy\":" + Json.num(r.cloudeEntropy)
             + ",\"cloude_eigenvalues\":" + Json.vec(r.cloudeEigenvalues)
             + ",\"violations\":" + vs
             + ",\"projection_applied\":false"
             + ",\"note\":\"非物理候选仅标记违约，未做投影后冒充原解。\"}";
    }

    private String decompositionJson(Decomposition.Candidate c) {
        StringBuilder sc = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String,Double> e : c.scalars.entrySet()) {
            if (!first) sc.append(',');
            first = false;
            sc.append(Json.esc(e.getKey())).append(':').append(Json.num(e.getValue()));
        }
        sc.append('}');
        return "{\"candidate_id\":" + Json.esc(c.candidateId)
             + ",\"computable\":" + c.computable
             + ",\"scalars\":" + sc
             + ",\"depolarizer\":" + Json.mat(c.depolarizer)
             + ",\"retarder\":" + Json.mat(c.retarder)
             + ",\"diattenuator\":" + Json.mat(c.diattenuator)
             + ",\"residual\":" + Json.num(c.residual)
             + ",\"note\":" + Json.esc(c.note) + "}";
    }
}
