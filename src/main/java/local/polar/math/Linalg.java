package local.polar.math;

import org.ejml.data.DMatrixRMaj;
import org.ejml.simple.SimpleEVD;
import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;

import java.util.Arrays;

/** Small wrapper around EJML used by the estimation pipeline. */
public final class Linalg {

    private Linalg() {
    }

    public static SimpleMatrix m(double[][] a) {
        return new SimpleMatrix(a);
    }

    public static SimpleMatrix vec(double[] x) {
        double[][] col = new double[x.length][1];
        for (int i = 0; i < x.length; i++) col[i][0] = x[i];
        return new SimpleMatrix(col);
    }

    public static double[] toArray(SimpleMatrix col) {
        double[] out = new double[col.getNumElements()];
        for (int i = 0; i < out.length; i++) out[i] = col.get(i);
        return out;
    }

    public static SimpleMatrix kron(SimpleMatrix a, SimpleMatrix b) {
        return a.kron(b);
    }

    public static SimpleMatrix transpose(SimpleMatrix a) {
        return a.transpose();
    }

    public static SimpleMatrix solve(SimpleMatrix a, SimpleMatrix b) {
        return a.solve(b);
    }

    public static boolean invertible(SimpleMatrix a) {
        return !a.hasUncountable() && new SimpleMatrix(a).invert().hasUncountable() == false;
    }

    /**
     * SVD summary. Smallest singular value and 2-norm condition number of A.
     */
    public static SvdSummary svd(SimpleMatrix a) {
        @SuppressWarnings("unchecked")
        SimpleSVD<DMatrixRMaj> svd = (SimpleSVD<DMatrixRMaj>) a.svd();
        double[] s = singularValues(svd, Math.min(a.numRows(), a.numCols()));
        double smax = s[0];
        double smin = s[s.length - 1];
        double cond = smin > 0 ? smax / smin : Double.POSITIVE_INFINITY;
        return new SvdSummary(s, smax, smin, cond);
    }

    private static double[] singularValues(SimpleSVD<DMatrixRMaj> svd, int k) {
        double[] s = new double[k];
        for (int i = 0; i < k; i++) s[i] = svd.getSingleValue(i);
        Arrays.sort(s);
        for (int i = 0; i < k / 2; i++) {
            double t = s[i];
            s[i] = s[k - 1 - i];
            s[k - 1 - i] = t;
        }
        return s;
    }

    /** Eigenvalues of a real symmetric matrix, ascending order. */
    public static double[] symmetricEigenvalues(SimpleMatrix sym) {
        @SuppressWarnings("unchecked")
        SimpleEVD<DMatrixRMaj> evd =
                (SimpleEVD<DMatrixRMaj>) sym.eig();
        double[] ev = new double[evd.getNumberOfEigenvalues()];
        for (int i = 0; i < ev.length; i++) {
            ev[i] = evd.getEigenvalue(i).getReal();
        }
        Arrays.sort(ev);
        return ev;
    }

    public static double trace(SimpleMatrix a) {
        double t = 0;
        for (int i = 0; i < a.numRows(); i++) t += a.get(i, i);
        return t;
    }

    public static double frobenius(SimpleMatrix a) {
        double s = 0;
        for (int i = 0; i < a.getNumElements(); i++) {
            double v = a.get(i);
            s += v * v;
        }
        return Math.sqrt(s);
    }

    public record SvdSummary(double[] singularValues, double smax,
                             double smin, double condition) {
        public int rank(double tol) {
            int r = 0;
            for (double s : singularValues) {
                if (s > tol * Math.max(1.0, smax)) r++;
            }
            return r;
        }
    }
}
