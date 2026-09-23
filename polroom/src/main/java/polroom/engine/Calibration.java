package polroom.engine;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import polroom.domain.Mat;

/**
 * Instrument calibration: dark subtraction, reference-sample gain correction,
 * and PSG/PSA instrument-matrix correction.  All correction is applied on the
 * derived layer only; raw intensities are never rewritten.
 */
public final class Calibration {

    public record Corrected(double[] intensities, double[][] covariance,
                            double[] gain, java.util.List<String> diagnostics) {
    }

    /**
     * @param raw           raw detector counts
     * @param dark          dark-frame counts (same length)
     * @param referenceGain multiplicative gain per state from the reference sample
     * @param rawCovariance measurement covariance (count^2), may be null
     */
    public static Corrected apply(double[] raw, double[] dark, double[] referenceGain,
                                  RealMatrix rawCovariance) {
        java.util.List<String> diagnostics = new java.util.ArrayList<>();
        double[] corrected = new double[raw.length];
        double[] gain = new double[raw.length];
        for (int k = 0; k < raw.length; k++) {
            double g = referenceGain == null ? 1.0 : referenceGain[k];
            if (!Double.isFinite(g) || Math.abs(g) < 1e-12) {
                diagnostics.add("reference gain invalid at index " + k + ": " + g);
                g = 1.0;
            }
            gain[k] = g;
            corrected[k] = (raw[k] - dark[k]) / g;
        }
        RealMatrix covariance = rawCovariance == null ? null : rawCovariance.copy();
        if (rawCovariance != null) {
            for (int i = 0; i < raw.length; i++) {
                for (int j = 0; j < raw.length; j++) {
                    covariance.setEntry(i, j, rawCovariance.getEntry(i, j)
                            / (gain[i] * gain[j]));
                }
            }
        }
        return new Corrected(corrected, covariance == null ? null : covariance.getData(),
                gain, diagnostics);
    }

    /**
     * Correct optical-state Stokes vectors with the measured instrument matrix
     * W (4x4): true state = W^{-1} measured state.  A singular instrument
     * matrix is reported, never force-inverted.
     */
    public record InstrumentResult(double[][] correctedStates, boolean singular,
                                   java.util.List<String> diagnostics) {
    }

    public static InstrumentResult correctStates(double[][] states, RealMatrix instrument) {
        java.util.List<String> diagnostics = new java.util.ArrayList<>();
        if (instrument == null) {
            return new InstrumentResult(states, false, diagnostics);
        }
        double det = Mat.determinant(instrument);
        if (Math.abs(det) < 1e-10) {
            diagnostics.add("instrument matrix singular (det=" + det + "); states kept uncorrected");
            return new InstrumentResult(states, true, diagnostics);
        }
        RealMatrix inv = Mat.inverse(instrument);
        double[][] out = new double[states.length][];
        for (int k = 0; k < states.length; k++) {
            out[k] = Mat.mv(inv, states[k]);
        }
        return new InstrumentResult(out, false, diagnostics);
    }

    /** Build a covariance matrix with an off-diagonal correlation term. */
    public static RealMatrix correlatedCovariance(double[] variance, double rho) {
        int n = variance.length;
        RealMatrix cov = new Array2DRowRealMatrix(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double localRho = i == j ? 1.0 : rho;
                cov.setEntry(i, j, localRho * Math.sqrt(variance[i] * variance[j]));
            }
        }
        return cov;
    }

    private Calibration() {
    }
}
