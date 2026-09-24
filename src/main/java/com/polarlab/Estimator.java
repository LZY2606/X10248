package com.polarlab;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimator from generator/analyzer intensity measurements.
 *
 * <p>Hard rules encoded here:
 * <ul>
 *   <li>(near) singular design matrix is never force-inverted: no unique claim, no hidden pseudoinverse;</li>
 *   <li>a minimum-norm point may be shown ONLY as a labeled diagnostic candidate;</li>
 *   <li>non-physical candidates keep their values and carry violation flags (no projection).</li>
 * </ul>
 */
public final class Estimator {
    private Estimator() {}

    public static final double DEFAULT_COND_THRESHOLD = 1e10;
    public static final double SVD_TOL = 1e-12;

    /** One intensity row after source separation (raw + dark + gain references live elsewhere). */
    public record Row(String measurementId, double[] analyzerCanon, double[] generatorCanon,
                      double raw, double dark, double gain, double rawVar, double darkVar,
                      double gainRelVar) {}

    public record Candidate(String kind, double[] mVec, double score, boolean physical,
                            List<Mueller.Violation> violations, String note) {}

    public record Result(String status, double cond, int rank, int nullity, double[] singularValues,
                         double[] solution, RealMatrix cov, double chi2, int dof,
                         List<Candidate> candidates, List<double[]> nullspace, String message) {
        public double[][] mueller() {
            double[][] m = new double[4][4];
            for (int i = 0; i < 4; i++) {
                System.arraycopy(solution, 4 * i, m[i], 0, 4);
            }
            return m;
        }
        public double[][] covMatrix() { return cov.getData(); }
    }

    public static Result estimate(List<Row> rows, double physTol) {
        return estimate(rows, physTol, DEFAULT_COND_THRESHOLD);
    }

    public static Result estimate(List<Row> rows, double physTol, double condThreshold) {
        int n = rows.size();
        double[][] x = new double[n][16];
        double[] y = new double[n];
        double[][] sigma = new double[n][n];
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            x[i] = Mueller.designRow(r.analyzerCanon, r.generatorCanon);
            double yi = (r.raw - r.dark) / r.gain;
            y[i] = yi;
        }
        // covariance: raw + dark variance scaled by 1/g^2, gain uncertainty fully correlated
        for (int i = 0; i < n; i++) {
            Row ri = rows.get(i);
            double localVar = (ri.rawVar + ri.darkVar) / (ri.gain * ri.gain);
            double gainVar = y[i] * y[i] * ri.gainRelVar * ri.gainRelVar;
            sigma[i][i] = localVar + gainVar;
            for (int j = i + 1; j < n; j++) {
                Row rj = rows.get(j);
                double cov = y[i] * y[j] * Math.min(ri.gainRelVar, rj.gainRelVar)
                        * Math.min(ri.gainRelVar, rj.gainRelVar);
                sigma[i][j] = cov;
                sigma[j][i] = cov;
            }
        }
        RealMatrix xm = new Array2DRowRealMatrix(x, false);
        RealMatrix sigm = new Array2DRowRealMatrix(sigma, false);
        Mat.Svd svd = Mat.svd(xm, SVD_TOL);

        RealMatrix w = Mat.inverseFullRank(sigm);
        double noiseLevel = 0;
        for (int i = 0; i < n; i++) {
            noiseLevel += sigma[i][i];
        }
        noiseLevel = Math.sqrt(noiseLevel / Math.max(1, n));

        if (svd.rank() == 16 && svd.cond() <= condThreshold) {
            Mat.Wls wls = Mat.wlsFullRank(xm, y, w);
            double chi = 0;
            double[] resid = new double[n];
            for (int i = 0; i < n; i++) {
                double pred = 0;
                for (int k = 0; k < 16; k++) {
                    pred += x[i][k] * wls.x()[k];
                }
                resid[i] = y[i] - pred;
            }
            // chi2 with correlated residual
            double[] wr = w.operate(resid);
            for (int i = 0; i < n; i++) {
                chi += resid[i] * wr[i];
            }
            double[][] mm = toMatrix(wls.x());
            List<Mueller.Violation> v = Mueller.physicalViolations(mm, physTol);
            boolean phys = v.stream().allMatch(Mueller.Violation::ok);
            List<Candidate> cands = new ArrayList<>();
            cands.add(new Candidate("wls", wls.x(), chi, phys, v,
                    phys ? "weighted least squares, physical" : "weighted least squares, NON-PHYSICAL: flagged, not projected"));
            return new Result(phys ? "OK" : "OK_NONPHYSICAL", svd.cond(), 16, 0, svd.s(),
                    wls.x(), wls.cov(), chi, n - 16, cands, null,
                    phys ? "unique full-rank estimate" : "unique fit but violates physical constraints");
        }

        // SINGULAR or NEAR-SINGULAR: do NOT invert as if unique.
        RealMatrix pinv = Mat.pseudoInverse(xm);
        double[] minNorm = pinv.operate(y);
        List<double[]> dirs = new ArrayList<>();
        int nDirs;
        String status;
        String message;
        if (svd.rank() < 16) {
            nDirs = 16 - svd.rank();
            RealMatrix ns = svd.nullspace(nDirs);
            for (int k = 0; k < nDirs; k++) {
                dirs.add(ns.getColumn(k));
            }
            status = "SINGULAR";
            message = "design matrix rank " + svd.rank() + " < 16; solution is a " + nDirs
                    + "-parameter family; inversion refused";
        } else {
            nDirs = 1;
            dirs.add(svd.v().getColumn(15));
            status = "NEAR_SINGULAR";
            message = String.format("condition number %.3e exceeds threshold %.0e; unstable inversion refused",
                    svd.cond(), condThreshold);
        }
        List<Candidate> cands = enumerateCandidates(minNorm, dirs, noiseLevel, svd, physTol);
        double[][] mm = toMatrix(minNorm);
        List<Mueller.Violation> v = Mueller.physicalViolations(mm, physTol);
        cands.add(0, new Candidate("minimum-norm diagnostic", minNorm.clone(), Double.NaN,
                v.stream().allMatch(Mueller.Violation::ok), v,
                "pseudoinverse minimum-norm point; NOT claimed as the solution"));
        return new Result(status, svd.cond(), svd.rank(), nDirs, svd.s(),
                null, null, Double.NaN, n - svd.rank(), cands, dirs, message);
    }

    /**
     * Enumerate feasible family members. For the fixed case nullity is 1; we scan the coefficient
     * of each undetermined direction and record every legal (physical) candidate plus the
     * feasibility interval. Higher nullity falls back to a bounded grid.
     */
    static List<Candidate> enumerateCandidates(double[] base, List<double[]> dirs, double noiseLevel,
                                               Mat.Svd svd, double physTol) {
        List<Candidate> out = new ArrayList<>();
        if (dirs.size() == 1) {
            double[] d = dirs.get(0);
            // coefficient scale: data noise divided by the smallest non-zero singular value
            double sMin = svd.smallestPositive();
            double span = 6 * noiseLevel / sMin;
            List<double[]> intervals = feasibleIntervals1d(base, d, span, physTol, 600);
            int tag = 0;
            for (double[] iv : intervals) {
                double[] pts = {iv[0], 0.5 * (iv[0] + iv[1]), iv[1]};
                for (double t : pts) {
                    double[] m = addScaled(base, d, t);
                    List<Mueller.Violation> vv = Mueller.physicalViolations(toMatrix(m), physTol);
                    out.add(new Candidate("family-1d#" + (tag++), m, t, true, vv,
                            String.format("nullspace coefficient t=%.4g within feasible interval [%.4g, %.4g]", t, iv[0], iv[1])));
                }
            }
            // also record the raw endpoints span points that are non-physical, flagged
            for (double t : new double[] {-span, span}) {
                double[] m = addScaled(base, d, t);
                List<Mueller.Violation> vv = Mueller.physicalViolations(toMatrix(m), physTol);
                boolean ok = vv.stream().allMatch(Mueller.Violation::ok);
                if (!ok) {
                    out.add(new Candidate("family-1d-nonphysical", m, t, false, vv,
                            "family member outside physical set; violations flagged, value retained"));
                }
            }
            return out;
        }
        // generic grid fallback (fixed case does not need it; kept honest for >1 null directions)
        int grid = 7;
        double sMin = svd.smallestPositive();
        double span = 3 * noiseLevel / sMin;
        for (int combo = 0; combo < Math.pow(grid, dirs.size()); combo++) {
            int c = combo;
            double[] m = base.clone();
            for (double[] d : dirs) {
                double t = -span + 2 * span * (c % grid) / (grid - 1);
                c /= grid;
                m = addScaled(m, d, t);
            }
            List<Mueller.Violation> vv = Mueller.physicalViolations(toMatrix(m), physTol);
            if (vv.stream().allMatch(Mueller.Violation::ok)) {
                out.add(new Candidate("family-grid", m, Double.NaN, true, vv, "feasible grid member"));
            }
        }
        return out;
    }

    private static List<double[]> feasibleIntervals1d(double[] base, double[] d, double span,
                                                      double physTol, int samples) {
        List<double[]> intervals = new ArrayList<>();
        double prevT = -span;
        boolean prev = Mueller.isPhysical(toMatrix(addScaled(base, d, prevT)), physTol);
        double start = prev ? prevT : Double.NaN;
        for (int k = 1; k <= samples; k++) {
            double t = -span + 2 * span * k / samples;
            boolean cur = Mueller.isPhysical(toMatrix(addScaled(base, d, t)), physTol);
            if (cur && !prev) {
                start = bisect(base, d, prevT, t, physTol, true);
            } else if (!cur && prev) {
                double end = bisect(base, d, prevT, t, physTol, false);
                if (!Double.isNaN(start)) {
                    intervals.add(new double[] {start, end});
                }
                start = Double.NaN;
            }
            prev = cur;
            prevT = t;
        }
        if (!Double.isNaN(start)) {
            intervals.add(new double[] {start, span});
        }
        return intervals;
    }

    private static double bisect(double[] base, double[] d, double lo, double hi, double tol, boolean entering) {
        for (int it = 0; it < 40; it++) {
            double mid = 0.5 * (lo + hi);
            boolean phys = Mueller.isPhysical(toMatrix(addScaled(base, d, mid)), tol);
            if (entering == phys) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double[] addScaled(double[] base, double[] d, double t) {
        double[] m = base.clone();
        for (int i = 0; i < 16; i++) {
            m[i] += t * d[i];
        }
        return m;
    }

    private static double[][] toMatrix(double[] v) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(v, 4 * i, m[i], 0, 4);
        }
        return m;
    }
}
