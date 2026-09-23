package org.polarimetry.domain;

import java.util.Arrays;

/**
 * Measurement design W with y_k = s_a^T M s_g = W[k,:] vec(M) (row-major).
 * Dark subtraction and the reference-sample gain are applied to the data, not
 * folded into W; instrument matrices transform the state vectors.
 */
public final class DesignMatrix {
    public final Linear W;
    public final double[] y;
    public final Linear cov;           // N x N intensity covariance (counts^2)
    public final double dark;          // dark counts subtracted
    public final double refGain;       // reference-sample gain applied
    public final Linear.Pseudoinverse pinv;
    public final Linear whitened;      // cov^{-1/2} W (kept for reporting)
    public static final double NEAR_SINGULAR_REL_TOL = 1e-6;

    private DesignMatrix(Linear W, double[] y, Linear cov, double dark, double refGain,
                         Linear.Pseudoinverse pinv, Linear whitened) {
        this.W = W; this.y = y; this.cov = cov; this.dark = dark; this.refGain = refGain;
        this.pinv = pinv; this.whitened = whitened;
    }

    /** Build the design with instrument-corrected states. */
    public static DesignMatrix build(MeasurementPlan plan,
                                     double darkCounts,
                                     double refGain,
                                     Linear instrumentGenerator, // 4x4 or null
                                     Linear instrumentAnalyzer) {
        int n = plan.measurementCount();
        Linear W = Linear.zeros(n, 16);
        double[] y = new double[n];
        for (int k = 0; k < n; k++) {
            double[] sg = corrected(plan.generators().get(k).canonicalStokes(), instrumentGenerator);
            double[] sa = corrected(plan.analyzers().get(k).canonicalStokes(), instrumentAnalyzer);
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    W.m.set(k, i * 4 + j, sa[i] * sg[j]);
            y[k] = (plan.rawIntensities()[k] - darkCounts) / refGain;
        }
        Linear cov = Linear.identity(n);
        Linear whitened = W.copy();
        Linear.Pseudoinverse pinv = W.pseudoinverse(NEAR_SINGULAR_REL_TOL);
        return new DesignMatrix(W, y, cov, darkCounts, refGain, pinv, whitened);
    }

    private static double[] corrected(double[] s, Linear inst) {
        if (inst == null) return s;
        // instrument matrix maps measured states back to ideal sample states
        Linear out = inst.mult(Linear.column(s));
        double[] r = new double[4];
        for (int i = 0; i < 4; i++) r[i] = out.get(i, 0);
        return r;
    }

    public DesignMatrix withCovariance(Linear newCov) {
        return new DesignMatrix(W, y, newCov, dark, refGain, pinv, whiten(newCov, W));
    }

    private static Linear whiten(Linear cov, Linear W) {
        // diagonal whitening is enough for visualization; estimation uses pinv of W directly
        double[] scale = new double[cov.rows];
        for (int i = 0; i < cov.rows; i++) scale[i] = 1.0 / Math.sqrt(Math.max(cov.get(i, i), 1e-30));
        Linear ww = Linear.zeros(W.rows, W.cols);
        for (int i = 0; i < W.rows; i++)
            for (int j = 0; j < W.cols; j++) ww.m.set(i, j, W.get(i, j) * scale[i]);
        return ww;
    }

    public double conditionNumber() { return pinv.rankDeficient()
            ? Double.POSITIVE_INFINITY : pinv.pinv == null ? Double.POSITIVE_INFINITY
            : W.svd().conditionNumber(); }

    public int rank() { return pinv.rank; }
    public double[] singularValues() { return Arrays.copyOf(pinv.singularValues, pinv.singularValues.length); }
    public Linear nullspace() { return pinv.nullspace; }
    public boolean rankDeficient() { return pinv.rankDeficient(); }
}
