package org.polarreview.optics;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition_F64;

import java.util.ArrayList;
import java.util.List;

/**
 * Covariance-weighted least squares via SVD.
 *
 * <p>Hard rule of this workspace: a rank-deficient design matrix is never
 * force-inverted. When rank &lt; 16 the estimator reports
 * {@link Status#RANK_DEFICIENT}, keeps the minimum-norm particular solution
 * <em>and the full null space</em> (the legal candidate family), and reports no
 * covariance inversion. A near-singular but formally full-rank matrix is solved
 * normally with an explicit NEAR_SINGULAR warning, so the reviewer decides.
 */
public final class Solver {

    public enum Status { FULL_RANK, NEAR_SINGULAR, RANK_DEFICIENT }

    public static final class Result {
        public final Status status;
        public final double[] m;
        public final double[][] covariance;
        public final double[] singularValues;
        public final double conditionNumber;
        public final int rank;
        public final double[][] nullSpace;
        public final double residualChi2;
        public final int dof;

        Result(Status status, double[] m, double[][] covariance, double[] sv, double cond,
               int rank, double[][] nullSpace, double chi2, int dof) {
            this.status = status;
            this.m = m;
            this.covariance = covariance;
            this.singularValues = sv;
            this.conditionNumber = cond;
            this.rank = rank;
            this.nullSpace = nullSpace;
            this.residualChi2 = chi2;
            this.dof = dof;
        }
    }

    private Solver() {}

    /**
     * @param relativeRankTol singular values above {@code tol*smax} count as nonzero
     * @param nearSingularCondition condition number at/above which a full-rank solve is warned
     */
    public static Result solve(double[][] wRaw, double[] y, double[][] covRaw,
                               double relativeRankTol, double nearSingularCondition) {
        int n = wRaw.length;

        // Whiten with the measurement covariance (handles correlated errors).
        double[][] c = covRaw == null ? identity(n) : covRaw;
        double[][] L = choleskyLower(c);
        double[][] Linv = invertLower(L);
        double[][] w = multiply(Linv, wRaw);
        double[] yw = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += Linv[i][j] * y[j];
            yw[i] = s;
        }

        DMatrixRMaj A = new DMatrixRMaj(w);
        @SuppressWarnings("unchecked")
        SingularValueDecomposition_F64<DMatrixRMaj> sd =
                (SingularValueDecomposition_F64<DMatrixRMaj>)
                        DecompositionFactory_DDRM.svd(n, 16, true, true, false);
        if (!sd.decompose(A)) throw new IllegalStateException("SVD decomposition failed");

        double[] sv = new double[16];
        double smax = 0;
        for (int i = 0; i < 16; i++) {
            sv[i] = sd.getSingularValue(i);
            smax = Math.max(smax, sv[i]);
        }
        double tol = relativeRankTol * Math.max(smax, Double.MIN_NORMAL);
        int rank = 0;
        for (double v : sv) if (v > tol) rank++;
        double smin = sv[15];
        double cond = smax > 0 ? smax / Math.max(smin, Double.MIN_NORMAL) : Double.POSITIVE_INFINITY;

        DMatrixRMaj U = sd.getU(null, false);
        DMatrixRMaj V = sd.getV(null, true);

        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int k = 0; k < n; k++) s += U.get(k, i) * yw[k];
            z[i] = s;
        }
        double[] coeff = new double[16];
        for (int i = 0; i < 16; i++) coeff[i] = sv[i] > tol ? z[i] / sv[i] : 0.0;
        double[] m = new double[16];
        for (int j = 0; j < 16; j++) {
            double s = 0;
            for (int i = 0; i < 16; i++) s += V.get(j, i) * coeff[i];
            m[j] = s;
        }

        double chi2 = 0;
        for (int k = 0; k < n; k++) {
            double pred = 0;
            for (int j = 0; j < 16; j++) pred += w[k][j] * m[j];
            double r = yw[k] - pred;
            chi2 += r * r;
        }

        List<double[]> ns = new ArrayList<>();
        for (int i = rank; i < 16; i++) {
            double[] v = new double[16];
            for (int j = 0; j < 16; j++) v[j] = V.get(j, i);
            ns.add(v);
        }

        double[][] cov = null;
        if (rank == 16) {
            // cov = V diag(1/s^2) V^T in whitened coordinates; whitening already
            // applied, so this is the estimator covariance for vec(M).
            double[][] inv = new double[16][16];
            for (int a = 0; a < 16; a++) {
                double invs = 1.0 / Math.max(sv[a], Double.MIN_NORMAL);
                for (int i = 0; i < 16; i++) {
                    for (int j = 0; j < 16; j++) {
                        inv[i][j] += V.get(i, a) * invs * invs * V.get(j, a);
                    }
                }
            }
            cov = inv;
        }

        Status status = rank < 16 ? Status.RANK_DEFICIENT
                : (cond >= nearSingularCondition ? Status.NEAR_SINGULAR : Status.FULL_RANK);
        return new Result(status, m, cov, sv, cond, rank,
                ns.toArray(new double[0][]), chi2, Math.max(n - rank, 0));
    }

    private static double[][] identity(int n) {
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) a[i][i] = 1;
        return a;
    }

    static double[][] choleskyLower(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][n];
        double floor = 1e-12 * Math.max(1.0, a[0][0]);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                if (i == j) {
                    double v = Math.max(sum, floor);
                    l[i][j] = Math.sqrt(v);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    private static double[][] invertLower(double[][] l) {
        int n = l.length;
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            inv[i][i] = 1.0 / l[i][i];
            for (int j = 0; j < i; j++) {
                double s = 0;
                for (int k = j; k < i; k++) s -= l[i][k] * inv[k][j];
                inv[i][j] = s / l[i][i];
            }
        }
        return inv;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        int n = a.length, p = b[0].length, k = b.length;
        double[][] r = new double[n][p];
        for (int i = 0; i < n; i++)
            for (int t = 0; t < k; t++)
                if (a[i][t] != 0)
                    for (int j = 0; j < p; j++) r[i][j] += a[i][t] * b[t][j];
        return r;
    }

    // Used by tests / diagnostics.
    public static double frobenius(double[][] a) {
        double s = 0;
        for (double[] row : a) for (double v : row) s += v * v;
        return Math.sqrt(s);
    }

}
