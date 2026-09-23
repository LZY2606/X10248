package polar.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller-matrix estimation, physical-realizability checks and decomposition.
 *
 * Rules enforced here (these are domain invariants, not UI concerns):
 *  - A near-singular design matrix is NEVER inverted by force; the run is
 *    flagged NEAR_SINGULAR and no matrix is produced.
 *  - A non-physical Mueller candidate is flagged with its violations; it is
 *    never silently projected onto the physical set and passed off as the
 *    original solution.
 */
public final class MuellerMath {
    private MuellerMath() {}

    /** Condition number above which the design is treated as near-singular. */
    public static final double CONDITION_LIMIT = 1e12;

    public enum EstimationStatus { OK, NEAR_SINGULAR }

    public record EstimationResult(
            EstimationStatus status,
            double conditionNumber,
            double[][] mueller,      // null when NEAR_SINGULAR
            double[] elementErrors,  // null when NEAR_SINGULAR
            double[] residuals,
            String diagnostic) {}

    /**
     * Weighted least squares y = A m with weights from the inverse covariance.
     * {@code design} is n x 16, {@code y} is dark-corrected intensity.
     */
    public static EstimationResult estimate(double[][] design, double[] y, double[][] inverseCovariance) {
        double[][] at = Matrix.transpose(design);
        double[][] atw = Matrix.multiply(at, inverseCovariance);
        double[][] atwa = Matrix.multiply(atw, design);
        double cond = Matrix.conditionNumberSymmetric(atwa);
        double[] atwy = Matrix.matVec(atw, y);
        if (!(cond < CONDITION_LIMIT)) {
            return new EstimationResult(EstimationStatus.NEAR_SINGULAR, cond, null, null, null,
                    "design matrix A^T W A condition number " + cond
                            + " exceeds limit " + CONDITION_LIMIT + "; inverse not attempted");
        }
        double[][] cov;
        try {
            cov = Matrix.inverse(atwa);
        } catch (ArithmeticException e) {
            return new EstimationResult(EstimationStatus.NEAR_SINGULAR, cond, null, null, null,
                    "normal equations singular to working precision; inverse not attempted");
        }
        double[] m = Matrix.matVec(cov, atwy);
        double[][] mueller = new double[4][4];
        for (int i = 0; i < 16; i++) mueller[i / 4][i % 4] = m[i];
        double[] errs = new double[16];
        for (int i = 0; i < 16; i++) errs[i] = Math.sqrt(Math.max(cov[i][i], 0));
        double[] fit = Matrix.matVec(design, m);
        double[] res = new double[y.length];
        for (int i = 0; i < y.length; i++) res[i] = y[i] - fit[i];
        return new EstimationResult(EstimationStatus.OK, cond, mueller, errs, res, null);
    }

    /** Build one design-matrix row from generator/analyzer Stokes vectors. */
    public static double[] designRow(double[] generator, double[] analyzer) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                row[i * 4 + j] = analyzer[i] * generator[j];
        return row;
    }

    public record PhysicalityReport(
            boolean physical,
            double[] coherencyEigenvalues,
            double violationResidual,
            List<String> violations) {}

    /**
     * Physical realizability: the coherency matrix H associated with M must be
     * positive semidefinite. Negative eigenvalues are reported as violations.
     */
    public static PhysicalityReport physicality(double[][] m) {
        // Pauli matrices (complex): s0=I, s1, s2, s3.
        double[][][] re = new double[4][4][4];
        double[][][] im = new double[4][4][4];
        re[0] = new double[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
        re[1] = new double[][]{{1, 0, 0, 0}, {0, -1, 0, 0}, {0, 0, -1, 0}, {0, 0, 0, 1}};
        re[2] = new double[][]{{0, 1, 1, 0}, {1, 0, 0, 1}, {1, 0, 0, 1}, {0, 1, 1, 0}};
        im[3] = new double[][]{{0, 0, 0, -1}, {0, 0, -1, 0}, {0, 1, 0, 0}, {1, 0, 0, 0}};
        // H = 1/4 sum_ij m_ij sigma_i (x) sigma_j  (Cloude's coherency form)
        double[][] hre = new double[4][4];
        double[][] him = new double[4][4];
        // Build sigma_i (x) sigma_j contributions directly.
        double[][][] sr = {
                {{1, 0}, {0, 1}},
                {{1, 0}, {0, -1}},
                {{0, 1}, {1, 0}},
                {{0, 0}, {0, 0}}};
        double[][][] si = {
                {{0, 0}, {0, 0}},
                {{0, 0}, {0, 0}},
                {{0, 0}, {0, 0}},
                {{0, -1}, {1, 0}}};
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double mij = m[i][j] / 4.0;
                for (int a = 0; a < 2; a++)
                    for (int b = 0; b < 2; b++)
                        for (int c = 0; c < 2; c++)
                            for (int d = 0; d < 2; d++) {
                                double xr = sr[i][a][b], xi = si[i][a][b];
                                double yr = sr[j][c][d], yi = si[j][c][d];
                                int row = a * 2 + c, col = b * 2 + d;
                                hre[row][col] += mij * (xr * yr - xi * yi);
                                him[row][col] += mij * (xr * yi + xi * yr);
                            }
            }
        // Hermitian 4x4 -> real symmetric 8x8 [Re -Im; Im Re]
        double[][] big = new double[8][8];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                big[i][j] = hre[i][j];
                big[i][j + 4] = -him[i][j];
                big[i + 4][j] = him[i][j];
                big[i + 4][j + 4] = hre[i][j];
            }
        double[] ev8 = Matrix.eigenvaluesSymmetric(big);
        double[] ev = new double[4];
        for (int i = 0; i < 4; i++) ev[i] = ev8[2 * i]; // each eigenvalue doubled
        double tol = -1e-9;
        List<String> violations = new ArrayList<>();
        double residual = 0;
        for (int i = 0; i < 4; i++) {
            if (ev[i] < tol) {
                violations.add("coherency eigenvalue h" + i + " = " + ev[i] + " < 0");
                residual += -ev[i];
            }
        }
        return new PhysicalityReport(violations.isEmpty(), ev, residual, violations);
    }

    public record DecompositionCandidate(
            String scheme,
            double diattenuation,
            double retardanceDeg,
            double depolarizationIndex,
            String notes) {}

    /**
     * Lu-Chipman polar decomposition candidates. Multiple legal candidates are
     * retained; none is silently preferred.
     */
    public static List<DecompositionCandidate> decompose(double[][] m) {
        List<DecompositionCandidate> out = new ArrayList<>();
        double m00 = m[0][0];
        double[] d = {m[0][1] / m00, m[0][2] / m00, m[0][3] / m00};
        double diat = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        // Depolarization index from G = M^T M eigenvalues of the 3x3 block.
        double[][] sub = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) sub[i][j] = m[i + 1][j + 1] / m00;
        double[][] g = Matrix.multiply(Matrix.transpose(sub), sub);
        double[] ev = Matrix.eigenvaluesSymmetric(g);
        double sum = 0;
        for (double e : ev) sum += Math.max(e, 0);
        double depol = Math.sqrt(Math.max(sum, 0) / 3.0);
        // Retardance estimate from the rotation part of the 3x3 block.
        double trace = sub[0][0] + sub[1][1] + sub[2][2];
        double cosR = Math.max(-1, Math.min(1, trace / Math.max(depol, 1e-12) / 2.0 - 0.5));
        double retard = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosR))));
        out.add(new DecompositionCandidate("lu-chipman:forward(M=MDep*MRet*MDiat)",
                diat, retard, 1 - depol, "standard forward Lu-Chipman order"));
        out.add(new DecompositionCandidate("lu-chipman:reverse(M=MDiat*MRet*MDep)",
                diat, retard, 1 - depol, "reverse product order; equally legal a priori"));
        return out;
    }
}
