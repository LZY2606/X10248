package polwork.estimation;

import org.ejml.data.DMatrixRMaj;
import polwork.domain.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates Mueller matrices from multi-state intensity measurements.
 *
 * Provenance rule: a near-singular design is never inverted by force (SVD
 * truncation + RANK_DEFICIENT/NEAR_SINGULAR status), a non-physical candidate
 * is kept as the raw estimate with its violation flags (no projection passed
 * off as the original), and rotation/Stokes conventions enter via the
 * fingerprint. Every legitimate candidate is retained.
 */
public final class MuellerEstimator {

    public record Inputs(
            String intensityVersionId,
            IntensityTable intensity,
            StateEncoding encoding,
            DarkFrame dark,
            ReferenceSample reference,
            InstrumentMatrix instrument,
            CovarianceSpec covariance,
            Conventions conventions,
            String ruleSetVersion) {}

    public record Output(DesignInfo design, List<MuellerCandidate> candidates, double[] residuals, double chiSquared) {}

    private MuellerEstimator() {}

    public static Output estimate(Inputs in) {
        PolarModel.BuiltDesign design = PolarModel.build(
                in.encoding,
                in.instrument == null ? null : in.instrument.wGenerator,
                in.instrument == null ? null : in.instrument.wAnalyzer);

        int ng = in.intensity.generatorStateNames.size();
        int na = in.intensity.analyzerStateNames.size();
        int n = ng * na;

        double[] y = new double[n];
        int k = 0;
        for (int g = 0; g < ng; g++) {
            for (int a = 0; a < na; a++) {
                double raw = in.intensity.counts[g][a];
                double dark = darkCount(in.dark, g, a);
                y[k++] = raw - dark;
            }
        }

        // weights from covariance (correlated detector columns supported)
        DMatrixRMaj cov;
        DMatrixRMaj wHalfInv;
        if (in.covariance != null && in.covariance.matrix != null) {
            cov = Linear.mat(in.covariance.matrix);
            wHalfInv = whitenInverseSqrt(cov);
        } else {
            cov = Linear.identity(n);
            wHalfInv = null;
        }

        DMatrixRMaj b = Linear.vec(y);
        Linear.SvdSolution svd = Linear.solveWeightedSVD(design.matrix, b, wHalfInv);

        // residual scale for covariance of parameters
        DMatrixRMaj pred = Linear.mult(design.matrix, Linear.vec(svd.xMinNorm));
        double[] resid = new double[n];
        double chi2 = 0;
        for (int i = 0; i < n; i++) {
            resid[i] = y[i] - pred.get(i, 0);
            double var = Math.max(cov.get(i, i), 1e-12);
            chi2 += resid[i] * resid[i] / var;
        }

        // map 16 parameters back into 4x4 in stored order, then convert to physical order
        double[][] storedM = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) storedM[i][j] = svd.xMinNorm[i * 4 + j];
        double[][] physicalM = toPhysical(storedM, in.conventions.storagePermutation());

        // reference anchoring: scale so reference prediction matches known reference
        double scale = referenceScale(in.reference, design.matrix, svd.xMinNorm);
        double[][] m = scale != 1.0 ? scaleMatrix(physicalM, scale) : physicalM;

        // element standard errors from parameter covariance
        DMatrixRMaj pcov = svd.covariance;
        double[][] se = new double[4][4];
        double dof = Math.max(1, n - svd.rank);
        double varianceScale = chi2 / dof;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = Math.max(0, pcov.get(i * 4 + j, i * 4 + j)) * varianceScale;
                se[i][j] = Math.sqrt(v) * Math.abs(scale);
            }
        }

        DesignInfo info = new DesignInfo(n, svd.rank, svd.conditionNumber,
                svd.singularValues, svd.rankDeficient, Linear.SINGULAR_REL_TOL);

        String fp = Conventions.fingerprint(in.intensity.materialId, in.intensity.wavelengthId,
                in.intensityVersionId,
                in.dark == null ? "" : in.dark.versionId,
                in.reference == null ? "" : in.reference.versionId,
                in.instrument == null ? "" : in.instrument.versionId,
                in.covariance == null ? "" : in.covariance.versionId,
                in.conventions, in.ruleSetVersion);

        String status = svd.rankDeficient ? "RANK_DEFICIENT" : svd.nearSingular ? "NEAR_SINGULAR" : "OK";
        String note = statusNote(status, svd, scale);

        PhysicalConstraints pc = PhysicalRealizability.evaluate(m);
        List<DecompositionCandidate> decos = new ArrayList<>();
        decos.add(Decompositions.luChipmanForward(m));
        decos.add(Decompositions.luChipmanReverse(m));
        decos.add(Decompositions.cloudeSpectral(m));

        MuellerCandidate candidate = new MuellerCandidate(
                "cand_wls_" + fp.substring(3, 11), fp, m, se, info, pc, decos, status, note);

        // Minimum-norm alternative is explicitly retained when not unique.
        List<MuellerCandidate> candidates = new ArrayList<>();
        candidates.add(candidate);
        if (!"OK".equals(status)) {
            candidates.add(new MuellerCandidate(
                    "cand_nullspace_" + fp.substring(3, 11), fp, m, se, info, pc,
                    List.of(), status,
                    "design not full rank (" + svd.rank + "/16): nullspace active; "
                            + "value shown is the SVD minimum-norm representative, not a unique answer"));
        }
        return new Output(info, candidates, resid, chi2);
    }

    private static double darkCount(DarkFrame dark, int g, int a) {
        if (dark == null || dark.meanCounts.isEmpty()) return 0;
        if (dark.meanCounts.size() == 1) return dark.meanCounts.get(0);
        return dark.meanCounts.get((g * 7 + a) % dark.meanCounts.size());
    }

    private static double[][] toPhysical(double[][] stored, int[] perm) {
        double[][] phys = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                phys[i][j] = stored[perm[i]][perm[j]];
            }
        }
        return phys;
    }

    private static double[][] scaleMatrix(double[][] m, double s) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) o[i][j] = m[i][j] * s;
        return o;
    }

    /**
     * Reference anchoring compares the predicted normalized transmission m00
     * against the known reference m00. The reference is a separate versioned
     * source; changing it invalidates the calibration-scaled result only.
     */
    private static double referenceScale(ReferenceSample ref, DMatrixRMaj design, double[] x) {
        if (ref == null) return 1.0;
        double known00 = ref.muellerKnown[0][0];
        double est00 = x[0];
        if (Math.abs(est00) < 1e-12) return 1.0;
        return known00 / est00;
    }

    private static DMatrixRMaj whitenInverseSqrt(DMatrixRMaj cov) {
        // covariance must be SPD; use LDL-like eigenvalue regularized inverse sqrt
        var eig = org.ejml.dense.row.factory.DecompositionFactory_DDRM.eig(cov.numRows, true, true);
        eig.decompose(cov);
        DMatrixRMaj v = new DMatrixRMaj(cov.numRows, cov.numRows);
        double[] lam = new double[cov.numRows];
        for (int i = 0; i < cov.numRows; i++) {
            lam[i] = Math.max(1e-12, eig.getEigenvalue(i).real);
            DMatrixRMaj ev = eig.getEigenVector(i);
            for (int j = 0; j < cov.numRows; j++) v.set(j, i, ev.get(j));
        }
        DMatrixRMaj winv = new DMatrixRMaj(cov.numRows, cov.numRows);
        for (int i = 0; i < cov.numRows; i++) {
            double f = 1.0 / Math.sqrt(lam[i]);
            for (int r = 0; r < cov.numRows; r++) {
                for (int c = 0; c < cov.numRows; c++) {
                    winv.add(r, c, v.get(r, i) * f * v.get(c, i));
                }
            }
        }
        return winv;
    }

    private static String statusNote(String status, Linear.SvdSolution svd, double scale) {
        return switch (status) {
            case "OK" -> "full-rank weighted least squares; ref scale="
                    + String.format(java.util.Locale.ROOT, "%.5g", scale);
            case "NEAR_SINGULAR" -> "condition number "
                    + String.format(java.util.Locale.ROOT, "%.3g", svd.conditionNumber)
                    + " exceeds " + Linear.NEAR_SINGULAR_COND
                    + ": SVD used, no forced inverse; review required";
            default -> "rank " + svd.rank + "/16: nullspace dimension "
                    + (16 - svd.rank) + "; minimum-norm representative only, no unique Mueller matrix";
        };
    }
}
