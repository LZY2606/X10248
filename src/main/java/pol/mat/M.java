package pol.mat;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

/** Small helpers around 4x4 (and general) real matrices used across the polarimetry layer. */
public final class M {
    private M() {}

    public static RealMatrix of(double[][] a) {
        return new Array2DRowRealMatrix(a, true);
    }

    public static double[][] arr(RealMatrix m) {
        return m.getData();
    }

    public static RealMatrix kron(RealMatrix a, RealMatrix b) {
        int ar = a.getRowDimension();
        int ac = a.getColumnDimension();
        int br = b.getRowDimension();
        int bc = b.getColumnDimension();
        RealMatrix out = MatrixUtils.createRealMatrix(ar * br, ac * bc);
        for (int i = 0; i < ar; i++) {
            for (int j = 0; j < ac; j++) {
                double v = a.getEntry(i, j);
                out.setSubMatrix(b.scalarMultiply(v).getData(), i * br, j * bc);
            }
        }
        return out;
    }

    /** Column-major vectorisation: entry (i,j) -> index j*rows+i. */
    public static double[] vecColMajor(RealMatrix m) {
        int r = m.getRowDimension();
        int c = m.getColumnDimension();
        double[] v = new double[r * c];
        for (int j = 0; j < c; j++) {
            for (int i = 0; i < r; i++) {
                v[j * r + i] = m.getEntry(i, j);
            }
        }
        return v;
    }

    /** Inverse of {@link #vecColMajor}. */
    public static RealMatrix unvecColMajor(double[] v, int rows, int cols) {
        RealMatrix m = MatrixUtils.createRealMatrix(rows, cols);
        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                m.setEntry(i, j, v[j * rows + i]);
            }
        }
        return m;
    }

    public static double[] matVec(RealMatrix m, double[] v) {
        return m.operate(v);
    }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    public static double frob(RealMatrix m) {
        return m.getFrobeniusNorm();
    }
}
