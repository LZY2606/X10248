package com.polarlab.optics;

import com.polarlab.math.Linalg;
import com.polarlab.math.Mat;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimation from (dark-corrected) intensities with a declared covariance.
 *
 * Near-singular designs are never force-inverted: when the condition number exceeds the safe
 * threshold (or the design is rank deficient) the estimate is flagged UNSTABLE/UNDERDETERMINED,
 * a minimum-norm LS representative is still reported, and every legal null-space candidate is
 * retained so reviewers can see the ambiguity instead of a single fake answer.
 */
public final class Estimator {

    public enum Status { OK, NEAR_SINGULAR, UNDERDETERMINED }

    public static final class Candidate {
        public final String label;
        public final Mat mueller;
        public final String note;
        public Candidate(String label, Mat mueller, String note) {
            this.label = label;
            this.mueller = mueller;
            this.note = note;
        }
    }

    public static final class Result {
        public final Status status;
        public final double conditionNumber;
        public final double[] singularValues;
        public final int rank;
        public final List<Candidate> candidates;
        public final double[][] covariance;   // 16x16 covariance of stacked m
        public final double residualNorm;
        public final List<double[]> nullspace;

        Result(Status status, double cond, double[] sv, int rank, List<Candidate> c,
               double[][] cov, double res, List<double[]> ns) {
            this.status = status;
            this.conditionNumber = cond;
            this.singularValues = sv;
            this.rank = rank;
            this.candidates = c;
            this.covariance = cov;
            this.residualNorm = res;
            this.nullspace = ns;
        }
    }

    /** Whiten design and data with the Cholesky factor of the measurement covariance. */
    public static Result estimate(PolarimetricDesign design, double[] darkCorrected,
                                  double[][] covariance, double safeCondition) {
        int n = darkCorrected.length;
        double[][] l = Linalg.cholesky(covariance);
        // Solve C^{-1/2} W m = C^{-1/2} b via whitening rows: L^{-1}
        double[][] ww = new double[n][16];
        double[] bb = new double[n];
        for (int j = 0; j < 16; j++) {
            double[] col = new double[n];
            for (int i = 0; i < n; i++) col[i] = design.w.a[i][j];
            double[] wc = Linalg.triSolve(l, col, true);
            for (int i = 0; i < n; i++) ww[i][j] = wc[i];
        }
        double[] wb = Linalg.triSolve(l, darkCorrected.clone(), true);
        Mat wwMat = Mat.of(ww);

        double cond = design.conditionNumber(1e-12);
        Linalg.LSResult ls = Linalg.leastSquares(wwMat, wb, 1e-10);

        Status status;
        if (ls.rank < 16) status = Status.UNDERDETERMINED;
        else if (cond > safeCondition) status = Status.NEAR_SINGULAR;
        else status = Status.OK;

        List<Candidate> candidates = new ArrayList<>();
        Mat representative = unstack(ls.x);
        candidates.add(new Candidate("minimum-norm LS representative", representative,
                ls.nullDim > 0 ? "rank deficient; one representative of the full solution affine space"
                        : (status == Status.NEAR_SINGULAR ? "full rank but condition number " + fmt(cond)
                        + " exceeds safe threshold " + fmt(safeCondition) : "weighted least squares solution")));

        // Covariance propagation: (W^T C^-1 W)^+ sigma^2 style — only meaningful on resolved subspace.
        double[][] cov;
        if (ls.rank == 16) {
            Mat wtw = wwMat.transpose().mul(wwMat);
            Mat inv = Linalg.inverse(wtw);
            cov = inv.toArray();
        } else {
            cov = new double[16][16]; // unresolved: zeros encode "not determined" and null vectors carry ambiguity
        }

        return new Result(status, cond, design.singularValues(), ls.rank, candidates,
                cov, ls.residualNorm, ls.nullspace);
    }

    /** Build explicit affine candidates m0 + t*z for each null vector at offsets -1,0,+1 (scaled). */
    public static List<Candidate> nullspaceCandidates(Result result) {
        List<Candidate> out = new ArrayList<>(result.candidates);
        double[] m0 = stack(result.candidates.get(0).mueller);
        int idx = 1;
        for (double[] z : result.nullspace) {
            double scale = Math.max(1e-6, rms(m0) / Math.max(1e-12, rms(z)));
            for (double t : new double[]{-scale, scale}) {
                double[] mm = m0.clone();
                for (int i = 0; i < 16; i++) mm[i] += t * z[i];
                out.add(new Candidate("null-candidate z" + idx + " t=" + fmt(t), unstack(mm),
                        "same measurement fit; ambiguity from unresolved design null space"));
            }
            idx++;
        }
        return out;
    }

    static double[] stack(Mat m) {
        double[] x = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) x[4 * i + j] = m.a[i][j];
        return x;
    }

    static Mat unstack(double[] x) {
        Mat m = new Mat(4, 4);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) m.a[i][j] = x[4 * i + j];
        return m;
    }

    static double rms(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return Math.sqrt(s / v.length);
    }

    static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3g", d);
    }
}
