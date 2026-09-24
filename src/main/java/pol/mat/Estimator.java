package pol.mat;

import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller estimation from generator/analyser intensity measurements.
 *
 * <p>Model (row k): y_k = a_k^T M g_k = x_k vec(M), x_k = kron(g_k, a_k)^T with
 * column-major vec. Dark-corrected intensities; full covariance via GLS:
 * vec(M) = (X^T C^-1 X)^-1 X^T C^-1 y.
 *
 * <p><b>Rule EST-NEAR-SINGULAR:</b> the design is never inverted blindly. With
 * {@code svmax/svmin > NEAR_THRESHOLD} the minimum-norm SVD solution is returned but
 * flagged {@code NEAR_SINGULAR} with inflated variances; at numerical rank loss the
 * result is {@code RANK_DEFICIENT}: no unique matrix is presented and the null space is
 * returned so every legal candidate family remains visible.
 */
public final class Estimator {
    private Estimator() {}

    public static final double NEAR_THRESHOLD = 1e6;
    public static final double ZERO_SV = 1e-9;

    public static final class Input {
        public final List<double[]> generators;
        public final List<double[]> analyzers;
        public final double[] intensities;
        public final double[][] covariance;

        public Input(List<double[]> generators, List<double[]> analyzers,
                     double[] intensities, double[][] covariance) {
            this.generators = generators;
            this.analyzers = analyzers;
            this.intensities = intensities;
            this.covariance = covariance;
        }
    }

    public static final class Result {
        public final String status;
        public final double conditionNumber;
        public final double[] singularValues;
        public final int rank;
        public final RealMatrix mueller;
        public final double[][] covarianceM;
        public final double reducedChiSquared;
        public final List<double[]> nullSpace;
        public final List<String> diagnostics;

        Result(String status, double conditionNumber, double[] singularValues, int rank,
               RealMatrix mueller, double[][] covarianceM, double reducedChiSquared,
               List<double[]> nullSpace, List<String> diagnostics) {
            this.status = status;
            this.conditionNumber = conditionNumber;
            this.singularValues = singularValues;
            this.rank = rank;
            this.mueller = mueller;
            this.covarianceM = covarianceM;
            this.reducedChiSquared = reducedChiSquared;
            this.nullSpace = nullSpace;
            this.diagnostics = diagnostics;
        }
    }

    public static Result estimate(Input in) {
        int n = in.intensities.length;
        if (in.generators.size() != n || in.analyzers.size() != n) {
            throw new IllegalArgumentException("generator/analyser/intensity size mismatch");
        }
        double[][] x = new double[n][16];
        for (int k = 0; k < n; k++) {
            double[] g = in.generators.get(k);
            double[] a = in.analyzers.get(k);
            double[] row = M.vecColMajor(M.of(outer(a, g)));
            x[k] = row;
        }
        RealMatrix X = MatrixUtils.createRealMatrix(x);
        SingularValueDecomposition svd = new SingularValueDecomposition(X);
        double[] sv = svd.getSingularValues();
        double smax = sv[0];
        double smin = sv[sv.length - 1];
        int rank = 0;
        for (double v : sv) {
            if (v > ZERO_SV * smax) {
                rank++;
            }
        }
        double cond = smin > 0 ? smax / smin : Double.POSITIVE_INFINITY;
        List<String> diag = new ArrayList<>();

        RealMatrix C = in.covariance == null
                ? MatrixUtils.createRealIdentityMatrix(n)
                : MatrixUtils.createRealMatrix(in.covariance);
        CholeskyDecomposition chol = new CholeskyDecomposition(C, 1e-10, 1e-10);
        RealMatrix winv = new org.apache.commons.math3.linear.LUDecomposition(chol.getL()).getSolver().getInverse();
        RealMatrix Xw = winv.multiply(X);
        RealMatrix ywVec = winv.multiply(MatrixUtils.createColumnRealMatrix(in.intensities));

        SingularValueDecomposition svdw = new SingularValueDecomposition(Xw);
        double[] svw = svdw.getSingularValues();
        int rankW = 0;
        for (double v : svw) {
            if (v > ZERO_SV * svw[0]) {
                rankW++;
            }
        }

        if (rankW < 16) {
            diag.add("Numerical rank " + rankW + " < 16; design cannot uniquely determine M");
            List<double[]> nulls = new ArrayList<>();
            RealMatrix v = svdw.getV();
            for (int j = rankW; j < 16; j++) {
                double[] col = v.getColumn(j);
                nulls.add(col);
            }
            return new Result("RANK_DEFICIENT", cond, sv, rank, null, null,
                    Double.NaN, nulls, diag);
        }

        RealMatrix pinv = pseudoInverseSvd(svdw, svw);
        double[] beta = pinv.multiply(ywVec).getColumn(0);
        RealMatrix cov = pinv.multiply(pinv.transpose());
        RealMatrix m = M.unvecColMajor(beta, 4, 4);

        double[] fit = X.operate(beta);
        double chi2 = 0;
        RealMatrix cinv = new org.apache.commons.math3.linear.LUDecomposition(C).getSolver().getInverse();
        for (int i = 0; i < n; i++) {
            double r = in.intensities[i] - fit[i];
            for (int j = 0; j < n; j++) {
                chi2 += r * cinv.getEntry(i, j) * (in.intensities[j] - fit[j]);
            }
        }
        double reduced = n > 16 ? chi2 / (n - 16) : Double.NaN;

        String status = "OK";
        if (cond > NEAR_THRESHOLD) {
            status = "NEAR_SINGULAR";
            diag.add("Condition number " + String.format(java.util.Locale.ROOT, "%.3g", cond)
                    + " exceeds " + NEAR_THRESHOLD + "; minimum-norm solution, check variances");
        }
        return new Result(status, cond, sv, rank, m, cov.getData(), reduced, List.of(), diag);
    }

    private static RealMatrix pseudoInverseSvd(SingularValueDecomposition svd, double[] sv) {
        double cutoff = ZERO_SV * sv[0];
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getV();
        RealMatrix pinv = MatrixUtils.createRealMatrix(16, u.getRowDimension());
        for (int k = 0; k < Math.min(sv.length, u.getColumnDimension()); k++) {
            if (sv[k] <= cutoff) {
                continue;
            }
            double inv = 1.0 / sv[k];
            double[] vk = v.getColumn(k);
            double[] uk = u.getColumn(k);
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < uk.length; j++) {
                    pinv.addToEntry(i, j, inv * vk[i] * uk[j]);
                }
            }
        }
        return pinv;
    }

    private static double[][] outer(double[] a, double[] g) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                o[i][j] = a[i] * g[j];
            }
        }
        return o;
    }
}
