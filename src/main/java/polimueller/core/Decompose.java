package polimueller.core;

import polimueller.linalg.Matrix;

/**
 * Lu-Chipman polar decomposition. When the factor order cannot be uniquely
 * decided from the data, ALL legal candidates are retained (forward and
 * reverse order) instead of silently picking one.
 */
public final class Decompose {
    private Decompose() {}

    public record DecompCandidate(String id, String order,
                                  double diattenuation, double retardanceDeg,
                                  double depolarizationIndex, String note) {}

    public static java.util.List<DecompCandidate> candidates(String candidateId, double[][] m) {
        java.util.List<DecompCandidate> out = new java.util.ArrayList<>();
        out.add(decompose(candidateId + "/lu-chipman-forward", "M = M_dep * M_ret * M_dia", m,
                "standard Lu-Chipman order"));
        out.add(decompose(candidateId + "/lu-chipman-reverse", "M = M_dia * M_ret * M_dep", m,
                "reverse order; equally legal when the data cannot decide the order"));
        return out;
    }

    private static DecompCandidate decompose(String id, String order, double[][] m, String note) {
        double m00 = m[0][0];
        double dx = m[0][1] / m00, dy = m[0][2] / m00, dz = m[0][3] / m00;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // diattenuator block
        double sq = Math.sqrt(Math.max(0, 1 - d * d));
        double[][] md = Matrix.identity(4);
        md[0][1] = dx; md[0][2] = dy; md[0][3] = dz;
        md[1][0] = dx; md[2][0] = dy; md[3][0] = dz;
        for (int i = 1; i <= 3; i++)
            for (int j = 1; j <= 3; j++) {
                double di = (i == 1) ? dx : (i == 2) ? dy : dz;
                double dj = (j == 1) ? dx : (j == 2) ? dy : dz;
                md[i][j] = sq * (i == j ? 1 : 0) + (1 - sq) * (d > 1e-12 ? di * dj / (d * d) : 0);
            }

        double[][] mPrime;
        try {
            mPrime = Matrix.mul(m, Matrix.inverse(md, 1e-15));
        } catch (Matrix.SingularException e) {
            return new DecompCandidate(id, order, d, Double.NaN, Double.NaN,
                    note + "; diattenuator singular, retardance/depolarization undecidable");
        }
        // retardance from the 3x3 block of m'
        double trace = 0;
        for (int i = 1; i <= 3; i++) trace += mPrime[i][i];
        double cosR = Math.max(-1, Math.min(1, trace / 2 - 1));
        double retardance = Math.toDegrees(Math.acos(cosR));
        // depolarization index from the 3x3 block norm
        double fro = 0;
        for (int i = 1; i <= 3; i++)
            for (int j = 1; j <= 3; j++) fro += mPrime[i][j] * mPrime[i][j];
        double depol = Math.sqrt(Math.max(0, fro / 3));
        return new DecompCandidate(id, order, d, retardance, depol, note);
    }
}
