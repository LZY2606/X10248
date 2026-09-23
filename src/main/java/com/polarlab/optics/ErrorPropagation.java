package com.polarlab.optics;

import com.polarlab.math.Mat;

/**
 * Element-wise error propagation from the 16x16 Mueller covariance. Diagonal sigmas are shown
 * per element; correlated versions of the covariance also provide an off-diagonal correlation
 * matrix and joint confidence radii for scalar summaries.
 */
public final class ErrorPropagation {

    private ErrorPropagation() {}

    public static double[] elementSigmas(double[][] cov16) {
        double[] sig = new double[16];
        for (int k = 0; k < 16; k++) sig[k] = Math.sqrt(Math.max(0, cov16[k][k]));
        return sig;
    }

    public static double[][] correlationMatrix(double[][] cov16) {
        double[][] r = new double[16][16];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                double d = Math.sqrt(cov16[i][i] * cov16[j][j]);
                r[i][j] = d > 0 ? cov16[i][j] / d : 0;
            }
        }
        return r;
    }

    /** Propagated sigma for a linear functional f^T m (e.g. one element of a decomposition). */
    public static double functionalSigma(double[] f, double[][] cov16) {
        double v = 0;
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) v += f[i] * cov16[i][j] * f[j];
        return Math.sqrt(Math.max(0, v));
    }

    /** RMS element sigma, used as a compact quality number on the review page. */
    public static double rmsSigma(Mat m, double[][] cov16) {
        double[] s = elementSigmas(cov16);
        double sum = 0;
        for (double v : s) sum += v * v;
        return Math.sqrt(sum / 16);
    }
}
