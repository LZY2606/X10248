package pol.math;

import java.util.List;
import java.util.Map;

/**
 * Measurement design for a rotating-state polarimeter:
 *
 *   b_k = I_k - dark_k = g_k^T M a_k
 *
 * which is linear in the 16 Mueller entries: b = W m, m = [m00..m33] row-major.
 * Row k = kron(analyzer_k^T, generator_k).
 */
public final class DesignMatrix {
    private DesignMatrix() {}

    public record State(String kind, double angleDeg) {}

    /** Build state Stokes (SIQUV) from an optical-state code under the fingerprint convention. */
    public static double[] stateVector(Map<String, Object> code, Fingerprint fp) {
        String kind = code.getOrDefault("kind", "L").toString();
        double raw = Double.parseDouble(code.getOrDefault("angleDeg", "0").toString());
        double theta = fp.signedAngleDeg(raw);
        double[] v = switch (kind) {
            case "L" -> Stokes.linear(theta);
            case "RC" -> Stokes.rightCircular();
            case "LC" -> Stokes.leftCircular();
            case "U" -> Stokes.unpolarized();
            default -> throw new IllegalArgumentException("unknown state kind " + kind);
        };
        return Stokes.reorder(v, fp.stokesOrder);
    }

    /** Build the n x 16 design matrix. */
    public static Matrix build(List<Map<String, Object>> generatorCodes,
                               List<Map<String, Object>> analyzerCodes,
                               Fingerprint fp) {
        int n = Math.min(generatorCodes.size(), analyzerCodes.size());
        Matrix W = new Matrix(n, 16);
        for (int k = 0; k < n; k++) {
            double[] g = stateVector(generatorCodes.get(k), fp);
            double[] a = stateVector(analyzerCodes.get(k), fp);
            int col = 0;
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++) W.a[k][col++] = a[r] * g[c];
        }
        return W;
    }
}
