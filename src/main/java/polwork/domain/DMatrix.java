package polwork.domain;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Dense real-matrix helpers used by the polarimetry pipeline.
 * Everything here is deterministic; no randomness and no mutation of inputs.
 */
public final class DMatrix {

    private DMatrix() {
    }

    public static RealMatrix m(double[][] data) {
        return new Array2DRowRealMatrix(data);
    }

    public static RealMatrix identity(int n) {
        return MatrixUtils.createRealIdentityMatrix(n);
    }

    public static RealMatrix transpose(RealMatrix a) {
        return a.transpose();
    }

    public static RealMatrix mul(RealMatrix a, RealMatrix b) {
        return a.multiply(b);
    }

    public static RealMatrix scale(RealMatrix a, double s) {
        return a.scalarMultiply(s);
    }

    public static RealMatrix add(RealMatrix a, RealMatrix b) {
        return a.add(b);
    }

    public static RealMatrix sub(RealMatrix a, RealMatrix b) {
        return a.subtract(b);
    }

    /** Moore-Penrose pseudo-inverse via SVD (only callers that explicitly allow pseudo-inversion). */
    public static RealMatrix pseudoInverse(RealMatrix a, double singularCutoff) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] s = svd.getSingularValues();
        double cutoff = singularCutoff <= 0 ? Math.max(a.getRowDimension(), a.getColumnDimension()) * s[0] * 1e-14 : singularCutoff;
        RealMatrix inv = new Array2DRowRealMatrix(a.getColumnDimension(), a.getRowDimension());
        RealMatrix u = svd.getU();
        RealMatrix vt = svd.getVT();
        for (int k = 0; k < s.length; k++) {
            if (s[k] > cutoff) {
                double invS = 1.0 / s[k];
                for (int i = 0; i < a.getColumnDimension(); i++) {
                    for (int j = 0; j < a.getRowDimension(); j++) {
                        inv.addToEntry(i, j, vt.getEntry(k, i) * invS * u.getEntry(j, k));
                    }
                }
            }
        }
        return inv;
    }

    /** Inverse for matrices the caller has already verified non-singular; throws otherwise. */
    public static RealMatrix inverseVerified(RealMatrix a) {
        QRDecomposition qr = new QRDecomposition(a, 1e-12);
        if (!qr.getSolver().isNonSingular()) {
            throw new ArithmeticException("matrix is singular; inverse refused");
        }
        return qr.getSolver().getInverse();
    }

    public static double[] solveLstSq(RealMatrix design, double[] rhs) {
        return new QRDecomposition(design).getSolver().solve(new Array2DRowRealMatrix(rhs)).getColumn(0);
    }

    public static double frobenius(RealMatrix a) {
        return Math.sqrt(a.walkInOptimizedOrder(new org.apache.commons.math3.linear.DefaultRealMatrixPreservingVisitor() {
            private double sum;

            @Override
            public void visit(int row, int column, double value) {
                sum += value * value;
            }

            @Override
            public double end() {
                return Math.sqrt(sum);
            }
        }));
    }
}
