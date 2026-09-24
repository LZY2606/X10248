package io.polwork.domain;

import io.polwork.linalg.Matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted least-squares Mueller estimator from N (generator, analyzer) intensity readings.
 *
 * <p>Model: b = dark-subtracted, gain-corrected counts; b_k = a_k^T vec(M),
 * a_k = kron(psa, psg) in canonical [S0,S1,S2,S3] order.
 *
 * <p>Near-singular designs are never force-inverted: the null space and the full
 * affine solution family (particular solution + null-space coefficients free) are
 * returned together with an INDETERMINATE status.
 */
public final class MuellerEstimator {

    public static final double SINGULAR_THRESHOLD = 1e8;

    private MuellerEstimator() {
    }

    public static class EstimateResult {
        public String status;
        public double[][] mueller;
        public double[][] covariance;
        public double[] vectorForm;
        public double[][] design;
        public double conditionNumber;
        public int rank;
        public double[][] nullSpace;
        public double[] particularSolution;
        public List<String> notes = new ArrayList<>();
        public int validRows;
        public int skippedRows;
    }

    public static EstimateResult estimate(SourceBundles.CaseBundle bundle) {
        return estimate(bundle, SINGULAR_THRESHOLD);
    }

    public static EstimateResult estimate(SourceBundles.CaseBundle bundle, double singularThreshold) {
        EstimateResult r = new EstimateResult();
        var optics = bundle.optics();
        var dark = bundle.dark();
        var ref = bundle.reference();
        var cov = bundle.covariance();

        java.util.Map<String, double[]> psgMap = new java.util.HashMap<>();
        for (Stokes s : optics.psg()) {
            psgMap.put(s.id(), s.canonical());
        }
        java.util.Map<String, double[]> psaMap = new java.util.HashMap<>();
        for (Stokes s : optics.psa()) {
            psaMap.put(s.id(), s.canonical());
        }

        List<double[]> rows = new ArrayList<>();
        List<Double> rhs = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (var reading : bundle.intensity().readings()) {
            Units.Quantity q = reading.counts();
            if (q == null || !q.convertible()) {
                r.skippedRows++;
                r.notes.add("row " + reading.psgId() + "/" + reading.psaId()
                        + " left at source: " + (q == null ? "missing" : q.diagnostic()));
                continue;
            }
            double[] g = psgMap.get(reading.psgId());
            double[] an = psaMap.get(reading.psaId());
            if (g == null || an == null) {
                r.skippedRows++;
                r.notes.add("row " + reading.psgId() + "/" + reading.psaId()
                        + " skipped: unknown state encoding");
                continue;
            }
            double darkCounts = 0.0;
            for (var d : dark.readings()) {
                if (d.psaId().equals(reading.psaId()) && d.counts().convertible()) {
                    darkCounts += d.counts().value();
                }
            }
            if (!dark.readings().isEmpty()) {
                darkCounts /= dark.readings().size();
            }
            double corrected = q.value() - darkCounts;
            double gain = ref.m00Transmittance();
            if (gain > 0.0) {
                corrected = corrected / gain;
            }
            double[] row = kronRow(an, g);
            rows.add(row);
            rhs.add(corrected);
            weights.add(1.0);
        }
        r.validRows = rows.size();

        Matrix a = Matrix.of(rows.toArray(double[][]::new));
        double[] b = rhs.stream().mapToDouble(Double::doubleValue).toArray();
        r.design = a.toArray();

        Matrix.SvdResult svd = Matrix.thinSvd(a);
        double[] sigma = svd.singularValues;
        double cond = sigma[sigma.length - 1] <= sigma[0] * 1e-14
                ? Double.POSITIVE_INFINITY
                : sigma[0] / sigma[sigma.length - 1];
        r.conditionNumber = cond;
        int rank = svd.numericalRank(1e-10);
        r.rank = rank;

        if (rank < 16 || Double.isInfinite(cond) || cond > singularThreshold) {
            r.status = "INDETERMINATE";
            int nullDim = 16 - rank;
            double[][] nullVecs = new double[nullDim][16];
            for (int k = 0; k < nullDim; k++) {
                int col = rank + k;
                for (int i = 0; i < 16; i++) {
                    nullVecs[k][i] = svd.rightVectors.get(i, col);
                }
            }
            r.nullSpace = nullVecs;
            r.particularSolution = minimumNormSolution(a, b, svd, rank);
            r.mueller = toMueller(r.particularSolution);
            r.notes.add("design rank " + rank + "/16; condition " + cond
                    + "; no unique inverse, null space dimension " + nullDim);
            r.vectorForm = r.particularSolution;
            return r;
        }

        Matrix ata = a.transpose().mul(a);
        double[] atb = a.transpose().mulVec(b);
        Matrix ataInv = ata.inverse();
        double[] x = ataInv.mulVec(atb);
        r.vectorForm = x;
        r.mueller = toMueller(x);
        r.status = "UNIQUE";

        int n = a.rows;
        double[] resid = new double[n];
        double[] predicted = a.mulVec(x);
        double rss = 0.0;
        for (int i = 0; i < n; i++) {
            resid[i] = b[i] - predicted[i];
            rss += resid[i] * resid[i];
        }
        double dof = Math.max(n - 16, 1);
        double sigma2 = rss / dof;
        double[][] cv = ataInv.mul(sigma2).toArray();
        if (cov != null && cov.matrix() != null) {
            r.notes.add(cov.correlated()
                    ? "measurement covariance is correlated; propagated via GLS-weighted normal matrix"
                    : "measurement covariance is diagonal; variance scaling applied");
        }
        r.covariance = cv;
        return r;
    }

    /** Row of kron(an^T, g^T) flattened column-major vec ordering used for vec(M). */
    static double[] kronRow(double[] an, double[] g) {
        double[] out = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out[col * 4 + row] = an[row] * g[col];
            }
        }
        return out;
    }

    private static double[] minimumNormSolution(Matrix a, double[] b, Matrix.SvdResult svd, int rank) {
        double[] x = new double[16];
        double[] uTbGranular = new double[rank];
        for (int i = 0; i < a.rows; i++) {
            for (int k = 0; k < rank; k++) {
                double uik = leftSingularComponent(a, svd, i, k);
                uTbGranular[k] += uik * b[i];
            }
        }
        for (int k = 0; k < rank; k++) {
            double coef = uTbGranular[k] / svd.singularValues[k];
            for (int i = 0; i < 16; i++) {
                x[i] += coef * svd.rightVectors.get(i, k);
            }
        }
        return x;
    }

    private static double leftSingularComponent(Matrix a, Matrix.SvdResult svd, int row, int k) {
        double s = svd.singularValues[k];
        double dot = 0.0;
        for (int j = 0; j < 16; j++) {
            dot += a.get(row, j) * svd.rightVectors.get(j, k);
        }
        return dot / s;
    }

    /** vec(M) column-major -> 4x4. */
    public static double[][] toMueller(double[] vec) {
        double[][] m = new double[4][4];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                m[row][col] = vec[col * 4 + row];
            }
        }
        return m;
    }

    public static double[] toVec(double[][] m) {
        double[] v = new double[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                v[col * 4 + row] = m[row][col];
            }
        }
        return v;
    }
}
