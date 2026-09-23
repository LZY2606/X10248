package com.polws.domain;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted least-squares Mueller estimator with explicit near-singular handling.
 *
 * Rule: a (near-)singular design matrix is never inverted blindly. Full-rank cases use the
 * normal-equation covariance; rank-deficient cases produce a minimum-norm solution plus an
 * explicit null space, and callers enumerate legal candidates instead of hiding ambiguity.
 */
public final class Estimator {
    private Estimator() {
    }

    public static final class Result {
        public final double[] mueller;
        public final double[] covariance; // 16x16 flattened row-major (null entries zero when rank deficient)
        public final double conditionNumber;
        public final int rank;
        public final double[] singularValues;
        public final double[][] nullBasis; // rows spanning the null space of D
        public final double chiSquared;
        public final int dof;
        public final String method;

        Result(double[] mueller, double[] covariance, double cond, int rank, double[] sv,
               double[][] nullBasis, double chi, int dof, String method) {
            this.mueller = mueller;
            this.covariance = covariance;
            this.conditionNumber = cond;
            this.rank = rank;
            this.singularValues = sv;
            this.nullBasis = nullBasis;
            this.chiSquared = chi;
            this.dof = dof;
            this.method = method;
        }
    }

    public static Result estimate(double[][] dRows, double[] y, double[][] cov, double rcond) {
        int n = dRows.length;
        RealMatrix d = MatrixUtils.createRealMatrix(dRows);
        SingularValueDecomposition svd = new SingularValueDecomposition(d);
        double[] sv = svd.getSingularValues();
        double smax = sv[0];
        int rank = 0;
        for (double v : sv) {
            if (v > rcond * smax) {
                rank++;
            }
        }
        int smallestIndex = Math.min(sv.length, n <= 16 ? n - 1 : 15) - 1;
        double sMin = sv[smallestIndex] > 0 ? sv[smallestIndex] : Double.MIN_NORMAL;
        double cond = sv[0] / sMin;

        RealMatrix v = svd.getV();
        List<double[]> nullRows = new ArrayList<>();
        for (int j = rank; j < v.getColumnDimension(); j++) {
            nullRows.add(v.getColumn(j));
        }
        double[][] nullBasis = nullRows.toArray(new double[0][]);

        RealMatrix winv = cov == null ? null : symInv(MatrixUtils.createRealMatrix(cov));
        RealMatrix mhat;
        double[] covFlat;
        String method;

        if (rank >= 16) {
            RealMatrix dtwd;
            double[] dtwy;
            if (winv != null) {
                RealMatrix wd = winv.multiply(d);
                dtwd = d.transpose().multiply(wd);
                double[] wy = winv.operate(y);
                dtwy = d.transpose().operate(wy);
            } else {
                dtwd = d.transpose().multiply(d);
                dtwy = d.transpose().operate(y);
            }
            RealMatrix ainv = symInv(dtwd);
            mhat = MatrixUtils.createColumnRealMatrix(ainv.operate(dtwy));
            covFlat = flatten(ainv);
            method = "WLS_FULL_RANK";
        } else {
            RealMatrix mn;
            if (winv != null) {
                // weighted normal matrix, pseudoinverse => minimum weighted-norm solution
                RealMatrix a = d.transpose().multiply(winv.multiply(d));
                double[] b = d.transpose().operate(winv.operate(y));
                mn = MatrixUtils.createColumnRealMatrix(pseudoInverse(new SingularValueDecomposition(a), rcond).operate(b));
                covFlat = flatten(pseudoInverse(new SingularValueDecomposition(a), rcond));
            } else {
                RealMatrix dp = pseudoInverse(svd, rcond);
                mn = MatrixUtils.createColumnRealMatrix(dp.operate(y));
                covFlat = flatten(dp.multiply(dp.transpose()));
            }
            mhat = mn;
            method = "TSVD_MIN_NORM_NULLSPACE";
        }

        double[] pred = d.operate(mhat.getColumn(0));
        double chi = 0;
        for (int i = 0; i < n; i++) {
            double rv = y[i] - pred[i];
            double wi = (cov != null && cov[i][i] > 0) ? 1.0 / cov[i][i] : 1.0;
            chi += wi * rv * rv;
        }
        return new Result(mhat.getColumn(0), covFlat, cond, rank, sv, nullBasis,
                chi, Math.max(0, n - rank), method);
    }

    static RealMatrix symInv(RealMatrix a) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        return pseudoInverse(svd, 1e-12);
    }

    private static double[] flatten(RealMatrix m) {
        double[][] a = m.getData();
        int s = a.length;
        double[] flat = new double[s * s];
        for (int i = 0; i < s; i++) {
            System.arraycopy(a[i], 0, flat, i * s, s);
        }
        return flat;
    }

    private static RealMatrix pseudoInverse(SingularValueDecomposition svd, double rcond) {
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getV();
        double[] s = svd.getSingularValues();
        double cutoff = rcond * s[0];
        RealMatrix pinv = MatrixUtils.createRealMatrix(v.getColumnDimension(), u.getColumnDimension());
        int k = Math.min(s.length, Math.min(u.getColumnDimension(), v.getColumnDimension()));
        for (int i = 0; i < k; i++) {
            if (s[i] > cutoff) {
                RealMatrix outer = v.getColumnMatrix(i).multiply(u.getColumnMatrix(i).transpose());
                pinv = pinv.add(outer.scalarMultiply(1.0 / s[i]));
            }
        }
        return pinv;
    }
}
