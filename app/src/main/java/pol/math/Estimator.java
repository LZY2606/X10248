package pol.math;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted least-squares Mueller estimation with a singular-value cutoff.
 *
 * Near-singular designs are NOT inverted: components below the cutoff are
 * dropped, the candidate is marked non-unique, and every legal null-space
 * direction is preserved as an alternative candidate family member.
 */
public final class Estimator {

    /** Rank-deficiency threshold relative to the largest singular value. */
    public static final double SINGULAR_CUTOFF = 1e-6;

    public static final class Result {
        public final Matrix design;
        public final double[] singularValues;
        public final double conditionNumber;
        public final int rank;
        public final boolean nearSingular;
        public final double[] mueller;      // min-norm solution, SIQUV row-major
        public final double[] residual;    // b - W m
        public final double chi2;
        public final double[][] cov;       // 16x16 covariance (NaN-filled on null directions)
        public final List<double[]> nullDirections;
        public final List<String> notes = new ArrayList<>();

        Result(Matrix design, double[] sv, double cond, int rank, boolean nearSingular,
               double[] mueller, double[] residual, double chi2, double[][] cov,
               List<double[]> nullDirections) {
            this.design = design;
            this.singularValues = sv;
            this.conditionNumber = cond;
            this.rank = rank;
            this.nearSingular = nearSingular;
            this.mueller = mueller;
            this.residual = residual;
            this.chi2 = chi2;
            this.cov = cov;
            this.nullDirections = nullDirections;
        }
    }

    /**
     * Estimate with per-measurement intensity variance. covariances entries may carry
     * off-diagonal correlations (n x n); null/negative variances are reported, not repaired.
     */
    public static Result estimate(Matrix W, double[] b, double[][] covariance) {
        Matrix.SVD svd = Matrix.svd(W);
        int n = W.rows;
        double cutoff = SINGULAR_CUTOFF * max(svd.s);
        int rank = 0;
        for (double s : svd.s) if (s > cutoff) rank++;
        boolean near = rank < 16;
        double cond = finiteCondition(svd.s, cutoff);

        // Whiten with the covariance matrix (diagonal when uncorrelated).
        Whitening wh = Whitening.from(covariance, n);

        double[] wb = wh.apply(b);
        Matrix wW = wh.apply(W);
        Matrix.SVD wsvd = Matrix.svd(wW);
        double wcutoff = SINGULAR_CUTOFF * max(wsvd.s);
        // minimum-norm solution x = V S+ U^T wb
        double[] x = new double[16];
        for (int i = 0; i < 16; i++) {
            if (wsvd.s[i] <= wcutoff) continue;
            double comp = 0;
            for (int k = 0; k < n; k++) comp += wsvd.U.a[k][i] * wb[k];
            comp /= wsvd.s[i];
            for (int j = 0; j < 16; j++) x[j] += comp * wsvd.Vt.a[i][j];
        }

        double[] residual = new double[n];
        double[] pred = W.mulVec(x);
        double chi2 = 0;
        for (int k = 0; k < n; k++) {
            residual[k] = b[k] - pred[k];
            double var = covariance[k][k];
            if (var > 0) chi2 += residual[k] * residual[k] / var;
        }

        // Covariance of the estimate in row space; NaN along undetermined directions.
        double[][] cov = new double[16][16];
        for (double[] row : cov) java.util.Arrays.fill(row, Double.NaN);
        // cov = Vr (Sr+^2) Vr^T assuming whitening used Cov^{-1}
        for (int i = 0; i < rank; i++) {
            if (wsvd.s[i] <= wcutoff) continue;
            double inv2 = 1.0 / (wsvd.s[i] * wsvd.s[i]);
            for (int p = 0; p < 16; p++)
                for (int q = 0; q < 16; q++)
                    cov[p][q] = (Double.isNaN(cov[p][q]) ? 0 : cov[p][q])
                            + wsvd.Vt.a[i][p] * inv2 * wsvd.Vt.a[i][q];
        }

        // Null-space directions (columns of V corresponding to dropped singular values).
        List<double[]> nullDirs = new ArrayList<>();
        for (int i = rank; i < 16; i++) {
            double[] d = new double[16];
            for (int j = 0; j < 16; j++) d[j] = wsvd.Vt.a[i][j];
            if (norm(d) > 1e-9) nullDirs.add(scale(d, 1.0 / norm(d)));
        }

        Result r = new Result(W, svd.s, cond, rank, near, x, residual, chi2, cov, nullDirs);
        if (near) r.notes.add("RANK_DEFICIENT: design rank " + rank + " < 16; pseudoinverse minimum-norm candidate retained; "
                + nullDirs.size() + " null direction(s) preserved, no forced inversion");
        if (wh.correlated) r.notes.add("CORRELATED_ERRORS: measurement covariance has off-diagonal terms; GLS whitening applied");
        if (wh.illConditionedVar) r.notes.add("COVARIANCE_WARNING: non-positive variance retained at source; diagonal fallback used per row");
        return r;
    }

    public static Map<String, Object> serialize(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", r.rank);
        m.put("conditionNumber", Double.isInfinite(r.conditionNumber) ? null : r.conditionNumber);
        m.put("exactSingular", Double.isInfinite(r.conditionNumber));
        m.put("nearSingular", r.nearSingular);
        m.put("singularValues", toList(r.singularValues));
        double[][] mm = new double[4][4];
        for (int i = 0; i < 16; i++) mm[i / 4][i % 4] = r.mueller[i];
        m.put("muellerMinNorm", rows(mm));
        m.put("chi2", r.chi2);
        m.put("residual", toList(r.residual));
        List<Object> dirs = new ArrayList<>();
        for (double[] d : r.nullDirections) {
            double[][] dm = new double[4][4];
            for (int i = 0; i < 16; i++) dm[i / 4][i % 4] = d[i];
            dirs.add(rows(dm));
        }
        m.put("nullDirections", dirs);
        List<Object> covRows = new ArrayList<>();
        for (double[] row : r.cov) covRows.add(toList(row));
        m.put("covariance", covRows);
        m.put("notes", r.notes);
        return m;
    }

    private static double finiteCondition(double[] sv, double cutoff) {
        double max = max(sv);
        double min = Double.POSITIVE_INFINITY;
        for (double s : sv) if (s > cutoff) min = Math.min(min, s);
        if (Double.isInfinite(min) || min == 0) return Double.POSITIVE_INFINITY;
        return max / min;
    }

    private static double max(double[] v) {
        double m = 0;
        for (double d : v) m = Math.max(m, Math.abs(d));
        return m;
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double d : v) s += d * d;
        return Math.sqrt(s);
    }

    private static double[] scale(double[] v, double k) {
        double[] r = v.clone();
        for (int i = 0; i < r.length; i++) r[i] *= k;
        return r;
    }

    private static List<Object> toList(double[] v) {
        List<Object> l = new ArrayList<>();
        for (double d : v) l.add(d);
        return l;
    }

    private static List<Object> rows(double[][] m) {
        List<Object> l = new ArrayList<>();
        for (double[] row : m) l.add(toList(row));
        return l;
    }

    /** Covariance whitening: uses diagonal when off-diagonal terms are absent. */
    static final class Whitening {
        boolean correlated;
        boolean illConditionedVar;
        private final double[][] halfInv; // L such that L^T L = Cov^{-1}
        private final boolean diagonal;
        private final double[] diagW;

        private Whitening(double[][] h, boolean diagonal, double[] diagW, boolean corr, boolean ill) {
            this.halfInv = h; this.diagonal = diagonal; this.diagW = diagW;
            this.correlated = corr; this.illConditionedVar = ill;
        }

        static Whitening from(double[][] cov, int n) {
            boolean offDiag = false;
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (i != j && Math.abs(cov[i][j]) > 0) offDiag = true;
            if (!offDiag) {
                double[] w = new double[n];
                boolean ill = false;
                for (int i = 0; i < n; i++) {
                    if (!(cov[i][i] > 0)) { ill = true; w[i] = 1; }
                    else w[i] = 1 / Math.sqrt(cov[i][i]);
                }
                return new Whitening(null, true, w, false, ill);
            }
            // Cholesky of cov = L L^T ; whitening uses L^{-1}
            double[][] L = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j <= i; j++) {
                    double sum = cov[i][j];
                    for (int k = 0; k < j; k++) sum -= L[i][k] * L[j][k];
                    if (i == j) {
                        if (sum <= 0) throw new IllegalArgumentException(
                                "COVARIANCE_NOT_POSITIVE_DEFINITE: correlated error matrix cannot be whitened at " + i);
                        L[i][j] = Math.sqrt(sum);
                    } else L[i][j] = sum / L[j][j];
                }
            }
            // invert lower triangular L
            double[][] Linv = new double[n][n];
            for (int i = 0; i < n; i++) {
                Linv[i][i] = 1 / L[i][i];
                for (int j = 0; j < i; j++) {
                    double s = 0;
                    for (int k = j; k < i; k++) s += L[i][k] * Linv[k][j];
                    Linv[i][j] = -s / L[i][i];
                }
            }
            return new Whitening(Linv, false, null, true, false);
        }

        double[] apply(double[] v) {
            if (diagonal) {
                double[] r = v.clone();
                for (int i = 0; i < r.length; i++) r[i] *= diagW[i];
                return r;
            }
            // whitened vector = Linv v
            int n = v.length;
            double[] r = new double[n];
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int j = 0; j <= i; j++) s += halfInv[i][j] * v[j];
                r[i] = s;
            }
            return r;
        }

        Matrix apply(Matrix M) {
            Matrix R = new Matrix(M.rows, M.cols);
            for (int j = 0; j < M.cols; j++) {
                double[] col = new double[M.rows];
                for (int i = 0; i < M.rows; i++) col[i] = M.a[i][j];
                double[] wc = apply(col);
                for (int i = 0; i < M.rows; i++) R.a[i][j] = wc[i];
            }
            return R;
        }
    }
}
