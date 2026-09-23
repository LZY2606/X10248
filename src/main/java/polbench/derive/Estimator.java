package polbench.derive;

import polbench.lin.M;

import java.util.*;

/**
 * Mueller estimator from multi-state intensity data.
 *
 * y = W x + noise, x = flattened 4x4 Mueller (row-major).
 * Full rank: unique OLS/GLS estimate with propagated covariance.
 * Rank deficient: the system is NOT force-inverted; min-norm candidate is
 * labelled non-unique together with the null space.
 */
public final class Estimator {
    public static final double NEAR_SINGULAR_KAPPA = 1e6;
    public static final double RANK_TOL = 1e-9;

    private Estimator() {}

    public static final class Result {
        public String status;
        public double kappa;
        public int rank;
        public int rows;
        public double[][] mueller;
        public double[][] covariance;
        public double[] residual;
        public double rss;
        public double reducedChi2;
        public List<double[]> nullspace = new ArrayList<>();
        public double[] singularValues;
        public String method;
        public double[][] whitened;
    }

    public static Result estimate(double[][] w, double[] y, double[][] cov, String method) {
        Result r = new Result();
        r.rows = w.length;
        r.method = method == null ? "OLS" : method;
        M svd = M.svd(w);
        r.singularValues = svd.sv;
        r.kappa = M.cond(w);
        r.rank = M.rank(w, RANK_TOL);
        double cutoff = RANK_TOL * svd.sv[0];

        if (r.rank < 16) {
            r.status = "SINGULAR";
            double[][] pinv = M.pinv(w, RANK_TOL);
            double[] xmin = M.mul(pinv, y);
            r.mueller = Optics.unflatten(xmin);
            for (int col = 0; col < 16; col++) {
                if (svd.sv[col] <= cutoff) {
                    double[] n = new double[16];
                    for (int k = 0; k < 16; k++) n[k] = svd.vt[col][k];
                    r.nullspace.add(n);
                }
            }
            r.residual = residuals(w, y, xmin);
            r.rss = M.dot(r.residual, r.residual);
            r.reducedChi2 = Double.NaN;
            r.covariance = null;
            return r;
        }

        double[] x;
        double[][] xtwx;
        if ("GLS".equals(r.method) && cov != null) {
            double[][] cinv = M.invSPD(cov);
            double[][] xtw = M.tmul(w, cinv);
            xtwx = M.mul(xtw, w);
            double[] xtwy = M.mul(xtw, y);
            double[][] covX = M.invSPD(xtwx);
            x = M.mul(covX, xtwy);
            r.covariance = covX;
            r.whitened = cinv;
        } else {
            double[][] xtx = M.mul(M.tr(w), w);
            double[] xtyv = new double[16];
            for (int i = 0; i < w.length; i++)
                for (int j = 0; j < 16; j++) xtyv[j] += w[i][j] * y[i];
            double[][] inv = M.invSPD(xtx);
            x = M.mul(inv, xtyv);
            r.covariance = inv;
            double varEst = M.dot(residual(w, y, x), residual(w, y, x)) / Math.max(1, w.length - 16);
            for (int i = 0; i < 16; i++)
                for (int j = 0; j < 16; j++) r.covariance[i][j] *= varEst;
        }
        r.mueller = Optics.unflatten(x);
        r.residual = residuals(w, y, x);
        r.rss = M.dot(r.residual, r.residual);
        r.reducedChi2 = r.rss / Math.max(1, w.length - 16);
        r.status = (r.kappa >= NEAR_SINGULAR_KAPPA) ? "NEAR_SINGULAR" : "UNIQUE";
        return r;
    }

    private static double[] residuals(double[][] w, double[] y, double[] x) {
        double[] pred = M.mul(w, x);
        double[] e = new double[y.length];
        for (int i = 0; i < y.length; i++) e[i] = y[i] - pred[i];
        return e;
    }

    public static double elementSigma(double[][] cov, int i, int j) {
        int k = i * 4 + j;
        return cov == null ? Double.NaN : Math.sqrt(Math.max(0, cov[k][k]));
    }
}
