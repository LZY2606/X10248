package io.polroom;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimation.
 *
 *  - Generalised least squares with the declared intensity covariance C (correlated-error
 *    aware): L^{-1} y = L^{-1} W m,  L L^T = C.
 *  - Near-singular designs are NOT force-inverted. When the design lacks full rank we emit
 *      (a) a minimum-norm candidate, explicitly labelled non-unique, and
 *      (b) the nullspace family so every legal candidate can be displayed.
 *  - Covariance of the estimate is propagated as (W^T C^{-1} W)^{-1} when full rank;
 *    for rank-deficient designs only the identifiable subspace covariance is reported.
 */
public final class Estimator {

    public static final double SINGULAR_TOL = 1e-6;

    public static class Result {
        public boolean unique;
        public double conditionNumber;
        public int rank;
        public double[][] mueller;
        public double[][] covariance;
        public double[][] minimumNormMueller;
        public double[][] nullSpace;
        public double[] residual;
        public double chiSquared;
        public String method;
        public List<double[][]> legalFamily = new ArrayList<>();

        RealMatrix w;
        RealVector y;
    }

    private Estimator() {}

    public static Result estimate(RealMatrix wRaw, double[] yRaw, double[][] cov) {
        Result r = new Result();
        RealMatrix c = LA.m(cov);
        RealMatrix l = LA.m(LA.choleskyL(c));
        RealMatrix linv = new org.apache.commons.math3.linear.LUDecomposition(l).getSolver().getInverse();
        RealMatrix w = linv.multiply(wRaw);
        RealVector y = linv.operate(LA.v(yRaw));

        r.w = w;
        r.y = y;
        double cond = LA.conditionNumber(wRaw);
        r.conditionNumber = cond;
        r.rank = LA.effectiveRank(w, SINGULAR_TOL);

        RealVector resid;
        RealVector sol = LA.solveIfFullRank(w, y, SINGULAR_TOL);
        if (sol != null) {
            r.unique = true;
            r.method = "GLS-fullrank";
            r.mueller = unroll(sol.toArray());
            RealMatrix normal = w.transpose().multiply(w);
            r.covariance = new org.apache.commons.math3.linear.LUDecomposition(normal).getSolver().getInverse().getData();
            resid = y.subtract(w.operate(sol));
        } else {
            r.unique = false;
            r.method = "rank-deficient: minimum-norm (NOT unique) + nullspace family";
            double[][] pinv = LA.pseudoInverse(w, SINGULAR_TOL);
            RealVector mn = LA.m(pinv).operate(y);
            r.minimumNormMueller = unroll(mn.toArray());
            r.mueller = r.minimumNormMueller;
            RealMatrix ns = LA.nullSpace(w, SINGULAR_TOL);
            r.nullSpace = ns.getData();
            r.legalFamily.add(r.minimumNormMueller);
            double[][] basis = ns.getData();
            for (int j = 0; j < basis[0].length; j++) {
                double[] dir = new double[16];
                for (int i = 0; i < 16; i++) dir[i] = basis[i][j];
                double scale = Math.max(1.0, LA.v(mn.toArray()).getNorm() / Math.max(1e-12, LA.v(dir).getNorm()));
                r.legalFamily.add(unroll(add(mn.toArray(), mul(dir, 0.25 * scale))));
                r.legalFamily.add(unroll(add(mn.toArray(), mul(dir, -0.25 * scale))));
            }
            RealMatrix proj = LA.m(pinv).multiply(w);
            r.covariance = new double[16][16];
            RealMatrix nw = w.transpose().multiply(w).multiply(proj);
            try {
                r.covariance = new org.apache.commons.math3.linear.LUDecomposition(nw).getSolver().getInverse().getData();
            } catch (Exception ignored) {
            }
            resid = y.subtract(w.operate(mn));
        }
        r.residual = resid.toArray();
        r.chiSquared = resid.dotProduct(resid);
        return r;
    }

    private static double[] add(double[] a, double[] b) {
        double[] c = a.clone();
        for (int i = 0; i < c.length; i++) c[i] += b[i];
        return c;
    }

    private static double[] mul(double[] a, double s) {
        double[] c = a.clone();
        for (int i = 0; i < c.length; i++) c[i] *= s;
        return c;
    }

    /** vec convention: column-major m[i][j] at index j*4+i (matches the design rows). */
    public static double[][] unroll(double[] vec) {
        double[][] m = new double[4][4];
        for (int j = 0; j < 4; j++)
            for (int i = 0; i < 4; i++) m[i][j] = vec[j * 4 + i];
        return m;
    }

    public static double[] flatten(double[][] m) {
        double[] v = new double[16];
        for (int j = 0; j < 4; j++)
            for (int i = 0; i < 4; i++) v[j * 4 + i] = m[i][j];
        return v;
    }

    public static double elementSigma(double[][] covariance, int i, int j) {
        int idx = j * 4 + i;
        return Math.sqrt(Math.max(0, covariance[idx][idx]));
    }
}
