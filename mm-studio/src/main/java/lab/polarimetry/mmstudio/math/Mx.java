package lab.polarimetry.mmstudio.math;

import org.apache.commons.math3.linear.*;

/** Dense real matrix helpers (4x4/16xN domain scale), all based on commons-math. */
public final class Mx {
    private Mx() {}

    public static RealMatrix m(double[][] a) { return new Array2DRowRealMatrix(a, false); }

    public static RealMatrix zeros(int r, int c) { return new Array2DRowRealMatrix(r, c); }

    public static RealMatrix identity(int n) { return MatrixUtils.createRealIdentityMatrix(n); }

    public static RealMatrix kron(RealMatrix a, RealMatrix b) {
        int ar = a.getRowDimension(), ac = a.getColumnDimension();
        int br = b.getRowDimension(), bc = b.getColumnDimension();
        RealMatrix out = zeros(ar * br, ac * bc);
        for (int i = 0; i < ar; i++) {
            for (int j = 0; j < ac; j++) {
                out.setSubMatrix(b.scalarMultiply(a.getEntry(i, j)).getData(), i * br, j * bc);
            }
        }
        return out;
    }

    /** Row vector a (1xn) kron row vector b (1xn) -> 1x16 design row. */
    public static RealMatrix rowKron(double[] a, double[] b) {
        RealMatrix out = zeros(1, a.length * b.length);
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                out.setEntry(0, i * b.length + j, a[i] * b[j]);
            }
        }
        return out;
    }

    /** Invert a symmetric positive (semi-)definite matrix via eigendecomposition;
     *  eigenvalues at/below zero are left at zero (never forced through a fake inverse). */
    public static RealMatrix psdPseudoInverse(RealMatrix psd, double relTol) {
        EigenDecomposition e = new EigenDecomposition(symm(psd));
        double max = 0;
        for (double v : e.getRealEigenvalues()) max = Math.max(max, Math.abs(v));
        double cut = Math.max(relTol * max, 1e-300);
        RealMatrix inv = zeros(psd.getRowDimension(), psd.getColumnDimension());
        for (int k = 0; k < psd.getRowDimension(); k++) {
            double v = e.getRealEigenvalue(k);
            if (Math.abs(v) > cut && v > 0) {
                RealMatrix vec = e.getEigenvector(k);
                inv = inv.add(vec.multiply(vec.transpose()).scalarMultiply(1.0 / v));
            }
        }
        return inv;
    }

    public static RealMatrix sqrtPsd(RealMatrix a) {
        EigenDecomposition e = new EigenDecomposition(symm(a));
        RealMatrix out = zeros(a.getRowDimension(), a.getColumnDimension());
        for (int k = 0; k < a.getRowDimension(); k++) {
            double v = Math.max(0, e.getRealEigenvalue(k));
            RealMatrix vec = e.getEigenvector(k);
            out = out.add(vec.multiply(vec.transpose()).scalarMultiply(Math.sqrt(v)));
        }
        return out;
    }

    public static RealMatrix symm(RealMatrix a) {
        return a.add(a.transpose()).scalarMultiply(0.5);
    }

    public static RealMatrix inverse(RealMatrix a) {
        return new LUDecomposition(a).getSolver().getInverse();
    }

    public static double[] flattenRowMajor(RealMatrix a) {
        int r = a.getRowDimension(), c = a.getColumnDimension();
        double[] out = new double[r * c];
        for (int i = 0; i < r; i++) {
            System.arraycopy(a.getRow(i), 0, out, i * c, c);
        }
        return out;
    }

    public static RealMatrix unflatten4(double[] v) {
        if (v.length != 16) throw new IllegalArgumentException("expected 16 values, got " + v.length);
        return m(new double[][]{
                {v[0], v[1], v[2], v[3]},
                {v[4], v[5], v[6], v[7]},
                {v[8], v[9], v[10], v[11]},
                {v[12], v[13], v[14], v[15]}
        });
    }

    public static double get(RealMatrix a, int i, int j) { return a.getEntry(i, j); }
}
