package pmr.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;
import org.ejml.interfaces.decomposition.EigenDecomposition;
import org.ejml.data.Complex_F64;
import org.ejml.data.ZMatrixRMaj;
import org.ejml.dense.row.CommonOps_ZDRM;
import org.ejml.dense.row.factory.DecompositionFactory_ZDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;

import java.util.ArrayList;
import java.util.List;

/** EJML-backed linear algebra used by the Mueller review engine. */
public final class Linalg {
    private Linalg() {}

    public static DMatrixRMaj mat(double[][] a) {
        return new DMatrixRMaj(a);
    }

    public static double[][] arrays(DMatrixRMaj m) {
        double[][] out = new double[m.numRows][m.numCols];
        for (int i = 0; i < m.numRows; i++) {
            for (int j = 0; j < m.numCols; j++) {
                out[i][j] = m.get(i, j);
            }
        }
        return out;
    }

    public static DMatrixRMaj zeros(int rows, int cols) {
        return new DMatrixRMaj(rows, cols);
    }

    public static DMatrixRMaj transpose(DMatrixRMaj a) {
        DMatrixRMaj out = new DMatrixRMaj(a.numCols, a.numRows);
        CommonOps_DDRM.transpose(a, out);
        return out;
    }

    public static DMatrixRMaj mult(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows, b.numCols);
        CommonOps_DDRM.mult(a, b, out);
        return out;
    }

    public static DMatrixRMaj solve(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj out = new DMatrixRMaj(a.numCols, b.numCols);
        if (!CommonOps_DDRM.solve(a, b, out)) {
            throw new ArithmeticException("solve failed: singular system");
        }
        return out;
    }

    public static DMatrixRMaj invert(DMatrixRMaj a) {
        DMatrixRMaj out = new DMatrixRMaj(a.numRows, a.numCols);
        CommonOps_DDRM.invert(a, out);
        return out;
    }

    public static boolean invertible(DMatrixRMaj a, double tol) {
        if (a.numRows != a.numCols) {
            return false;
        }
        var svd = DecompositionFactory_DDRM.svd(true, true, false);
        if (!svd.decompose(a.copy())) {
            return false;
        }
        double[] sv = singularValues(svd, a.numRows);
        return sv[sv.length - 1] > tol * sv[0];
    }

    public record SvdInfo(double[] singularValues, int rank, double condition,
                          DMatrixRMaj u, DMatrixRMaj vt) {}

    public static SvdInfo svd(DMatrixRMaj a, double rankTol) {
        var d = DecompositionFactory_DDRM.svd(true, true, false);
        if (!d.decompose(a.copy())) {
            throw new ArithmeticException("SVD failed");
        }
        double[] sv = singularValues(d, Math.min(a.numRows, a.numCols));
        int rank = 0;
        for (double v : sv) {
            if (v > rankTol * (sv.length == 0 ? 1 : sv[0])) {
                rank++;
            }
        }
        double cond = sv[sv.length - 1] > 0 ? sv[0] / sv[sv.length - 1] : Double.POSITIVE_INFINITY;
        DMatrixRMaj u = d.getU(null, false);
        DMatrixRMaj vt = d.getVt(null, false);
        return new SvdInfo(sv, rank, cond, u, vt);
    }

    private static double[] singularValues(SingularValueDecomposition<DMatrixRMaj> d, int n) {
        double[] sv = new double[n];
        for (int i = 0; i < n; i++) {
            sv[i] = d.getSingularValue(i);
        }
        return sv;
    }

    /** Moore-Penrose solution x = V Σ^+ U^T b using explicit SVD pieces. */
    public static DMatrixRMaj pseudoSolve(SvdInfo svd, DMatrixRMaj b, double truncateBelow) {
        double[] sv = svd.singularValues();
        double smax = sv[0];
        DMatrixRMaj u = svd.u();
        DMatrixRMaj vt = svd.vt();
        int k = sv.length;
        DMatrixRMaj utb = new DMatrixRMaj(k, b.numCols);
        CommonOps_DDRM.multTransA(u, b, utb);
        for (int i = 0; i < k; i++) {
            double inv = sv[i] > truncateBelow * smax ? 1.0 / sv[i] : 0.0;
            for (int col = 0; col < b.numCols; col++) {
                utb.set(i, col, utb.get(i, col) * inv);
            }
        }
        DMatrixRMaj x = new DMatrixRMaj(vt.getNumRows(), b.numCols);
        CommonOps_DDRM.multTransA(vt, utb, x); // x = V (V^T?) -> vt is k x n here
        // vt returned by EJML for an m x n matrix has shape min(m,n) x n when compact
        return x;
    }

    /** Null space basis from V^T rows whose singular value is (near) zero. */
    public static List<DMatrixRMaj> nullspace(SvdInfo svd, DMatrixRMaj a, double rankTol) {
        double[] sv = svd.singularValues();
        DMatrixRMaj vt = svd.vt();
        List<DMatrixRMaj> basis = new ArrayList<>();
        for (int i = 0; i < sv.length; i++) {
            if (sv[i] <= rankTol * sv[0]) {
                DMatrixRMaj v = new DMatrixRMaj(a.numCols, 1);
                for (int r = 0; r < a.numCols; r++) {
                    v.set(r, 0, vt.get(i, r));
                }
                basis.add(v);
            }
        }
        return basis;
    }

    public static double trace(DMatrixRMaj m) {
        double t = 0;
        for (int i = 0; i < Math.min(m.numRows, m.numCols); i++) {
            t += m.get(i, i);
        }
        return t;
    }

    public static DMatrixRMaj identity(int n) {
        return DMatrixRMaj.identity(n);
    }

    // ---------------- complex (Cloude coherency) ----------------

    public static ZMatrixRMaj[] pauli() {
        ZMatrixRMaj s0 = new ZMatrixRMaj(new double[][]{{1, 0, 0, 0}, {0, 0, 1, 0}});
        ZMatrixRMaj s1 = new ZMatrixRMaj(new double[][]{{1, 0, 0, 0}, {0, 0, -1, 0}});
        ZMatrixRMaj s2 = new ZMatrixRMaj(new double[][]{{0, 0, 1, 0}, {1, 0, 0, 0}});
        ZMatrixRMaj s3 = new ZMatrixRMaj(new double[][]{{0, 0, 0, -1}, {0, 1, 0, 0}});
        return new ZMatrixRMaj[]{s0, s1, s2, s3};
    }

    private static ZMatrixRMaj mult(ZMatrixRMaj a, ZMatrixRMaj b) {
        ZMatrixRMaj out = new ZMatrixRMaj(a.numRows, b.numCols);
        CommonOps_ZDRM.mult(a, b, out);
        return out;
    }

    private static double[] traceComplex(ZMatrixRMaj m) {
        double re = 0;
        double im = 0;
        for (int i = 0; i < Math.min(m.numRows, m.numCols); i++) {
            re += m.getReal(i, i);
            im += m.getImag(i, i);
        }
        return new double[]{re, im};
    }

    /**
     * Cloude coherency matrix H with H_kl = (1/8) sum_ij M_ij tr(sigma_i sigma_k sigma_j sigma_l).
     * For any physical Mueller matrix H is Hermitian positive semidefinite.
     */
    public static ZMatrixRMaj coherency(double[][] m) {
        ZMatrixRMaj[] sigma = pauli();
        ZMatrixRMaj h = new ZMatrixRMaj(4, 4);
        for (int k = 0; k < 4; k++) {
            for (int l = 0; l < 4; l++) {
                double re = 0;
                double im = 0;
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        ZMatrixRMaj t = mult(mult(mult(sigma[i], sigma[k]), sigma[j]), sigma[l]);
                        double[] tr = traceComplex(t);
                        re += m[i][j] * tr[0];
                        im += m[i][j] * tr[1];
                    }
                }
                h.set(k, l, re / 8.0, im / 8.0);
            }
        }
        return h;
    }

    public record HermitianEigen(double[] real, double maxImagAbs) {}

    public static HermitianEigen eigenHermitian(ZMatrixRMaj h) {
        @SuppressWarnings("unchecked")
        EigenDecomposition_F64<ZMatrixRMaj> d =
                (EigenDecomposition_F64<ZMatrixRMaj>) (EigenDecomposition<?>)
                        DecompositionFactory_ZDRM.eig(4, true);
        if (!d.decompose(h.copy())) {
            throw new ArithmeticException("complex eigendecomposition failed");
        }
        double[] real = new double[4];
        double maxIm = 0;
        for (int i = 0; i < 4; i++) {
            Complex_F64 eig = d.getEigenvalue(i);
            real[i] = eig.real;
            maxIm = Math.max(maxIm, Math.abs(eig.imaginary));
        }
        java.util.Arrays.sort(real);
        return new HermitianEigen(real, maxIm);
    }
}
