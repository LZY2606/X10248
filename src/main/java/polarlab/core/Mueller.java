package polarlab.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller matrix estimation from intensity measurements under multiple
 * generator/analyser states, with physical-realizability checks.
 *
 * Rules enforced here:
 *  - a near-singular design matrix is NEVER forcibly inverted;
 *  - a non-physical Mueller candidate is flagged with its violation, never
 *    silently projected back onto the physical cone and passed off as the
 *    original solution.
 */
public final class Mueller {
    private Mueller() {}

    public static final class Candidate {
        public String id;
        public double lambda;
        public double[] m;            // 16 elements, row-major
        public double[] errors;       // per-element 1-sigma
        public double[] coherencyEigenvalues;
        public double physicalResidual; // min coherency eigenvalue; < 0 violates
        public boolean physical;
        public boolean accepted;      // human ruling, default undecided
        public boolean rejected;
    }

    public static final class Estimate {
        public String status;         // OK | NEAR_SINGULAR_DESIGN
        public double conditionNumber;
        public double condThreshold;
        public List<Candidate> candidates = new ArrayList<>();
        public List<String> diagnostics = new ArrayList<>();
    }

    /** Build one design row: w = 0.5 * kron(analyser, generator) after Stokes-order mapping. */
    public static double[] designRow(double[] analyser, double[] generator, RuleConfig cfg) {
        int[] perm = cfg.stokesPermutation();
        double[] a = new double[4], g = new double[4];
        for (int i = 0; i < 4; i++) {
            a[perm[i]] = analyser[i];
            g[perm[i]] = generator[i];
        }
        // Rotation convention flips the sign of the U,V rows of rotated states.
        double s = cfg.rotationSign();
        g[2] *= s; g[3] *= s;
        a[2] *= s; a[3] *= s;
        double[] k = Lin.kron(a, g);
        for (int i = 0; i < k.length; i++) k[i] *= 0.5;
        return k;
    }

    /**
     * Estimate Mueller matrix from design matrix W (n x 16), dark-corrected
     * intensities y (n) and full measurement covariance sigma (n x n).
     */
    public static Estimate estimate(double[][] w, double[] y, double[][] sigma, RuleConfig cfg) {
        Estimate est = new Estimate();
        est.condThreshold = cfg.condThreshold;
        est.conditionNumber = Lin.conditionNumber(w);
        if (!(est.conditionNumber < cfg.condThreshold)) {
            est.status = "NEAR_SINGULAR_DESIGN";
            est.diagnostics.add("design matrix condition number " + est.conditionNumber
                    + " >= threshold " + cfg.condThreshold
                    + "; normal equations NOT inverted, no candidate produced");
            return est;
        }
        est.status = "OK";
        double[][] wt = Lin.transpose(w);
        double[][] wtw = Lin.mul(wt, w);
        double[] wty = matVec(wt, y);
        double[][] wtwInv = Lin.inverse(wtw);
        // Full covariance propagation: cov(m) = (W'W)^-1 W' Sigma W (W'W)^-1
        double[][] cov = Lin.mul(Lin.mul(Lin.mul(wtwInv, wt), sigma), Lin.mul(w, wtwInv));
        for (int ci = 0; ci < cfg.ridgeLambdas.length; ci++) {
            double lambda = cfg.ridgeLambdas[ci];
            Candidate c = new Candidate();
            c.id = "cand-" + ci;
            c.lambda = lambda;
            double[][] reg = new double[16][16];
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++)
                    reg[i][j] = wtw[i][j] + (i == j ? lambda : 0);
            c.m = Lin.solve(reg, wty);
            c.errors = new double[16];
            for (int i = 0; i < 16; i++) c.errors[i] = Math.sqrt(Math.max(cov[i][i], 0));
            c.coherencyEigenvalues = coherencyEigenvalues(c.m);
            c.physicalResidual = c.coherencyEigenvalues[0];
            c.physical = c.physicalResidual >= -1e-9;
            if (!c.physical) {
                est.diagnostics.add("candidate " + c.id + " (lambda=" + lambda + ") violates physical "
                        + "realizability: min coherency eigenvalue " + c.physicalResidual
                        + " < 0; flagged and kept as-is, NOT projected onto the physical cone");
            }
            est.candidates.add(c);
        }
        return est;
    }

    /** Cloude coherency matrix eigenvalues of a Mueller matrix (physical iff all >= 0). */
    public static double[] coherencyEigenvalues(double[] m) {
        double[][] h = new double[4][4];
        h[0][0] = (m[0] + m[1] + m[4] + m[5]) / 2;
        h[0][1] = (m[2] + m[6]) / 2;
        h[0][2] = (m[8] + m[12]) / 2;
        h[0][3] = (m[3] + m[7]) / 2;
        h[1][0] = h[0][1];
        h[1][1] = (m[0] + m[1] - m[4] - m[5]) / 2;
        h[1][2] = (m[9] + m[13]) / 2;
        h[1][3] = (m[11] + m[15]) / 2;
        h[2][0] = h[0][2];
        h[2][1] = h[1][2];
        h[2][2] = (m[0] - m[1] + m[4] - m[5]) / 2;
        h[2][3] = (m[10] + m[14]) / 2;
        h[3][0] = h[0][3];
        h[3][1] = h[1][3];
        h[3][2] = h[2][3];
        h[3][3] = (m[0] - m[1] - m[4] + m[5]) / 2;
        return Lin.jacobiEigenvalues(h);
    }

    /** Lu-Chipman style summary of a Mueller matrix for the decomposition view. */
    public static String decompositionSummary(double[] m) {
        double m00 = m[0];
        double d = Math.sqrt(m[1] * m[1] + m[2] * m[2] + m[3] * m[3]) / m00; // diattenuation
        double p = Math.sqrt(m[4] * m[4] + m[8] * m[8] + m[12] * m[12]) / m00; // polarizance
        double dep = 0;
        for (int i = 1; i < 16; i++) dep += m[i] * m[i];
        dep = Math.sqrt(dep / 3) / m00; // depolarisation index proxy
        return String.format("diattenuation=%.6f, polarizance=%.6f, depolarisationIndex=%.6f", d, p, dep);
    }

    private static double[] matVec(double[][] a, double[] x) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) out[i] += a[i][j] * x[j];
        return out;
    }
}
