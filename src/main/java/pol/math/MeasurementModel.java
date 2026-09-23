package pol.math;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear Mueller measurement model:
 *
 *   i_n = dark_n + k * a_n^T M g_n                       (raw detector counts)
 *
 * Reference-normalised observation:
 *   y_n = (i_n - dark_n) / r_n * m00_ref
 *   W_{n,:} = (a_n (x) g_n)^T        (row of the design matrix, 16 coefficients)
 *   y = W vec(M) + e
 *
 * Weighted least squares with an arbitrary (possibly correlated) covariance C:
 *   vec(M)_hat = (W^T C^{-1} W)^{-1} W^T C^{-1} y
 *
 * Near-singular designs are NOT force-inverted: the SVD rank, condition number and
 * null space are returned with status INDETERMINATE.
 */
public final class MeasurementModel {

    public enum Status { OK, NEAR_SINGULAR, SINGULAR }

    public static final class Config {
        public final double[][] generator; // n x 4 canonical generator Stokes
        public final double[][] analyzer;  // n x 4 canonical analyzer Stokes
        public final int sign;
        public final double tolerance;
        public Config(double[][] generator, double[][] analyzer, int sign, double tolerance) {
            this.generator = generator; this.analyzer = analyzer;
            this.sign = sign; this.tolerance = tolerance;
        }
    }

    public static final class Result {
        public Status status;
        public double[][] design;          // n x 16
        public double[] muellerVec;        // null when not uniquely estimable
        public double[][] covariance16;    // null when not uniquely estimable
        public double conditionNumber;
        public int rank;
        public List<double[]> nullspaceBasis = new ArrayList<>();
        public List<double[]> rowspaceBasis = new ArrayList<>();
        public double[][] svdU, svdVT;
        public double[] singularValues;
        public double residualNorm;
        public double[] fitted;
        public double[] observed;
        public String diagnostic;
    }

    private MeasurementModel() {}

    public static double[] observationRow(double[] analyzerCanonical, double[] generatorCanonical, int sign) {
        double[] a = Polar.normalizeStokes(analyzerCanonical);
        double[] g = Polar.normalizeStokes(generatorCanonical);
        double[] as = {a[0], sign * a[1], sign * a[2], sign * a[3]};
        double[] gs = {g[0], sign * g[1], sign * g[2], sign * g[3]};
        double[] row = new double[16];
        int k = 0;
        for (int ia = 0; ia < 4; ia++)
            for (int ig = 0; ig < 4; ig++) row[k++] = 0.5 * as[ia] * gs[ig];
        return row;
    }

    public static double[][] designMatrix(Config cfg) {
        int n = cfg.generator.length;
        double[][] w = new double[n][16];
        for (int i = 0; i < n; i++) {
            w[i] = observationRow(cfg.analyzer[i], cfg.generator[i], cfg.sign);
        }
        return w;
    }

    /** Estimate with independent observation variances (uncorrelated case). */
    public static Result estimate(Config cfg, double[] y, double[] variance) {
        int n = y.length;
        double[][] c = Mat.create(n, n);
        for (int i = 0; i < n; i++) c[i][i] = variance[i];
        return estimateCorrelated(cfg, y, c);
    }

    public static Result estimateCorrelated(Config cfg, double[] y, double[][] covariance) {
        Result r = new Result();
        r.observed = y.clone();
        double[][] w = designMatrix(cfg);
        r.design = w;
        Mat.SVD svd = Mat.svd(w);
        r.svdU = svd.u; r.svdVT = svd.vt; r.singularValues = svd.singularValues;
        r.conditionNumber = svd.condition();
        r.rank = svd.numericalRank(cfg.tolerance);
        r.nullspaceBasis = svd.nullspaceBasis(cfg.tolerance);
        r.rowspaceBasis = svd.rowspaceBasis(cfg.tolerance);

        if (r.rank < 16) {
            r.status = r.rank == 0 ? Status.SINGULAR : Status.NEAR_SINGULAR;
            r.diagnostic = "design matrix numerical rank " + r.rank + "/16 at tolerance "
                    + cfg.tolerance + "; no unique inversion, null-space dimension "
                    + (16 - r.rank);
            return r;
        }

        // Whiten: C^{-1}
        Mat.SVD csvd = Mat.svd(covariance);
        double[] cs = csvd.singularValues;
        double[][] invSqrtC = Mat.create(covariance.length, covariance.length);
        double[][] u = csvd.u, vt = csvd.vt;
        for (int a = 0; a < cs.length; a++)
            for (int b = 0; b < cs.length; b++) {
                double sum = 0;
                for (int k = 0; k < cs.length; k++) sum += u[a][k] * (1.0 / Math.sqrt(cs[k])) * vt[k][b];
                invSqrtC[a][b] = sum;
            }
        double[][] ww = Mat.mult(invSqrtC, w);
        double[] yy = Mat.mult(invSqrtC, y);

        Mat.SVD nsvd = Mat.svd(ww);
        double[][] wpinv = pseudoInverse(nsvd);
        double[] mv = Mat.mult(wpinv, yy);
        r.muellerVec = mv;
        // covariance: (W^T C^{-1} W)^{-1}
        double[][] wt = Mat.transpose(ww);
        double[][] normal = Mat.mult(wt, ww);
        r.covariance16 = Mat.inverse(normal);
        r.fitted = Mat.mult(w, mv);
        double rn2 = 0;
        for (int i = 0; i < y.length; i++) rn2 += (y[i] - r.fitted[i]) * (y[i] - r.fitted[i]);
        r.residualNorm = Math.sqrt(rn2);
        r.status = r.conditionNumber > 1.0 / cfg.tolerance ? Status.NEAR_SINGULAR : Status.OK;
        if (r.status == Status.NEAR_SINGULAR) {
            r.diagnostic = "rank formally 16 but condition number " + String.format("%.3e", r.conditionNumber)
                    + " exceeds 1/tolerance; estimate retained but flagged near-singular";
        }
        return r;
    }

    private static double[][] pseudoInverse(Mat.SVD s) {
        double[][] v = Mat.transpose(s.vt);
        double[][] ut = Mat.transpose(s.u);
        int k = Math.min(v[0].length, ut.length);
        double[][] out = Mat.create(v.length, ut.length);
        for (int i = 0; i < v.length; i++)
            for (int j = 0; j < ut.length; j++) {
                double sum = 0;
                for (int n = 0; n < s.singularValues.length; n++) sum += v[i][n] * (1.0 / s.singularValues[n]) * ut[n][j];
                out[i][j] = sum;
            }
        return out;
    }

    public static double[][] vecToMueller(double[] v) {
        double[][] m = Mat.create(4, 4);
        for (int i = 0; i < 16; i++) m[i / 4][i % 4] = v[i];
        return m;
    }

    /** Corrected observations: dark subtraction then reference normalisation. */
    public static double[] correctObservations(double[] intensity, double[] dark, double[] reference,
                                               double referenceM00, String[] diagnostics) {
        int n = intensity.length;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sample = intensity[i] - dark[i];
            double ref = reference[i] - dark[i];
            if (ref <= 0) {
                diagnostics[i] = "non-positive reference after dark subtraction; value kept raw with diagnostic";
                y[i] = sample;
            } else {
                y[i] = sample / ref * referenceM00;
            }
        }
        return y;
    }

    /** Propagate variance through dark subtraction and reference division (delta method). */
    public static double[] propagatedVariance(double[] intensity, double[] dark, double[] reference,
                                              double refM00, double sampleVar, double darkVar, double refVar,
                                              double[] corrSampleRef) {
        int n = intensity.length;
        double[] var = new double[n];
        for (int i = 0; i < n; i++) {
            double s = intensity[i] - dark[i];
            double r = reference[i] - dark[i];
            if (r <= 0) { var[i] = sampleVar + darkVar; continue; }
            double f = refM00 / r;
            double dS = f;
            double dR = -refM00 * s / (r * r);
            double dD = -f + refM00 * s / (r * r);
            double cov = corrSampleRef == null ? 0 : corrSampleRef[i];
            var[i] = dS * dS * sampleVar + dR * dR * refVar + dD * dD * darkVar
                    + 2 * dS * dR * cov;
            if (var[i] <= 0) var[i] = Double.NaN;
        }
        return var;
    }
}
