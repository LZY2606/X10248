package polworkspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Physics {
    public static final String RULE_VERSION = "mueller-room-rules-2026.1";
    public static final double SINGULAR_THRESHOLD = 1.0e-8;
    private Physics() {}

    public record Observation(double[][] design, double[] intensity, Matrix covariance,
                              String stokesOrder, String rotationSign, double[] corrected,
                              List<String> diagnostics) {}

    public static double[] stokesFromState(Json.Obj state, String order, String rotationSign) {
        double[] raw = new double[] {
                state.getDouble("s0", state.getDouble("S0", 0)),
                state.getDouble("s1", state.getDouble("S1", 0)),
                state.getDouble("s2", state.getDouble("S2", state.getDouble("s3", state.getDouble("S3", 0)))),
                state.getDouble("s3", state.getDouble("S3", state.getDouble("s2", state.getDouble("S2", 0))))
        };
        if ("S0S1S3S2".equals(order)) {
            double u = raw[2];
            raw[2] = raw[3];
            raw[3] = u;
        }
        if ("LEFT_HANDED_POSITIVE".equals(rotationSign)) raw[3] = -raw[3];
        return raw;
    }

    public static Observation buildObservation(Json.Obj sourceSet) {
        Json.Arr intensityRows = sourceSet.getArr("intensities");
        Json.Arr states = sourceSet.getArr("states");
        if (intensityRows == null || states == null) throw new IllegalArgumentException("intensities and states are required");
        if (intensityRows.size() != states.size()) throw new IllegalArgumentException("intensity/state row count mismatch");
        double[] dark = numericVector(sourceSet.getObj("dark"), "values", intensityRows.size());
        double scale = sourceSet.getObj("reference").getDouble("scale_factor", 1.0);
        Matrix instrument = matrixFromJson(sourceSet.getObj("instrument"), "matrix");
        String order = sourceSet.getStr("stokes_order");
        String sign = sourceSet.getStr("rotation_sign");
        int n = intensityRows.size();
        double[] corrected = new double[n];
        double[][] design = new double[n][16];
        for (int i = 0; i < n; i++) {
            double raw = ((Number) intensityRows.get(i)).doubleValue();
            corrected[i] = scale * (raw - dark[i]);
            double[] analyzer = stokesFromState((Json.Obj) states.get(i), order, sign);
            double[] generator = stokesFromState((Json.Obj) states.get(i).getClass() == Json.Obj.class ? (Json.Obj) states.get(i) : null, order, sign);
            Json.Obj state = (Json.Obj) states.get(i);
            generator = stokesFromState(state.getObj("generator"), order, sign);
            analyzer = stokesFromState(state.getObj("analyzer"), order, sign);
            for (int out = 0; out < 4; out++)
                for (int in = 0; in < 4; in++) design[i][4 * out + in] = analyzer[out] * generator[in];
        }
        Matrix rawDesign = Matrix.of(design);
        Matrix correctedDesign = rawDesign.multiply(instrument);
        Matrix covariance = covarianceFromJson(sourceSet.getObj("covariance"), n);
        List<String> diagnostics = new ArrayList<>();
        Json.Arr sourceDiagnostics = sourceSet.getArr("diagnostics");
        if (sourceDiagnostics != null) for (Object item : sourceDiagnostics) diagnostics.add(String.valueOf(item));
        return new Observation(correctedDesign.a, corrected, covariance, order, sign, corrected, diagnostics);
    }

    public static Matrix matrixFromJson(Json.Obj source, String key) {
        Json.Arr rows = source.getArr(key);
        double[][] values = new double[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            Json.Arr row = (Json.Arr) rows.get(r);
            values[r] = new double[row.size()];
            for (int c = 0; c < row.size(); c++) values[r][c] = ((Number) row.get(c)).doubleValue();
        }
        return Matrix.of(values);
    }

    public static double[] numericVector(Json.Obj source, String key, int expected) {
        Json.Arr rows = source.getArr(key);
        if (rows == null) return new double[expected];
        double[] values = new double[expected];
        for (int i = 0; i < Math.min(expected, rows.size()); i++) {
            Object value = rows.get(i);
            if (value instanceof Number number) values[i] = number.doubleValue();
            else values[i] = 0;
        }
        return values;
    }

    public static Matrix covarianceFromJson(Json.Obj source, int n) {
        Matrix covariance = Matrix.zeros(n, n);
        Json.Arr values = source.getArr("values");
        Json.Arr entries = source.getArr("entries");
        if (values != null) {
            if (values.size() == n * n) {
                for (int r = 0; r < n; r++) for (int c = 0; c < n; c++)
                    covariance.a[r][c] = ((Number) values.get(r * n + c)).doubleValue();
            } else {
                for (int i = 0; i < n; i++) covariance.a[i][i] = ((Number) values.get(Math.min(i, values.size() - 1))).doubleValue();
            }
        } else if (entries != null) {
            for (Object item : entries) {
                Json.Obj entry = (Json.Obj) item;
                covariance.a[entry.getInt("row", 0)][entry.getInt("column", 0)] = entry.getDouble("value", 0);
            }
        } else for (int i = 0; i < n; i++) covariance.a[i][i] = 1;
        Matrix sym = covariance.add(covariance.transpose()).scale(0.5);
        for (int i = 0; i < n; i++) sym.a[i][i] = covariance.a[i][i];
        return sym;
    }

    public record PhysicalReport(String status, double worstViolation, double minCloudeEigenvalue,
                                 double maxCloudeEigenvalue, double depolarizationIndex,
                                 double diattenuation, double polarizance, List<String> violations,
                                 double[] cloudeEigenvalues) {}

    public static PhysicalReport checkPhysical(double[][] mueller) {
        List<String> violations = new ArrayList<>();
        double m00 = mueller[0][0];
        if (m00 <= 0) violations.add("m00 must be positive");
        double worst = m00 <= 0 ? Math.abs(m00) : 0;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (r == 0 && c == 0) continue;
            if (m00 > 0 && Math.abs(mueller[r][c]) > m00 + 1e-9) {
                violations.add("|m" + r + c + "| exceeds m00");
                worst = Math.max(worst, Math.abs(mueller[r][c]) - m00);
            }
        }
        double sum = 0;
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) if (!(r == 0 && c == 0)) sum += mueller[r][c] * mueller[r][c];
        double di = m00 > 0 ? Math.sqrt(sum) / (Math.sqrt(3) * m00) : Double.NaN;
        if (Double.isFinite(di) && di > 1 + 1e-9) {
            violations.add("depolarization index exceeds one");
            worst = Math.max(worst, di - 1);
        }
        double dv = norm(mueller[0], 1) / Math.max(m00, 1e-300);
        double pv = 0;
        for (int r = 1; r < 4; r++) pv += mueller[r][0] * mueller[r][0];
        pv = Math.sqrt(pv) / Math.max(m00, 1e-300);
        if (dv > 1 + 1e-9) violations.add("diattenuation exceeds one");
        if (pv > 1 + 1e-9) violations.add("polarizance exceeds one");
        worst = Math.max(worst, Math.max(0, dv - 1), Math.max(0, pv - 1));
        double[] eigenvalues = Cloude.eigenvalues(mueller);
        double min = eigenvalues[0];
        double max = eigenvalues[0];
        for (double x : eigenvalues) { min = Math.min(min, x); max = Math.max(max, x); }
        double eigenViolation = max > 0 ? -min / max : Math.abs(min);
        if (eigenViolation > 1e-7) {
            violations.add("Cloude coherency eigenvalue " + format(min) + " is negative");
            worst = Math.max(worst, eigenViolation);
        }
        return new PhysicalReport(worst > 1e-7 ? "NONPHYSICAL_UNPROJECTED" : "PHYSICAL", worst, min, max, di, dv, pv, violations, eigenvalues);
    }

    private static double norm(double[] vector, int start) {
        double sum = 0;
        for (int i = start; i < vector.length; i++) sum += vector[i] * vector[i];
        return Math.sqrt(sum);
    }
    static String format(double x) { return String.format("%.4g", x); }

    public record DecompositionCandidate(String family, double[][] matrix, Json.Obj details) {}

    public static List<DecompositionCandidate> decompose(double[][] mueller) {
        double m00 = Math.max(mueller[0][0], 1e-300);
        double[] d = Arrays.copyOfRange(mueller[0], 1, 4);
        double[] p = new double[] {mueller[1][0], mueller[2][0], mueller[3][0]};
        return List.of(
                new DecompositionCandidate("forward_product", productMatrix(mueller, d, true), forwardDetails(mueller, d, "diattenuator_first")),
                new DecompositionCandidate("reverse_product", productMatrix(mueller, scalarDivide(p, m00), false), forwardDetails(mueller, scalarDivide(p, m00), "polarizance_last")),
                new DecompositionCandidate("cloude_spectral", mueller, cloudeDetails(mueller))
        );
    }

    private static double[] scalarDivide(double[] vector, double factor) {
        double[] result = vector.clone();
        for (int i = 0; i < result.length; i++) result[i] /= factor;
        return result;
    }

    private static double[][] productMatrix(double[][] m, double[] vector, boolean forward) {
        double[][] d = diattenuator(vector);
        Matrix di = Matrix.of(d);
        Matrix mu = Matrix.of(m);
        Matrix residual = forward ? Matrix.inverse(di).multiply(mu) : mu.multiply(Matrix.inverse(di));
        return residual.a;
    }

    private static double[][] diattenuator(double[] dRaw) {
        double[] d = dRaw.clone();
        double norm = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double safe = Math.min(0.999999, norm);
        double factor = norm < 1e-12 ? 0 : (1 - Math.sqrt(Math.max(0, 1 - safe * safe))) / (safe * safe);
        double[][] dmat = Matrix.identity(4).a;
        for (int i = 0; i < 3; i++) {
            dmat[0][i + 1] = d[i];
            dmat[i + 1][0] = d[i];
            for (int j = 0; j < 3; j++) dmat[i + 1][j + 1] = Math.sqrt(Math.max(0, 1 - safe * safe)) * (i == j ? 1 : 0) + factor * d[i] * d[j];
        }
        return dmat;
    }

    private static Json.Obj forwardDetails(double[][] m, double[] vector, String label) {
        double magnitude = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        return Json.obj().str("family", label)
                .num("vector_magnitude", magnitude)
                .obj("vector", vector)
                .str("note", "factor retained as an unprojected numeric decomposition candidate");
    }

    private static Json.Obj cloudeDetails(double[][] m) {
        double[] eigenvalues = Cloude.eigenvalues(m);
        double sum = Arrays.stream(eigenvalues).filter(x -> x > 0).sum();
        double entropy = 0;
        for (double x : eigenvalues) if (x > 0 && sum > 0) {
            double p = x / sum;
            entropy -= p * Math.log(p) / Math.log(4);
        }
        return Json.obj().str("family", "Cloude coherency spectral decomposition")
                .obj("eigenvalues", eigenvalues)
                .num("entropy", entropy)
                .bool("projected_to_nearest_physical", false);
    }
}
