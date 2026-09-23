package pol.math;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;

public final class Mat {
    private Mat() {}

    public static double[][] create(int rows, int cols) {
        return new double[rows][cols];
    }

    public static RealMatrix rm(double[][] a) {
        return new Array2DRowRealMatrix(a);
    }

    public static double[][] mult(double[][] a, double[][] b) {
        return rm(a).multiply(rm(b)).getData();
    }

    public static double[] mult(double[][] a, double[] v) {
        return rm(a).multiply(MatrixUtils.createRealVector(v)).toArray();
    }

    public static double[][] transpose(double[][] a) {
        return rm(a).transpose().getData();
    }

    public static double[][] inverse(double[][] a) {
        return new QRDecomposition(rm(a)).getSolver().getInverse().getData();
    }

    public static double det(double[][] a) {
        return new QRDecomposition(rm(a)).getDeterminant();
    }

    public static double[][] copy(double[][] a) {
        return rm(a).getData();
    }

    public static SVD svd(double[][] a) {
        SingularValueDecomposition s = new SingularValueDecomposition(rm(a));
        return new SVD(s.getU().getData(), s.getSingularValues(),
                s.getVT().getData(), s.getCovariance(0.0).getData());
    }

    public static double[] symmetricEigen(double[][] a) {
        EigenDecomposition e = new EigenDecomposition(rm(a));
        double[] ev = new double[a.length];
        for (int i = 0; i < ev.length; i++) ev[i] = e.getRealEigenvalue(i);
        java.util.Arrays.sort(ev);
        return ev;
    }

    public static double[][] symmetricEigenVectors(double[][] a) {
        EigenDecomposition e = new EigenDecomposition(rm(a));
        int n = a.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(e.getRealEigenvalue(x), e.getRealEigenvalue(y)));
        double[][] vec = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) vec[i][j] = e.getEigenvector(idx[j]).getEntry(i);
        }
        return vec;
    }

    public static double[][] nearestOrthogonal(double[][] a) {
        SVD s = svd(a);
        double[][] u = s.u;
        double[][] vt = s.vt;
        RealMatrix q = rm(u).multiply(rm(vt));
        if (new QRDecomposition(q).getDeterminant() < 0) {
            for (int i = 0; i < u.length; i++) u[i][u[0].length - 1] *= -1;
            q = rm(u).multiply(rm(vt));
        }
        return q.getData();
    }

    public static double trace(double[][] a) {
        double t = 0;
        for (int i = 0; i < a.length; i++) t += a[i][i];
        return t;
    }

    public static double normF(double[][] a) {
        return rm(a).getFrobeniusNorm();
    }

    public static double[][] scale(double[][] a, double k) {
        return rm(a).scalarMultiply(k).getData();
    }

    public static double[][] add(double[][] a, double[][] b) {
        return rm(a).add(rm(b)).getData();
    }

    public static double[][] sub(double[][] a, double[][] b) {
        return rm(a).subtract(rm(b)).getData();
    }

    public static double[][] identity(int n) {
        return MatrixUtils.createRealIdentityMatrix(n).getData();
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Math.abs(v) < 5e-4 && v != 0.0) return String.format("%.2e", v);
        return String.format("%.4f", v);
    }

    public static final class SVD {
        public final double[][] u;
        public final double[] singularValues;
        public final double[][] vt;
        public final double[][] pseudoinverseCov;
        public SVD(double[][] u, double[] sv, double[][] vt, double[][] cov) {
            this.u = u; this.singularValues = sv; this.vt = vt; this.pseudoinverseCov = cov;
        }
        public double condition() {
            double max = singularValues[0];
            double min = Double.POSITIVE_INFINITY;
            for (double v : singularValues) if (v > 0 && v < min) min = v;
            return min == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : max / min;
        }
        public int numericalRank(double tol) {
            double max = singularValues[0];
            int r = 0;
            for (double v : singularValues) if (v >= tol * max) r++;
            return r;
        }
        public List<double[]> nullspaceBasis(double tol) {
            int rank = numericalRank(tol);
            List<double[]> basis = new ArrayList<>();
            for (int j = rank; j < vt.length; j++) basis.add(vt[j].clone());
            return basis;
        }
        public List<double[]> rowspaceBasis(double tol) {
            int rank = numericalRank(tol);
            List<double[]> basis = new ArrayList<>();
            for (int j = 0; j < rank; j++) basis.add(vt[j].clone());
            return basis;
        }
    }
}
