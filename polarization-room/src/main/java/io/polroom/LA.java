package io.polroom;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Small dense linear-algebra helpers wrapping commons-math. */
public final class LA {
    public static final double EPS = 1e-12;

    private LA() {}

    public static RealMatrix m(double[][] a) {
        return new Array2DRowRealMatrix(a);
    }

    public static RealVector v(double... x) {
        return new ArrayRealVector(x);
    }

    public static double[][] arr(RealMatrix m) {
        return m.getData();
    }

    public static double[] arr(RealVector v) {
        return v.toArray();
    }

    public static RealMatrix eye(int n) {
        return MatrixUtils.createRealIdentityMatrix(n);
    }

    /** condition number = s1 / sN (Double.POSITIVE_INFINITY when numerically singular). */
    public static double conditionNumber(RealMatrix a) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        if (s.length == 0) return Double.POSITIVE_INFINITY;
        double smax = s[0];
        double smin = s[s.length - 1];
        if (smin <= EPS * Math.max(1, smax)) return Double.POSITIVE_INFINITY;
        return smax / smin;
    }

    public static double[] singularValues(RealMatrix a) {
        return new SingularValueDecomposition(a).getSingularValues();
    }

    /**
     * Rank-penalised / GLS-style normal solve.
     * Returns null when the design matrix is (numerically) singular for the requested
     * tolerance: the caller must NOT pretend a unique inverse exists.
     */
    public static RealVector solveIfFullRank(RealMatrix a, RealVector b, double tol) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        if (s.length == 0) return null;
        double smax = s[0];
        for (double si : s) {
            if (si <= tol * Math.max(1.0, smax)) return null;
        }
        RealMatrix sinv = MatrixUtils.createRealDiagonalMatrix(
                java.util.Arrays.stream(s).map(x -> 1.0 / x).toArray());
        RealMatrix pinv = svd.getV().multiply(sinv).multiply(svd.getUT());
        return pinv.operate(b);
    }

    /** Pseudoinverse with explicit effective rank; used for minimum-norm (clearly labelled) candidates. */
    public static double[][] pseudoInverse(RealMatrix a, double tol) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        double[] sinv = new double[s.length];
        int rank = 0;
        for (int i = 0; i < s.length; i++) {
            if (s[i] > tol * Math.max(1.0, s[0])) {
                sinv[i] = 1.0 / s[i];
                rank++;
            }
        }
        RealMatrix si = MatrixUtils.createRealDiagonalMatrix(sinv);
        return svd.getV().multiply(si).multiply(svd.getUT()).getData();
    }

    public static int effectiveRank(RealMatrix a, double tol) {
        double[] s = singularValues(a);
        if (s.length == 0) return 0;
        int rank = 0;
        for (double si : s) if (si > tol * Math.max(1, s[0])) rank++;
        return rank;
    }

    /** nullspace basis V columns for singular values below tolerance. */
    public static RealMatrix nullSpace(RealMatrix a, double tol) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        RealMatrix v = svd.getV();
        java.util.List<double[]> cols = new java.util.ArrayList<>();
        for (int i = 0; i < s.length; i++) {
            if (s[i] <= tol * Math.max(1, s[0])) {
                cols.add(v.getColumn(i));
            }
        }
        if (cols.isEmpty()) return m(new double[0][0]);
        double[][] out = new double[v.getRowDimension()][cols.size()];
        for (int j = 0; j < cols.size(); j++)
            for (int i = 0; i < out.length; i++) out[i][j] = cols.get(j)[i];
        return m(out);
    }

    public static double[][] choleskyL(RealMatrix cov) {
        return new CholeskyDecomposition(cov).getL().getData();
    }

    public static boolean isPositiveDefinite(RealMatrix cov) {
        try {
            new CholeskyDecomposition(cov, 1e-9, 1e-12);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static double trace(RealMatrix a) {
        double t = 0;
        int n = Math.min(a.getRowDimension(), a.getColumnDimension());
        for (int i = 0; i < n; i++) t += a.getEntry(i, i);
        return t;
    }

    public static double frobenius(RealMatrix a) {
        return Math.sqrt(a.transpose().multiply(a).getTrace());
    }
}
