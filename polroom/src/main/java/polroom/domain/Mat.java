package polroom.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Small linear-algebra toolkit used by the Mueller measurement engine. */
public final class Mat {

    public static final double SING_TOL = 1e-10;

    private Mat() {
    }

    public static RealMatrix m(double[][] a) {
        return new Array2DRowRealMatrix(a);
    }

    public static RealMatrix eye(int n) {
        return MatrixUtils.createRealIdentityMatrix(n);
    }

    public static RealMatrix kron(RealMatrix a, RealMatrix b) {
        int ar = a.getRowDimension();
        int ac = a.getColumnDimension();
        int br = b.getRowDimension();
        int bc = b.getColumnDimension();
        RealMatrix out = new Array2DRowRealMatrix(ar * br, ac * bc);
        for (int i = 0; i < ar; i++) {
            for (int j = 0; j < ac; j++) {
                out.setSubMatrix(b.scalarMultiply(a.getEntry(i, j)).getData(), i * br, j * bc);
            }
        }
        return out;
    }

    public record Svd(RealMatrix u, double[] s, RealMatrix vt) {
        public int rank(double tol) {
            double smax = s.length == 0 ? 0 : s[0];
            int r = 0;
            for (double v : s) {
                if (v > tol * Math.max(1.0, smax)) {
                    r++;
                }
            }
            return r;
        }

        public double condition() {
            double smax = s[0];
            double smin = 0;
            for (double v : s) {
                if (v > SING_TOL * Math.max(1.0, smax)) {
                    smin = v;
                }
            }
            if (smin <= 0) {
                return Double.POSITIVE_INFINITY;
            }
            return smax / smin;
        }
    }

    public static Svd svd(RealMatrix a) {
        SingularValueDecomposition d = new SingularValueDecomposition(a);
        return new Svd(d.getU(), d.getSingularValues(), d.getVT());
    }

    /** Moore-Penrose inverse with an explicit singular-value cutoff; never a naive inverse. */
    public static RealMatrix pinvTruncated(RealMatrix a, double tol) {
        Svd d = svd(a);
        double smax = d.s()[0];
        RealMatrix v = d.vt().transpose();
        RealMatrix ut = d.u().transpose();
        RealMatrix out = new Array2DRowRealMatrix(a.getColumnDimension(), a.getRowDimension());
        for (int k = 0; k < d.s().length; k++) {
            if (d.s()[k] > tol * Math.max(1.0, smax)) {
                double inv = 1.0 / d.s()[k];
                RealMatrix vk = v.getColumnMatrix(k);
                RealMatrix uk = ut.getRowMatrix(k).transpose();
                out = out.add(vk.multiply(uk).scalarMultiply(inv));
            }
        }
        return out;
    }

    /** Null-space basis V0 (columns) for singular values at or below the tolerance. */
    public static RealMatrix nullspace(RealMatrix a, double tol) {
        Svd d = svd(a);
        double smax = d.s()[0];
        RealMatrix v = d.vt().transpose();
        int cols = a.getColumnDimension();
        int nullCount = cols - d.rank(tol);
        if (nullCount == 0) {
            return new Array2DRowRealMatrix(cols, 0);
        }
        return v.getSubMatrix(0, cols - 1, cols - nullCount, cols - 1);
    }

    public static RealMatrix inverse(RealMatrix a) {
        return new LUDecomposition(a).getSolver().getInverse();
    }

    public static double determinant(RealMatrix a) {
        return new LUDecomposition(a).getDeterminant();
    }

    /** Positive-definite square root of a symmetric matrix; eigenvalues clamped at -eps. */
    public static RealMatrix sqrtSym(RealMatrix a) {
        EigenDecomposition e = new EigenDecomposition(a);
        RealMatrix v = e.getV();
        double[] diag = e.getRealEigenvalues().clone();
        double[] clamped = diag.clone();
        for (int i = 0; i < clamped.length; i++) {
            clamped[i] = Math.sqrt(Math.max(0, clamped[i]));
        }
        RealMatrix sqrtD = MatrixUtils.createRealDiagonalMatrix(clamped);
        return v.multiply(sqrtD).multiply(v.transpose());
    }

    public static double[] mv(RealMatrix a, double[] x) {
        return a.operate(new ArrayRealVector(x)).toArray();
    }

    public static double dot(double[] a, double[] b) {
        return new ArrayRealVector(a).dotProduct(new ArrayRealVector(b));
    }

    public static double norm(double[] a) {
        return new ArrayRealVector(a).getNorm();
    }

    /**
     * Eigenvalues of a Hermitian matrix (real and imaginary parts) by realification
     * to a symmetric matrix of doubled size.
     */
    public static double[] hermitianEigenvalues(double[][] re, double[][] im) {
        int n = re.length;
        RealMatrix big = new Array2DRowRealMatrix(2 * n, 2 * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                big.setEntry(i, j, re[i][j]);
                big.setEntry(i + n, j + n, re[i][j]);
                big.setEntry(i, j + n, -im[i][j]);
                big.setEntry(i + n, j, im[i][j]);
            }
        }
        EigenDecomposition e = new EigenDecomposition(big);
        double[] all = e.getRealEigenvalues();
        double[] out = new double[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }
}
