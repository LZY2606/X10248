package org.polarimetry.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.Norm_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;

import java.util.Arrays;

/** Immutable dense double matrix with the linear algebra this workspace needs. */
public final class Linear {
    public final int rows, cols;
    final DMatrixRMaj m;

    private Linear(DMatrixRMaj m) {
        this.m = m;
        this.rows = m.numRows;
        this.cols = m.numCols;
    }

    public static Linear of(double[][] a) {
        DMatrixRMaj m = new DMatrixRMaj(a);
        return new Linear(m);
    }

    public static Linear zeros(int r, int c) {
        return new Linear(new DMatrixRMaj(r, c));
    }

    public static Linear identity(int n) {
        DMatrixRMaj m = new DMatrixRMaj(n, n);
        CommonOps_DDRM.setIdentity(m);
        return new Linear(m);
    }

    public static Linear column(double... v) {
        DMatrixRMaj m = new DMatrixRMaj(v.length, 1);
        for (int i = 0; i < v.length; i++) m.set(i, 0, v[i]);
        return new Linear(m);
    }

    public double get(int r, int c) { return m.get(r, c); }
    public double[] rowMajor() { return Arrays.copyOf(m.data, m.data.length); }

    public double[][] toArrays() {
        double[][] a = new double[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) a[r][c] = m.get(r, c);
        return a;
    }

    public Linear copy() { return new Linear(m.copy()); }

    public Linear plus(Linear o) {
        DMatrixRMaj r = new DMatrixRMaj(rows, cols);
        CommonOps_DDRM.add(m, o.m, r);
        return new Linear(r);
    }

    public Linear minus(Linear o) {
        DMatrixRMaj r = new DMatrixRMaj(rows, cols);
        CommonOps_DDRM.subtract(m, o.m, r);
        return new Linear(r);
    }

    public Linear times(double s) {
        DMatrixRMaj r = new DMatrixRMaj(rows, cols);
        CommonOps_DDRM.scale(s, m, r);
        return new Linear(r);
    }

    public Linear mult(Linear o) {
        DMatrixRMaj r = new DMatrixRMaj(rows, o.cols);
        CommonOps_DDRM.mult(m, o.m, r);
        return new Linear(r);
    }

    public Linear transpose() {
        DMatrixRMaj r = new DMatrixRMaj(cols, rows);
        CommonOps_DDRM.transpose(m, r);
        return new Linear(r);
    }

    public Linear inverse() {
        DMatrixRMaj r = new DMatrixRMaj(rows, cols);
        if (!CommonOps_DDRM.invert(m, r)) throw new ArithmeticException("matrix is singular");
        return new Linear(r);
    }

    public Linear solve(Linear b) {
        DMatrixRMaj r = new DMatrixRMaj(cols, b.cols);
        if (!CommonOps_DDRM.solve(m, b.m, r)) throw new ArithmeticException("solve failed");
        return new Linear(r);
    }

    /** SVD snapshot: singular values and V (right singular vectors as columns). */
    public Svd svd() {
        SingularValueDecomposition<DMatrixRMaj> d =
                DecompositionFactory_DDRM.svd(rows, cols, true, true, false);
        if (!d.decompose(m.copy())) throw new ArithmeticException("svd failed");
        double[] sv = d.getSingularValues();
        DMatrixRMaj v = d.getV(null, true);
        return new Svd(Arrays.copyOf(sv, Math.min(rows, cols)), new Linear(v));
    }

    public static final class Svd {
        public final double[] singularValues;
        public final Linear v;
        Svd(double[] s, Linear v) { this.singularValues = s; this.v = v; }

        public double conditionNumber() {
            double max = singularValues[0];
            double min = Double.POSITIVE_INFINITY;
            for (double s : singularValues)
                if (s > 0 && s < min) min = s;
            if (min == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
            return max / min;
        }
    }

    /**
     * Truncated pseudo-inverse. Rows whose singular value is below the threshold
     * (relative to the largest singular value) are dropped instead of being
     * inverted, so a (near-)singular design is never force-inverted.
     */
    public Pseudoinverse pseudoinverse(double relTol) {
        Svd svd = svd();
        int n = svd.singularValues.length;
        double smax = svd.singularValues[0];
        double cutoff = smax * relTol;
        int rank = 0;
        for (double s : svd.singularValues) if (s > cutoff) rank++;
        DMatrixRMaj pinv = new DMatrixRMaj(cols, rows);
        // get U
        SingularValueDecomposition<DMatrixRMaj> d =
                DecompositionFactory_DDRM.svd(rows, cols, true, true, false);
        d.decompose(m.copy());
        DMatrixRMaj u = d.getU(null, true);
        DMatrixRMaj v = d.getV(null, true);
        double[] sv = d.getSingularValues();
        for (int k = 0; k < rank; k++) {
            double inv = 1.0 / sv[k];
            for (int i = 0; i < cols; i++)
                for (int j = 0; j < rows; j++)
                    pinv.add(i, j, v.get(i, k) * inv * u.get(j, k));
        }
        Linear nullspace = new Linear(CommonOps_DDRM.extract(v, 0, v.numRows, rank, v.numCols));
        return new Pseudoinverse(new Linear(pinv), rank, n, Arrays.copyOf(sv, n), nullspace);
    }

    public static final class Pseudoinverse {
        public final Linear pinv;
        public final int rank, fullRank;
        public final double[] singularValues;
        /** columns span the numerical nullspace (may have zero columns). */
        public final Linear nullspace;
        Pseudoinverse(Linear p, int rank, int full, double[] sv, Linear ns) {
            this.pinv = p; this.rank = rank; this.fullRank = full; this.singularValues = sv;
            this.nullspace = ns;
        }
        public boolean rankDeficient() { return rank < fullRank; }
    }

    /** Real eigenvalues of a (assumed Hermitian/symmetric) matrix, ascending. */
    public double[] realEigenvalues() {
        EigenDecomposition<DMatrixRMaj> d = DecompositionFactory_DDRM.eig(rows, true, false);
        if (!d.decompose(m.copy())) throw new ArithmeticException("eigendecomposition failed");
        double[] e = new double[d.getNumberOfEigenvalues()];
        for (int i = 0; i < e.length; i++) e[i] = d.getEigenvalue(i).getReal();
        Arrays.sort(e);
        return e;
    }

    public double frobeniusNorm() { return Norm_DDRM.fastNormF(m); }

    /** Frobenius-normalized relative difference: ||a-b||/||a||. */
    public static double relativeError(Linear a, Linear b) {
        double n = Norm_DDRM.fastNormF(a.m);
        return Norm_DDRM.fastNormF(a.minus(b).m) / (n == 0 ? 1 : n);
    }

    public static double[] flattenRowMajor(Linear a) { return a.rowMajor(); }
}
