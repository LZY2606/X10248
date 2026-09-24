package pol.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

/**
 * Mueller estimator from intensity measurements.
 *
 * Measurement model: y_i = g_i^T M a_i, with g_i the generator Stokes vector,
 * a_i the analyzer Stokes vector (both in canonical IQUV after explicit
 * reordering), so the design row is kron(a_i^T, g_i^T) acting on vec(M).
 *
 * Near-singular designs are solved with SVD and flagged; truly rank-deficient
 * designs are never force-inverted: the minimum-norm vector plus the complete
 * null space are returned as multiple legal candidates. Instrument correction
 * (dark frame + calibrated instrument matrices from a reference sample) is an
 * explicit, versioned path, never an in-place mutation of raw data.
 */
public final class Estimator {
    public static final double DEFAULT_RCOND = 1e-8;
    public static final double NEAR_SINGULAR_COND = 1e3;

    private Estimator() {
    }

    public record Inputs(
            List<RawMeasurement> measurements,
            Map<String, OpticalState> generatorsById,
            Map<String, OpticalState> analyzersById,
            DarkFrame dark,
            InstrumentMatrix instrument,
            ReferenceSample reference,
            Covariance covariance,
            WavelengthPoint wavelength,
            StokesOrder order,
            RotationConvention rotation,
            String estimatorMode,
            Double rcondOverride) {
    }

    public static EstimateResult estimate(Inputs inputs) {
        double rcond = inputs.rcondOverride() == null ? DEFAULT_RCOND : inputs.rcondOverride();
        List<String> notes = new ArrayList<>();
        List<RawMeasurement> usable = new ArrayList<>();
        for (RawMeasurement m : inputs.measurements()) {
            if (m.usable()) {
                usable.add(m);
            } else {
                notes.add("measurement " + m.id() + " excluded: " + m.diagnostic());
            }
        }
        int n = usable.size();
        DMatrixRMaj raw = new DMatrixRMaj(n, 1);
        DMatrixRMaj corrected = new DMatrixRMaj(n, 1);
        DMatrixRMaj design = new DMatrixRMaj(n, 16);

        DMatrixRMaj w = inputs.instrument() == null ? null : inputs.instrument().analyzerW();
        DMatrixRMaj p = inputs.instrument() == null ? null : inputs.instrument().generatorP();
        String correctionPath = inputs.instrument() == null
                ? "direct: ideal state vectors; dark=" + (inputs.dark() == null ? "none" : inputs.dark().version())
                : "calibrated: dark=" + safe(inputs.dark() == null ? null : inputs.dark().version())
                        + "; reference=" + inputs.instrument().referenceVersion()
                        + "; instrument=" + inputs.instrument().version();

        for (int i = 0; i < n; i++) {
            RawMeasurement m = usable.get(i);
            raw.set(i, 0, m.intensityValue());
            double darkValue = darkValue(inputs.dark(), m.analyzerStateId(), i);
            corrected.set(i, 0, m.intensityValue() - darkValue);

            double[] gRaw = stateVector(inputs.generatorsById().get(m.generatorStateId()), inputs.order());
            double[] aRaw = stateVector(inputs.analyzersById().get(m.analyzerStateId()), inputs.order());
            double[] g = applyRotation(gRaw, inputs.rotation());
            double[] a = applyRotation(aRaw, inputs.rotation());
            if (w != null && p != null) {
                a = applyInstrumentSide(w, a);
                g = applyInstrumentSide(p, g);
            }
            double[] row = kronRow(g, a);
            for (int j = 0; j < 16; j++) {
                design.set(i, j, row[j]);
            }
        }

        DMatrixRMaj rhs = corrected;
        DMatrixRMaj whitening = null;
        if (inputs.covariance() != null && inputs.covariance().matrix() != null) {
            DMatrixRMaj c = inputs.covariance().matrix();
            if (c.numRows == n && c.numCols == n) {
                whitening = whitening(c, notes);
                design = Mtx.multiply(whitening, design);
                rhs = Mtx.multiply(whitening, corrected);
                notes.add("GLS whitening with covariance " + inputs.covariance().version()
                        + (inputs.covariance().correlated() ? " (correlated)" : " (diagonal)"));
            } else {
                notes.add("covariance " + inputs.covariance().version() + " has shape "
                        + c.numRows + "x" + c.numCols + " but y has " + n + " elements; ignored for fit, retained at source");
            }
        }

        Fit fit = Mtx.solveLeastSquares(design, rhs, rcond, false);
        DMatrixRMaj vec = fit.solution();
        DMatrixRMaj mueller = toMueller(vec);

        List<DMatrixRMaj> nullDirs = new ArrayList<>();
        List<DMatrixRMaj> candidates = new ArrayList<>();
        candidates.add(mueller);
        if (fit.rankDeficient()) {
            for (int k = fit.rank(); k < 16; k++) {
                DMatrixRMaj dir = rightSingularVector(design, k);
                nullDirs.add(toMueller(dir));
                candidates.add(toMueller(dir));
            }
            notes.add("rank " + fit.rank() + "/16: " + fit.nullity()
                    + " free direction(s); all legal candidates retained, none force-fit to uniqueness");
        }
        String status;
        if (fit.rankDeficient()) {
            status = "RANK_DEFICIENT";
        } else if (fit.conditionNumber() > NEAR_SINGULAR_COND) {
            status = "NEAR_SINGULAR";
            notes.add("design condition number " + scientific(fit.conditionNumber())
                    + " exceeds " + NEAR_SINGULAR_COND + ": solved by SVD, uncertainty inflation expected");
        } else {
            status = "OK";
        }

        DMatrixRMaj cov = Mtx.covariance(design, rcond, scale(fit));
        for (int k = fit.rank(); k < 16; k++) {
            // null directions are unconstrained: mark infinite variance rather than zero
            DMatrixRMaj dir = rightSingularVector(design, k);
            for (int i = 0; i < 16; i++) {
                if (Math.abs(dir.get(i, 0)) > 1e-9) {
                    cov.set(i, i, Double.POSITIVE_INFINITY);
                }
            }
        }
        double[] sigma = new double[16];
        for (int i = 0; i < 16; i++) {
            double v = cov.get(i, i);
            sigma[i] = v >= 0 ? Math.sqrt(v) : Double.NaN;
        }

        Fingerprint fp = new Fingerprint(
                inputs.order().label(),
                inputs.rotation().code(),
                inputs.dark() == null ? "none" : inputs.dark().version(),
                inputs.reference() == null
                        ? (inputs.instrument() == null ? "none" : inputs.instrument().referenceVersion())
                        : inputs.reference().version(),
                inputs.instrument() == null ? "ideal" : inputs.instrument().version(),
                inputs.covariance() == null ? "none" : inputs.covariance().version(),
                inputs.wavelength() == null ? "none" : inputs.wavelength().id(),
                inputs.estimatorMode() == null ? "svd_ols" : inputs.estimatorMode(),
                rcond);

        return new EstimateResult(fp.text(), status, mueller, cov, sigma, fit, n, 16,
                nullDirs, candidates, correctionPath, notes);
    }

    private static String safe(String s) {
        return s == null ? "none" : s;
    }

    private static double scale(Fit fit) {
        double v = fit.residualVariance();
        return Double.isNaN(v) || v <= 0 ? 1.0 : v;
    }

    private static double darkValue(DarkFrame dark, String analyzerId, int rowIndex) {
        if (dark == null || dark.values() == null || dark.values().isEmpty()) {
            return 0.0;
        }
        if (dark.values().size() == 1) {
            return dark.values().get(0);
        }
        // per-channel in analyzer declaration order fallback: equal split
        return dark.values().get(rowIndex % dark.values().size());
    }

    private static double[] stateVector(OpticalState state, StokesOrder order) {
        if (state == null) {
            throw new IllegalArgumentException("measurement references unknown optical state");
        }
        return order.toIQUV(state.stokes());
    }

    private static double[] applyRotation(double[] canonicalIquv, RotationConvention rotation) {
        // Stokes vectors are already encoded; convention sign is carried by the
        // fingerprint and applied during *construction* of states. Here we keep
        // canonical IQUV intact while asserting the convention non-null.
        if (rotation == null) {
            throw new IllegalArgumentException("rotation convention required");
        }
        return canonicalIquv;
    }

    private static double[] applyInstrumentSide(DMatrixRMaj side, double[] s) {
        DMatrixRMaj v = Mtx.mat(4, 1, s[0], s[1], s[2], s[3]);
        DMatrixRMaj out = Mtx.multiply(side, v);
        return new double[] {out.get(0, 0), out.get(1, 0), out.get(2, 0), out.get(3, 0)};
    }

    /** Design row for y = g' M a: vec ordering row-major over M, kron(a,g). */
    private static double[] kronRow(double[] g, double[] a) {
        double[] row = new double[16];
        for (int mu = 0; mu < 4; mu++) {
            for (int nu = 0; nu < 4; nu++) {
                row[4 * mu + nu] = g[mu] * a[nu];
            }
        }
        return row;
    }

    private static DMatrixRMaj toMueller(DMatrixRMaj vec) {
        DMatrixRMaj m = Mtx.zeros(4, 4);
        for (int i = 0; i < 16; i++) {
            m.set(i / 4, i % 4, vec.get(i, 0));
        }
        return m;
    }

    private static DMatrixRMaj rightSingularVector(DMatrixRMaj design, int k) {
        Mtx.SVD svd = Mtx.svd(design);
        DMatrixRMaj v = new DMatrixRMaj(16, 1);
        DMatrixRMaj vt = svd.vt();
        for (int i = 0; i < 16; i++) {
            v.set(i, 0, vt.get(k, i));
        }
        return v;
    }

    /** Whiten y: C^-1/2 from the eigendecomposition of symmetric C. */
    private static DMatrixRMaj whitening(DMatrixRMaj c, List<String> notes) {
        DMatrixRMaj sym = c.copy();
        for (int i = 0; i < sym.numRows; i++) {
            for (int j = i + 1; j < sym.numCols; j++) {
                double avg = 0.5 * (sym.get(i, j) + sym.get(j, i));
                sym.set(i, j, avg);
                sym.set(j, i, avg);
            }
        }
        double[][] vecs = JacobiEigen.realSymmetricEigenvectors(sym);
        // eigenvalues in descending order, matching the descending eigenvector columns
        double[] eigenvalues = PhysicalCheck.realEigenvalues(sym);
        DMatrixRMaj w = Mtx.zeros(sym.numRows, sym.numRows);
        for (int k = 0; k < sym.numRows; k++) {
            double lambda = eigenvalues[k];
            if (lambda <= 0) {
                notes.add("covariance eigenvalue " + k + " non-positive (" + lambda + "); using large variance fallback");
                lambda = 1e12;
            }
            double f = 1.0 / Math.sqrt(lambda);
            for (int i = 0; i < sym.numRows; i++) {
                for (int j = 0; j < sym.numRows; j++) {
                    w.set(i, j, w.get(i, j) + f * vecs[i][k] * vecs[j][k]);
                }
            }
        }
        return w;
    }

    private static String scientific(double v) {
        if (Double.isInfinite(v)) {
            return "infinity";
        }
        return String.format(java.util.Locale.ROOT, "%.3e", v);
    }
}
