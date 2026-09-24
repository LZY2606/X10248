package polmu.domain;

import polmu.linalg.Lin;

/**
 * Weighted least squares Mueller estimate m = (A^T W A)^-1 A^T W y.
 * Rule: a near-singular design matrix is never force-inverted; the estimate is
 * withheld and diagnostics are returned instead.
 */
public final class Estimator {
    public enum Status { OK, NEAR_SINGULAR_NOT_INVERTED }

    public static final class Result {
        public Status status;
        public double conditionNumber;
        public double[] m;          // 16 Mueller elements, null when not inverted
        public double[][] cov;      // 16x16 estimator covariance, null when not inverted
        public double[] residuals;  // y - A m, null when not inverted
    }

    public static Result estimate(DesignMatrix design, double[][] w, double[] y) {
        Result r = new Result();
        r.conditionNumber = design.conditionNumber;
        if (design.nearSingular) {
            r.status = Status.NEAR_SINGULAR_NOT_INVERTED;
            return r;
        }
        r.status = Status.OK;
        double[][] at = Lin.transpose(design.a);
        double[][] atw = Lin.mul(at, w);
        double[][] atwa = Lin.mul(atw, design.a);
        double[] atwy = Lin.mul(atw, y);
        double[][] atwaInv = Lin.inverse(atwa);
        r.m = Lin.mul(atwaInv, atwy);
        r.cov = atwaInv;
        double[] fit = Lin.mul(design.a, r.m);
        r.residuals = new double[y.length];
        for (int i = 0; i < y.length; i++) r.residuals[i] = y[i] - fit[i];
        return r;
    }
}
