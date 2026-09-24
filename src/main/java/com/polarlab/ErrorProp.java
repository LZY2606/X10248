package com.polarlab;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;

/** Covariance propagation by centered numeric Jacobian: Cov(f) = J Cov(x) J^T. */
public final class ErrorProp {
    private ErrorProp() {}

    public interface F { double[] apply(double[] x); }

    public static double[] sigmas(double[] x, RealMatrix cov, F f) {
        RealMatrix j = jacobian(x, f);
        RealMatrix cf = j.multiply(cov).multiply(j.transpose());
        double[] sig = new double[cf.getRowDimension()];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = Math.sqrt(Math.max(0, cf.getEntry(i, i)));
        }
        return sig;
    }

    public static RealMatrix jacobian(double[] x, F f) {
        double[] f0 = f.apply(x);
        double[][] j = new double[f0.length][x.length];
        double[] xp = x.clone();
        double[] xm = x.clone();
        double eps = 1e-7;
        for (int k = 0; k < x.length; k++) {
            double step = eps * Math.max(1, Math.abs(x[k]));
            xp[k] = x[k] + step;
            xm[k] = x[k] - step;
            double[] fp = f.apply(xp);
            double[] fm = f.apply(xm);
            for (int i = 0; i < f0.length; i++) {
                j[i][k] = (fp[i] - fm[i]) / (2 * step);
            }
            xp[k] = x[k];
            xm[k] = x[k];
        }
        return new Array2DRowRealMatrix(j, false);
    }
}
