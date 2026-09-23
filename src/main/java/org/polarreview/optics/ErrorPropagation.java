package org.polarreview.optics;

import java.util.Random;

/**
 * Covariance propagation. Linear estimator covariance comes from the SVD
 * solver; derived scalar uncertainties (diattenuation, retardance,
 * depolarization, Cloude eigenvalues) are propagated by Monte Carlo draws from
 * the estimator covariance, which stays correct even when a candidate is near
 * the physical boundary.
 */
public final class ErrorPropagation {

    public static final class PropResult {
        public final double[] elementStd;
        public final double diattenuationStd;
        public final double retardanceStdRad;
        public final double depolarizationStd;
        public final double[] cloudeStd;
        public final int samples;

        PropResult(double[] e, double d, double r, double dep, double[] c, int n) {
            elementStd = e; diattenuationStd = d; retardanceStdRad = r;
            depolarizationStd = dep; cloudeStd = c; samples = n;
        }
    }

    private ErrorPropagation() {}

    public static PropResult monteCarlo(double[] mean, double[][] cov, long seed, int samples) {
        int p = mean.length;
        double[] std = new double[p];
        if (cov != null) {
            for (int i = 0; i < p; i++) std[i] = Math.sqrt(Math.max(0, cov[i][i]));
        }
        double[][] L = cov != null ? chol(cov) : null;

        double[] dSamples = new double[samples];
        double[] rSamples = new double[samples];
        double[] depSamples = new double[samples];
        double[][] eigSamples = new double[samples][4];
        double[] elemAcc = new double[p];
        double[] elemSq = new double[p];

        Random rnd = new Random(seed);
        for (int s = 0; s < samples; s++) {
            double[] x = new double[p];
            if (L != null) {
                double[] z = new double[p];
                for (int i = 0; i < p; i++) z[i] = rnd.nextGaussian();
                for (int i = 0; i < p; i++) {
                    double v = mean[i];
                    for (int j = 0; j <= i; j++) v += L[i][j] * z[j];
                    x[i] = v;
                }
            } else {
                System.arraycopy(mean, 0, x, 0, p);
            }
            for (int i = 0; i < p; i++) { elemAcc[i] += x[i]; elemSq[i] += x[i] * x[i]; }
            double[][] m = toMatrix(x);
            Decompositions.Factorization f = Decompositions.luChipmanForward(m);
            dSamples[s] = f.diattenuation;
            rSamples[s] = f.retardanceRad;
            depSamples[s] = f.depolarizationPower;
            double[][][] h = Cloude.coherency(m);
            HermitianJacobi ej = HermitianJacobi.decompose(h[0], h[1]);
            System.arraycopy(ej.eigenvalues, 0, eigSamples[s], 0, 4);
        }
        double[] elemStd = new double[p];
        for (int i = 0; i < p; i++) {
            double meanv = elemAcc[i] / samples;
            elemStd[i] = Math.sqrt(Math.max(0, elemSq[i] / samples - meanv * meanv));
        }
        return new PropResult(elemStd, sd(dSamples), sd(rSamples), sd(depSamples),
                sdColumns(eigSamples), samples);
    }

    private static double[][] chol(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                if (i == j) l[i][j] = Math.sqrt(Math.max(sum, 1e-30));
                else l[i][j] = sum / l[j][j];
            }
        }
        return l;
    }

    private static double sd(double[] x) {
        double m = 0;
        for (double v : x) m += v;
        m /= x.length;
        double s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / x.length);
    }

    private static double[] sdColumns(double[][] x) {
        int n = x.length, c = x[0].length;
        double[] out = new double[c];
        for (int j = 0; j < c; j++) {
            double[] col = new double[n];
            for (int i = 0; i < n; i++) col[i] = x[i][j];
            out[j] = sd(col);
        }
        return out;
    }

    public static double[][] toMatrix(double[] v) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++)
            System.arraycopy(v, 4 * i, m[i], 0, 4);
        return m;
    }
}
