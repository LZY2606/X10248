package pmr.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.ejml.data.DMatrixRMaj;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measurement model: dark-corrected intensity b_k = a_k^T M g_k,
 * vectorized as b = X vec(M) with vec row-major (M[i][j] at index 4*i+j).
 */
public final class Measurement {
    private Measurement() {}

    public record Row(int index, String generatorId, String analyzerId,
                      double intensity, double variance, double dark,
                      double[] generatorStokes, double[] analyzerStokes,
                      String unit) {}

    public record Built(List<Row> rows, DMatrixRMaj x, DMatrixRMaj b,
                        DMatrixRMaj covariance, List<String> issues) {}

    public static Built build(JsonNode intensities, JsonNode states, JsonNode dark,
                              Convention convention) {
        double offset = dark.path("offset").asDouble(0.0);
        double darkStd = dark.path("offsetStd").asDouble(0.0);
        Map<String, JsonNode> stateById = new LinkedHashMap<>();
        for (JsonNode s : states.path("states")) {
            stateById.set(s.path("id").asText(), s);
        }
        List<Row> rows = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<double[]> rowsX = new ArrayList<>();
        double[] b = new double[intensities.path("measurements").size()];
        double[] diagCov = new double[b.length];
        int idx = 0;
        for (JsonNode mn : intensities.path("measurements")) {
            String gId = mn.path("generatorStateId").asText();
            String aId = mn.path("analyzerStateId").asText();
            JsonNode gs = stateById.get(gId);
            JsonNode as = stateById.get(aId);
            if (gs == null || as == null) {
                throw new IllegalArgumentException("measurement row " + idx
                        + " references unknown optical state (" + gId + "," + aId + ")");
            }
            double[] g = stokes(gs, convention);
            double[] a = stokes(as, convention);
            double intensity = mn.path("intensity").asDouble();
            String unit = mn.path("unit").asText(intensities.path("intensityUnit").asText("count"));
            double measuredVar = mn.path("variance").isNumber()
                    ? mn.path("variance").asDouble() : Double.NaN;
            double variance = Double.isNaN(measuredVar)
                    ? Math.max(intensity - offset, 0.0) + darkStd * darkStd
                    : measuredVar;
            double corrected = intensity - offset;
            rowsX.add(kronecker(a, g));
            b[idx] = corrected;
            diagCov[idx] = variance;
            rows.add(new Row(idx, gId, aId, intensity, variance, offset, g, a, unit));
            idx++;
        }
        DMatrixRMaj x = new DMatrixRMaj(rowsX.toArray(new double[0][]));
        DMatrixRMaj bv = DMatrixRMaj.wrap(b.length, 1, b);
        DMatrixRMaj cov = new DMatrixRMaj(diagCov.length, diagCov.length);
        for (int i = 0; i < diagCov.length; i++) {
            cov.set(i, i, diagCov[i]);
        }
        return new Built(rows, x, bv, cov, issues);
    }

    /** Reorder an IQUV-encoded stokes vector according to the convention order. */
    private static double[] stokes(JsonNode state, Convention convention) {
        JsonNode v = state.path("stokesIQUV");
        double[] iquv = {v.get(0).asDouble(), v.get(1).asDouble(), v.get(2).asDouble(),
                v.get(3).asDouble() * convention.sign()};
        double[] out = new double[4];
        for (int r = 0; r < 4; r++) {
            out[r] = iquv["IQUV".indexOf(convention.stokesOrder().charAt(r))];
        }
        return out;
    }

    /** Row of the Khatri-like design product: X[k,4i+j] = a[i]*g[j]. */
    private static double[] kronecker(double[] a, double[] g) {
        double[] out = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[4 * i + j] = a[i] * g[j];
            }
        }
        return out;
    }
}
