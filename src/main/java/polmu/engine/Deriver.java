package polmu.engine;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Mueller-matrix estimation from polarimetric intensity measurements.
 *
 * Governing rules implemented here:
 *  - A near-singular design matrix is never force-inverted; the undecidable
 *    affine solution family (particular solution + null-space basis) is kept.
 *  - A non-physical Mueller candidate is flagged with its coherency residual;
 *    it is never projected onto the physical set and passed off as the
 *    original solution.
 *  - The rotation convention and Stokes order (from RuleParams) shape the
 *    design matrix, hence the derivation fingerprint.
 */
public final class Deriver {
    private Deriver() {}

    public record Measurement(String key, double[] sA, double[] sG, double intensity) {}

    public record Derivation(
            double conditionNumber,
            boolean nearSingular,
            int nullity,
            double[] particular,        // min-norm solution when singular, else the LS solution
            List<double[]> nullSpace,   // empty when well conditioned
            double[] estimate,          // null when near-singular (no forced inversion)
            double[] errors,            // null when near-singular
            double physicalResidual,    // min coherency eigenvalue; NaN when no estimate
            double[] cloudeWeights,     // null when no estimate
            List<double[][]> candidates // null when no estimate
    ) {}

    /** Build the 16-vec design row for one measurement: kron(sA, sG). */
    static double[] designRow(double[] sA, double[] sG) {
        double[] row = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) row[i * 4 + j] = sA[i] * sG[j];
        return row;
    }

    public static Derivation derive(List<Measurement> ms, double[][] covariance, RuleParams rules) {
        int n = ms.size();
        DMatrixRMaj w = new DMatrixRMaj(n, 16);
        DMatrixRMaj y = new DMatrixRMaj(n, 1);
        for (int k = 0; k < n; k++) {
            Measurement m = ms.get(k);
            double[] sA = permute(m.sA(), rules);
            double[] sG = permute(m.sG(), rules);
            double[] row = designRow(sA, sG);
            for (int j = 0; j < 16; j++) w.set(k, j, row[j]);
            y.set(k, 0, m.intensity());
        }
        double cond = MatrixOps.conditionNumber(w);
        if (!(cond < rules.conditionThreshold())) {
            // Near-singular: do NOT force the inversion. Keep the whole legal family.
            double tol = 1e-10 * Math.max(1.0, largestSingular(w));
            DMatrixRMaj pinv = MatrixOps.pinv(w, tol);
            DMatrixRMaj particular = new DMatrixRMaj(16, 1);
            CommonOps_DDRM.mult(pinv, y, particular);
            List<double[]> nullSpace = MatrixOps.nullSpace(w, tol);
            double[] part = new double[16];
            for (int i = 0; i < 16; i++) part[i] = particular.get(i, 0);
            return new Derivation(cond, true, nullSpace.size(), part, nullSpace,
                    null, null, Double.NaN, null, null);
        }
        DMatrixRMaj pinv = MatrixOps.pinv(w, 1e-12);
        DMatrixRMaj est = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(pinv, y, est);
        double[] estimate = new double[16];
        for (int i = 0; i < 16; i++) estimate[i] = est.get(i, 0);

        // Full covariance propagation through the (well-conditioned) pseudo-inverse.
        double[] errors = null;
        if (covariance != null) {
            DMatrixRMaj cov = new DMatrixRMaj(covariance);
            DMatrixRMaj tmp = new DMatrixRMaj(16, n);
            DMatrixRMaj covM = new DMatrixRMaj(16, 16);
            CommonOps_DDRM.mult(pinv, cov, tmp);
            CommonOps_DDRM.multTransB(tmp, pinv, covM);
            errors = new double[16];
            for (int i = 0; i < 16; i++) errors[i] = Math.sqrt(Math.max(0, covM.get(i, i)));
        }
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) m[i][j] = estimate[i * 4 + j];
        double residual = MatrixOps.physicalResidual(m);
        double[] weights = MatrixOps.cloudeWeights(m);
        List<double[][]> candidates = MatrixOps.cloudeCandidates(m);
        return new Derivation(cond, false, 0, estimate, List.of(), estimate, errors, residual, weights, candidates);
    }

    private static double[] permute(double[] stokes, RuleParams rules) {
        int[] perm = rules.stokesPermutation();
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[perm[i]] = stokes[i];
        return out;
    }

    private static double largestSingular(DMatrixRMaj w) {
        var svd = MatrixOps.svd(w);
        double max = 0;
        for (double s : svd.getSingularValues()) max = Math.max(max, s);
        return max;
    }

    /** Rotate a canonical-IQUV Stokes vector about the optical axis. */
    public static double[] rotateStokes(double[] s, double angleDeg, RuleParams rules) {
        double theta = Math.toRadians(angleDeg) * rules.rotationSign();
        double c = Math.cos(2 * theta), sn = Math.sin(2 * theta);
        return new double[]{
                s[0],
                c * s[1] + sn * s[2],
                -sn * s[1] + c * s[2],
                s[3]};
    }

    public static List<String> matrixSubjects() {
        List<String> out = new ArrayList<>();
        String[] idx = {"I", "Q", "U", "V"};
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out.add("M[" + idx[i] + "," + idx[j] + "]");
        return out;
    }
}
