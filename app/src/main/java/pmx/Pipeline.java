package pmx;

import java.util.*;

import static pmx.LinAlg.*;

/**
 * Derived (non-destructive) layer. Takes an immutable snapshot of the seven
 * resolved source-layer versions and produces:
 *   - measurement design + condition number / rank / nullspace
 *   - dark-corrected, instrument- and reference-corrected observations
 *   - generalized least-squares Mueller candidate with covariance
 *   - Cloude physical-realizability residuals
 *   - Lu-Chipman and Cloude spectral decomposition candidates
 * Near-singular designs are never force-inverted; non-physical candidates are
 * flagged, never projected and passed off as the original.
 */
public final class Pipeline {
    private Pipeline() {}

    public static final class Resolved {
        public final Map<String, Sources.Layer> layers = new LinkedHashMap<>();
        public Sources.Layer get(String k) { return layers.get(k); }
    }

    public static final class Result {
        public Map<String, Object> view = new LinkedHashMap<>();
        public List<String> diagnostics = new ArrayList<>();
    }

    public static Result run(Resolved in) {
        Result r = new Result();
        Map<String, Object> v = r.view;
        Sources.Layer gen = in.get("state");
        Sources.Layer intLayer = in.get("intensity");
        Sources.Layer dark = in.get("dark");
        Sources.Layer cov = in.get("covariance");
        Sources.Layer wave = in.get("wavelength");
        Sources.Layer inst = in.get("instrument");
        Sources.Layer ref = in.get("reference");

        String order = gen.stokesOrder;
        int rotSign = gen.rotationSign;
        String fingerprint = fingerprint(in);
        v.put("fingerprint", fingerprint);
        v.put("stokesOrder", order);
        v.put("rotationSign", rotSign);
        v.put("basis", gen.basis);

        // ---- states: two state layers may share columns; distinguish by role ----
        int n = intLayer.rows.size();
        List<Polar.State> gens = new ArrayList<>();
        List<Polar.State> anas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> row = intLayer.rows.get(i);
            String gAngle = pick(row, "gen_angle", "generator_angle", "gen.angle");
            String gKind = pick(row, "gen_kind", "generator_kind", "gen.optic");
            String aAngle = pick(row, "ana_angle", "analyzer_angle", "ana.angle");
            String aKind = pick(row, "ana_kind", "analyzer_kind", "ana.optic");
            if (gKind == null && gen != null && i < gen.rows.size()) {
                gKind = pick(gen.rows.get(i), "kind", "optic");
                gAngle = pick(gen.rows.get(i), "angle_deg", "angle");
            }
            if (aKind == null && gen != null && i < gen.rows.size()) {
                aKind = pick(gen.rows.get(i), "ana_kind", "analyzer_kind");
                aAngle = pick(gen.rows.get(i), "ana_angle", "analyzer_angle");
            }
            gens.add(Polar.parseState(i, pick(row, "id", "label", "row"), nz(gKind, "LP"), gAngle, rotSign));
            anas.add(Polar.parseState(i, pick(row, "id", "label", "row"), nz(aKind, "LP"), aAngle, rotSign));
        }

        // ---- intensities, dark, covariance ----
        double[] raw = new double[n], dk = new double[n], y = new double[n];
        double[] sigma = new double[n];
        boolean[] validIntensity = new boolean[n];
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> row = intLayer.rows.get(i);
            List<String> dg = new ArrayList<>();
            raw[i] = Sources.num(pick(row, "intensity", "counts", "i"), dg);
            validIntensity[i] = !Double.isNaN(raw[i]);
            if (!validIntensity[i]) dropped.add("row " + i + ": " + dg);
            double darkVal = 0;
            if (dark != null) {
                String token = i < dark.rows.size() ? pick(dark.rows.get(i), "dark", "dark_counts", "offset") : null;
                if (token != null) {
                    List<String> dd = new ArrayList<>();
                    darkVal = Sources.num(token, dd);
                    if (Double.isNaN(darkVal)) { darkVal = 0; dropped.add("dark row " + i + ": " + dd); }
                }
            }
            dk[i] = darkVal;
            y[i] = raw[i] - darkVal;
            sigma[i] = 1;
        }

        // covariance: diagonal file or dense block; correlation enters the same fingerprint
        double[][] covMat = buildCovariance(cov, n, sigma);
        boolean correlated = false;
        if (covMat != null)
            for (int i = 0; i < n && !correlated; i++)
                for (int j = i + 1; j < n; j++)
                    if (Math.abs(covMat[i][j]) > 1e-15) correlated = true;

        // ---- design (uses canonical order internally; order is fingerprinted) ----
        Polar.Design design = Polar.buildDesign(gens, anas);
        // rows with unconvertible intensity excluded from fitting but retained
        int fit = 0;
        for (boolean b : design.rowValid) if (b) fit++;
        for (boolean b : validIntensity) if (!b) fit--;
        boolean[] useRow = new boolean[n];
        int used = 0;
        for (int i = 0; i < n; i++) { useRow[i] = design.rowValid[i] && validIntensity[i]; if (useRow[i]) used++; }
        double[][] X = new double[used][16];
        double[] yy = new double[used];
        double[][] cc = covMat == null ? null : new double[used][used];
        int ui = 0;
        for (int i = 0; i < n; i++) if (useRow[i]) {
            System.arraycopy(design.x[i], 0, X[ui], 0, 16);
            yy[ui] = y[i];
            if (cc != null) { int uj = 0; for (int j = 0; j < n; j++) if (useRow[j]) { cc[ui][uj] = covMat[i][j]; uj++; } }
            ui++;
        }
        // ---- instrument correction (per-state gain from the instrument matrix) ----
        double[] gain = new double[used];
        Arrays.fill(gain, 1);
        boolean instrumentApplied = false;
        if (inst != null) {
            int gi = 0;
            for (int i = 0; i < n; i++) if (useRow[i]) {
                String gtok = i < inst.rows.size() ? pick(inst.rows.get(i), "gain", "response", "w") : null;
                if (gtok != null) {
                    List<String> ig = new ArrayList<>();
                    double gv = Sources.num(gtok, ig);
                    if (!Double.isNaN(gv) && gv != 0) { gain[gi] = gv; instrumentApplied = true; }
                    else r.diagnostics.add("instrument gain row " + i + " unusable: " + ig);
                }
                gi++;
            }
        }
        for (int i = 0; i < used; i++) yy[i] *= gain[i];

        Polar.Design fitDesign = designOf(X, design, used);
        Polar.Estimate est = Polar.estimate(fitDesign, yy, cc);

        // ---- reference-sample calibration factor (known m00 vs measured block) ----
        double refFactor = 1;
        boolean referenceApplied = false;
        Map<String, Object> refInfo = new LinkedHashMap<>();
        if (ref != null && !ref.rows.isEmpty()) {
            List<String> rg = new ArrayList<>();
            double knownM00 = Sources.num(pick(ref.rows.get(0), "m00", "known_m00", "t"), rg);
            double measM00 = est.m[0][0];
            if (!Double.isNaN(knownM00) && Math.abs(measM00) > 1e-12) {
                refFactor = knownM00 / measM00;
                referenceApplied = true;
                refInfo.put("knownM00", knownM00);
                refInfo.put("measuredM00", measM00);
                refInfo.put("factor", refFactor);
                refInfo.put("referenceVersion", ref.version);
                for (int a = 0; a < 4; a++)
                    for (int b = 0; b < 4; b++) est.m[a][b] *= refFactor;
            } else {
                r.diagnostics.add("reference calibration skipped: " + (rg.isEmpty() ? "m00 near zero" : rg));
            }
        }

        // ---- physicality (flag only; never project away violations) ----
        Polar.Physicality ph = Polar.assessPhysicality(est.m);

        // ---- decompositions: multiple candidates kept side by side ----
        List<Map<String, Object>> decomps = new ArrayList<>();
        Polar.LuChipman lc = Polar.luChipman(est.m);
        Map<String, Object> lu = new LinkedHashMap<>();
        lu.put("kind", "Lu-Chipman");
        lu.put("available", lc.available);
        if (lc.available) {
            lu.put("diattenuator", matrixView(lc.mDiattenuator, fingerprint, "lu-diat", est.sigma));
            lu.put("retarder", matrixView(lc.mRetarder, fingerprint, "lu-ret", est.sigma));
            lu.put("depolarizer", matrixView(lc.mDepolarizer, fingerprint, "lu-dep", est.sigma));
            lu.put("diattenuation", idNum(fingerprint, "lu-d", lc.diat));
            lu.put("retardanceDeg", idNum(fingerprint, "lu-r", lc.retardance));
            lu.put("depolarizationPower", idNum(fingerprint, "lu-dp", lc.depolarizationPower));
        } else lu.put("diagnostic", lc.diagnostic);
        decomps.add(lu);

        List<Polar.CloudeComponent> comps = Polar.cloudeSpectral(est.m);
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("kind", "Cloude-spectral");
        List<Object> clist = new ArrayList<>();
        double[][] sum = mat(4, 4);
        for (int k = 0; k < comps.size(); k++) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("lambda", idNum(fingerprint, "cl-lambda-" + k, comps.get(k).lambda));
            cm.put("mueller", matrixView(comps.get(k).m, fingerprint, "cl-m" + k, est.sigma));
            clist.add(cm);
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) sum[a][b] += comps.get(k).m[a][b];
        }
        cl.put("components", clist);
        double reconErr = 0;
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) reconErr += Math.abs(sum[a][b] - est.m[a][b]);
        cl.put("reconstructionError", reconErr);
        decomps.add(cl);

        // ---- assemble stable-identity view ----
        v.put("nRows", n);
        v.put("nUsed", used);
        v.put("excludedRows", dropped);
        v.put("darkVersion", dark == null ? null : dark.version);
        v.put("intensityVersion", intLayer.version);
        v.put("stateVersion", gen.version);
        v.put("covarianceVersion", cov == null ? null : cov.version);
        v.put("instrumentVersion", inst == null ? null : inst.version);
        v.put("wavelengthVersion", wave == null ? null : wave.version);
        v.put("referenceApplied", refInfo);
        v.put("correlatedErrors", correlated);
        v.put("instrumentApplied", instrumentApplied);
        v.put("design", designView(fitDesign, fingerprint));
        v.put("estimate", estimateView(est, fingerprint));
        v.put("physicality", physicalityView(ph, fingerprint));
        v.put("decompositions", decomps);
        v.put("wavelengthNm", readWavelength(wave, r.diagnostics));
        v.put("diagnostics", r.diagnostics);
        return r;
    }

    // ---------------- helpers ----------------

    static Polar.Design designOf(double[][] x, Polar.Design original, int used) {
        LinAlg.SVD svd = LinAlg.svd(x, 1e-10);
        double[][] ns = new double[16][Math.max(0, 16 - svd.rank)];
        for (int k = svd.rank; k < 16; k++)
            for (int i = 0; i < 16; i++) ns[i][k - svd.rank] = svd.v[i][k];
        boolean near = svd.rank == 16 && svd.cond > 1e10;
        return new Polar.Design(x, new boolean[used], svd, ns, near);
    }

    static double[][] buildCovariance(Sources.Layer cov, int n, double[] sigma) {
        if (cov == null || cov.rows.isEmpty()) return null;
        double[][] c = new double[n][n];
        // dense form if headers contain cov_i_j style columns
        boolean dense = cov.headers.stream().anyMatch(h -> h.startsWith("cov_"));
        for (int i = 0; i < n; i++) {
            Map<String, String> row = i < cov.rows.size() ? cov.rows.get(i) : Map.of();
            List<String> dg = new ArrayList<>();
            String s = pick(row, "variance", "sigma2", "sigma^2");
            double vv = s != null ? Sources.num(s, dg) : sigma[i] * sigma[i];
            if (Double.isNaN(vv) || vv <= 0) vv = 1;
            c[i][i] = vv;
            if (dense) {
                String v2 = pick(row, "cov_" + i + "_" + i);
                if (v2 != null) c[i][i] = Math.max(Sources.num(v2, new ArrayList<>()), 1e-12);
                for (int j = 0; j < n; j++) {
                    String cv = pick(row, "cov_" + i + "_" + j);
                    if (cv != null) c[i][j] = Sources.num(cv, new ArrayList<>());
                }
            } else {
                for (int j = i + 1; j < n; j++) {
                    String cv = pick(row, "cov_" + i + "_" + j, "cov" + i + "_" + j);
                    if (cv != null) { double z = Sources.num(cv, new ArrayList<>()); c[i][j] = z; c[j][i] = z; }
                }
            }
        }
        return c;
    }

    static double readWavelength(Sources.Layer wave, List<String> diag) {
        if (wave == null || wave.rows.isEmpty()) return Double.NaN;
        return Sources.num(pick(wave.rows.get(0), "wavelength_nm", "lambda_nm", "wavelength"), diag);
    }

    static String pick(Map<String, String> row, String... keys) {
        for (String k : keys) if (row.containsKey(k) && row.get(k) != null && !row.get(k).isBlank()) return row.get(k);
        return null;
    }
    static String nz(String s, String d) { return s == null || s.isBlank() ? d : s; }

    public static String sha256(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String shortSha(String s) { return sha256(s).substring(0, 12); }

    /** Fingerprint binds conventions + resolved source versions into the provenance of every value. */
    static String fingerprint(Resolved in) {
        StringBuilder sb = new StringBuilder("fp1|");
        for (String k : Sources.KINDS) {
            Sources.Layer l = in.get(k);
            sb.append(k).append('=');
            if (l != null) sb.append(l.version).append('@').append(l.sha == null ? "-" : l.sha.substring(0, 10));
            else sb.append("none");
            sb.append(';');
        }
        Sources.Layer st = in.get("state");
        if (st != null) sb.append("order=").append(st.stokesOrder).append(";rot=").append(st.rotationSign);
        return shortSha(sb.toString());
    }

    static Map<String, Object> idNum(String fp, String role, double value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "v_" + shortSha(fp + "|" + role));
        m.put("role", role);
        m.put("value", Double.isNaN(value) ? null : value);
        m.put("fingerprint", fp);
        return m;
    }

    static List<List<Map<String, Object>>> matrixView(double[][] m, String fp, String role, double[] sigma) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (int j = 0; j < 4; j++) {
                Map<String, Object> cell = idNum(fp, role + "-m" + i + j, m[i][j]);
                if (sigma != null) cell.put("sigma", sigma[4 * i + j]);
                cell.put("row", i); cell.put("col", j);
                row.add(cell);
            }
            rows.add(row);
        }
        return rows;
    }

    static Map<String, Object> designView(Polar.Design d, String fp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conditionNumber", idNum(fp, "design-cond", d.cond));
        m.put("rank", idNum(fp, "design-rank", d.rank));
        m.put("singular", d.singular);
        m.put("nearSingular", d.nearSingular);
        m.put("singularValues", Arrays.stream(d.singularValues)
                .mapToObj(s -> idNum(fp, "design-sv", s)).toList());
        List<Object> nulls = new ArrayList<>();
        for (int k = 0; k < d.nullspace[0].length; k++) {
            List<Object> vec = new ArrayList<>();
            for (int i = 0; i < 16; i++) vec.add(d.nullspace[i][k]);
            nulls.add(vec);
        }
        m.put("nullspace", nulls);
        m.put("determinate", d.rank == 16 && !d.nearSingular);
        return m;
    }

    static Map<String, Object> estimateView(Polar.Estimate e, String fp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mueller", matrixView(e.m, fp, "est", e.sigma));
        m.put("chisq", idNum(fp, "est-chisq", e.chisq));
        m.put("dof", e.dof);
        m.put("determinate", e.determinate);
        m.put("unit", "dimensionless Mueller entries (dark-corrected, instrument/reference calibrated)");
        return m;
    }

    static Map<String, Object> physicalityView(Polar.Physicality p, String fp) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> eig = new ArrayList<>();
        for (int k = 0; k < p.coherencyEigen.length; k++) eig.add(idNum(fp, "phys-eig" + k, p.coherencyEigen[k]));
        m.put("coherencyEigenvalues", eig);
        m.put("psd", p.psd);
        m.put("psdResidual", idNum(fp, "phys-psdres", p.psdResidual));
        m.put("passive", p.passive);
        m.put("passiveMargin", idNum(fp, "phys-passmargin", p.passiveMargin));
        m.put("depolarizationIndex", idNum(fp, "phys-dep", p.depolarization));
        m.put("violations", p.violations);
        m.put("projected", false);
        return m;
    }
}
