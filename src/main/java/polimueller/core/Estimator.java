package polimueller.core;

import polimueller.linalg.Matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller matrix estimation from intensity measurements under multiple
 * generator/analyzer states. Core rule: a near-singular design matrix is
 * NEVER force-inverted; instead the system emits truncated-rank candidates
 * and marks the estimate unresolved.
 */
public final class Estimator {
    private Estimator() {}

    public static final double COND_LIMIT = 1e12;
    public static final double PIVOT_TOL = 1e-14;
    public static final String RULE_VERSION = "mueller-rules-1.0";

    public record Measurement(double[] generator, double[] analyzer,
                              double intensity, double dark, String unit) {
        public double corrected() { return intensity - dark; }
    }

    public record Candidate(String id, double[] vecM, double[] errors, int rank,
                            boolean truncated) {
        public double[][] matrix() {
            double[][] m = new double[4][4];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) m[i][j] = vecM[i * 4 + j];
            return m;
        }
    }

    public record Result(String status, double conditionNumber, double[] designEigenvalues,
                         List<Candidate> candidates, String fingerprint, String rationale) {
        public boolean nearSingular() { return "NEAR_SINGULAR".equals(status); }
    }

    /** Design matrix row: kron(analyzer, generator) in row-major vec order. */
    public static double[] designRow(double[] analyzer, double[] generator) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                row[i * 4 + j] = analyzer[i] * generator[j];
        return row;
    }

    public static double[][] designMatrix(List<Measurement> ms) {
        double[][] w = new double[ms.size()][16];
        for (int k = 0; k < ms.size(); k++)
            w[k] = designRow(ms.get(k).analyzer(), ms.get(k).generator());
        return w;
    }

    public static Result estimate(List<Measurement> ms, double[][] covariance, String fingerprint) {
        int n = ms.size();
        double[][] w = designMatrix(ms);
        double[] y = new double[n];
        for (int k = 0; k < n; k++) y[k] = ms.get(k).corrected();

        double[][] cInv = Matrix.inverse(covariance, PIVOT_TOL);
        double[][] wt = Matrix.transpose(w);
        double[][] a = Matrix.mul(Matrix.mul(wt, cInv), w);   // 16x16 normal equations
        double[] b = Matrix.mul(Matrix.mul(wt, cInv), y);

        Matrix.Eigen eig = Matrix.jacobiEigen(a);
        double lmax = eig.values[0];
        double lmin = eig.values[15];
        double cond = lmin <= 0 ? Double.POSITIVE_INFINITY : lmax / lmin;

        List<Candidate> candidates = new ArrayList<>();
        if (cond > COND_LIMIT || lmin < PIVOT_TOL) {
            // Near-singular: do NOT invert. Offer truncated-rank candidates only.
            int[] ranks = candidateRanks(eig.values);
            int idx = 0;
            for (int r : ranks) {
                double[] m = truncatedSolve(eig, b, r);
                candidates.add(new Candidate("cand-" + fingerprint.substring(0, 8) + "-r" + r,
                        m, nanErrors(), r, true));
                idx++;
            }
            return new Result("NEAR_SINGULAR", cond, eig.values, candidates, fingerprint,
                    "near_singular_no_forced_inverse: condition=" + Matrix.fmt(cond)
                            + ", minEigen=" + Matrix.fmt(lmin)
                            + "; normal equations not inverted, " + candidates.size()
                            + " truncated-rank candidates retained");
        }

        double[][] aInv = Matrix.inverse(a, PIVOT_TOL);
        double[] m = Matrix.mul(aInv, b);
        double[] err = new double[16];
        for (int i = 0; i < 16; i++) err[i] = Math.sqrt(Math.abs(aInv[i][i]));
        candidates.add(new Candidate("cand-" + fingerprint.substring(0, 8) + "-full",
                m, err, 16, false));
        return new Result("OK", cond, eig.values, candidates, fingerprint,
                "well_conditioned: condition=" + Matrix.fmt(cond));
    }

    private static int[] candidateRanks(double[] eigenvalues) {
        // distinct plateaus where eigenvalues stay above 1e-10 * max
        double lmax = eigenvalues[0];
        List<Integer> ranks = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < eigenvalues.length; i++) {
            if (eigenvalues[i] > lmax * 1e-10) run++;
            else break;
        }
        if (run == 0) run = 1;
        ranks.add(run);
        if (run > 1) ranks.add(run - 1);
        if (run + 4 <= 16) ranks.add(run + 4); // a slightly over-complete alternative
        return ranks.stream().distinct().mapToInt(Integer::intValue).toArray();
    }

    private static double[] truncatedSolve(Matrix.Eigen eig, double[] b, int rank) {
        double[] m = new double[16];
        for (int i = 0; i < rank && i < 16; i++) {
            double lam = eig.values[i];
            if (lam < PIVOT_TOL) continue;
            double dot = 0;
            for (int k = 0; k < 16; k++) dot += eig.vectors[k][i] * b[k];
            for (int k = 0; k < 16; k++) m[k] += eig.vectors[k][i] * dot / lam;
        }
        return m;
    }

    private static double[] nanErrors() {
        double[] e = new double[16];
        java.util.Arrays.fill(e, Double.NaN);
        return e;
    }
}
