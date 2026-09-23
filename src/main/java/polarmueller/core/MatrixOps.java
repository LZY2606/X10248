package polarmueller.core;

import org.ejml.data.DMatrixRMaj;
import org.ejml.data.ZMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.CommonOps_ZDRM;
import org.ejml.dense.row.SingularValueDecomposition_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_CDRM;
import org.ejml.dense.row.decomposition.eig.EigenDecomposition_F64;
import org.ejml.dense.row.decomposition.eig.EigenDecomposition_CDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition;

import java.util.ArrayList;
import java.util.List;

/** Linear-algebra helpers for the polarimetry derivation layer. */
public final class MatrixOps {
    private MatrixOps() {}

    public static DMatrixRMaj mat(double[][] a) {
        DMatrixRMaj m = new DMatrixRMaj(a.length, a[0].length);
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) m.set(i, j, a[i][j]);
        return m;
    }

    public static double[][] toArray(DMatrixRMaj m) {
        double[][] a = new double[m.numRows][m.numCols];
        for (int i = 0; i < m.numRows; i++)
            for (int j = 0; j < m.numCols; j++) a[i][j] = m.get(i, j);
        return a;
    }

    /** Kronecker product of two vectors: kron(a,b)[i*nb+j] = a[i]*b[j]. */
    public static double[] kron(double[] a, double[] b) {
        double[] out = new double[a.length * b.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < b.length; j++) out[i * b.length + j] = a[i] * b[j];
        return out;
    }

    public static DMatrixRMaj kron(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows * b.numRows, a.numCols * b.numCols);
        for (int i = 0; i < a.numRows; i++)
            for (int j = 0; j < a.numCols; j++)
                for (int k = 0; k < b.numRows; k++)
                    for (int l = 0; l < b.numCols; l++)
                        out.set(i * b.numRows + k, j * b.numCols + l, a.get(i, j) * b.get(k, l));
        return out;
    }

    public record Svd(double[] singularValues, double condition, int rank) {}

    public static Svd svd(DMatrixRMaj m) {
        SingularValueDecomposition_DDRM<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(m.numRows, m.numCols, false, false, false);
        if (!svd.decompose(m.copy())) throw new IllegalStateException("SVD failed to converge");
        double[] sv = svd.getSingularValues();
        double max = 0, min = Double.POSITIVE_INFINITY;
        int rank = 0;
        for (double v : sv) max = Math.max(max, v);
        double tol = max * Math.max(m.numRows, m.numCols) * 1e-15;
        for (double v : sv) {
            if (v > tol) { rank++; min = Math.min(min, v); }
        }
        double cond = (rank == 0 || min <= 0) ? Double.POSITIVE_INFINITY : max / min;
        return new Svd(sv, cond, rank);
    }

    /** Pseudo-inverse via SVD with relative tolerance; never forced through singular directions. */
    public static DMatrixRMaj pinv(DMatrixRMaj m, double relTol) {
        SingularValueDecomposition_DDRM<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(m.numRows, m.numCols, true, true, false);
        if (!svd.decompose(m.copy())) throw new IllegalStateException("SVD failed to converge");
        DMatrixRMaj u = svd.getU(null, false);
        DMatrixRMaj v = svd.getV(null, false);
        double[] sv = svd.getSingularValues();
        double max = 0;
        for (double s : sv) max = Math.max(max, s);
        double cutoff = max * relTol;
        DMatrixRMaj sInv = new DMatrixRMaj(v.numCols, u.numCols);
        for (int i = 0; i < sv.length; i++)
            if (sv[i] > cutoff) sInv.set(i, i, 1.0 / sv[i]);
        DMatrixRMaj tmp = new DMatrixRMaj(v.numRows, sInv.numCols);
        CommonOps_DDRM.mult(v, sInv, tmp);
        DMatrixRMaj out = new DMatrixRMaj(tmp.numRows, u.numRows);
        CommonOps_DDRM.multTransB(tmp, u, out);
        return out;
    }

    /** Eigenvalues of a complex hermitian matrix given as {re[][], im[][]}, ascending. */
    public static double[] hermitianEigenvalues(double[][] re, double[][] im) {
        int n = re.length;
        ZMatrixRMaj z = new ZMatrixRMaj(n, n);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) z.set(i, j, re[i][j], im[i][j]);
        EigenDecomposition_CDRM eig = DecompositionFactory_CDRM.eig(n, false);
        if (!eig.decompose(z)) throw new IllegalStateException("hermitian eig failed");
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = eig.getEigenvalue(i).real;
        java.util.Arrays.sort(out);
        return out;
    }

    /** Symmetric PSD matrix square root via eigendecomposition (3x3 use). */
    public static DMatrixRMaj sqrtmSymmetric(DMatrixRMaj a) {
        EigenDecomposition_F64 eig = DecompositionFactory_DDRM.eig(a.numRows, true, true);
        if (!eig.decompose(a.copy())) throw new IllegalStateException("eig failed");
        int n = a.numRows;
        DMatrixRMaj v = new DMatrixRMaj(n, n);
        DMatrixRMaj d = new DMatrixRMaj(n, n);
        for (int i = 0; i < n; i++) {
            var ev = eig.getEigenVector(i);
            double lambda = eig.getEigenvalue(i).real;
            for (int r = 0; r < n; r++) v.set(r, i, ev.get(r));
            d.set(i, i, Math.sqrt(Math.max(lambda, 0.0)));
        }
        DMatrixRMaj tmp = new DMatrixRMaj(n, n);
        CommonOps_DDRM.mult(v, d, tmp);
        DMatrixRMaj out = new DMatrixRMaj(n, n);
        CommonOps_DDRM.multTransB(tmp, v, out);
        return out;
    }

    public static DMatrixRMaj invert(DMatrixRMaj a) {
        DMatrixRMaj inv = new DMatrixRMaj(a.numRows, a.numCols);
        CommonOps_DDRM.invert(a.copy(), inv);
        return inv;
    }

    public static List<Double> diag(DMatrixRMaj a) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < Math.min(a.numRows, a.numCols); i++) out.add(a.get(i, i));
        return out;
    }
}
