package pol.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition_F64;

/**
 * Small dense-linear-algebra facade over EJML used across estimation,
 * physical-constraint checks and decompositions. All routines are pure
 * functions on EJML matrices; no IO lives here.
 */
public final class Mtx {
    private Mtx() {
    }

    public static DMatrixRMaj mat(int rows, int cols, double... values) {
        if (values.length != rows * cols) {
            throw new IllegalArgumentException("expected " + rows * cols + " values, got " + values.length);
        }
        DMatrixRMaj m = new DMatrixRMaj(rows, cols);
        System.arraycopy(values, 0, m.data, 0, values.length);
        return m;
    }

    public static DMatrixRMaj zeros(int rows, int cols) {
        return new DMatrixRMaj(rows, cols);
    }

    public static DMatrixRMaj identity(int n) {
        DMatrixRMaj m = zeros(n, n);
        CommonOps_DDRM.setIdentity(m);
        return m;
    }

    public static DMatrixRMaj copy(DMatrixRMaj a) {
        return a.copy();
    }

    public static double get(DMatrixRMaj a, int row, int col) {
        return a.get(row, col);
    }

    public static void set(DMatrixRMaj a, int row, int col, double v) {
        a.set(row, col, v);
    }

    public static DMatrixRMaj multiply(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj c = zeros(a.numRows, b.numCols);
        CommonOps_DDRM.mult(a, b, c);
        return c;
    }

    public static DMatrixRMaj multiply(double s, DMatrixRMaj a) {
        DMatrixRMaj c = copy(a);
        CommonOps_DDRM.scale(s, c);
        return c;
    }

    public static DMatrixRMaj transpose(DMatrixRMaj a) {
        DMatrixRMaj t = zeros(a.numCols, a.numRows);
        CommonOps_DDRM.transpose(a, t);
        return t;
    }

    public static DMatrixRMaj add(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj c = zeros(a.numRows, a.numCols);
        CommonOps_DDRM.add(a, b, c);
        return c;
    }

    public static DMatrixRMaj subtract(DMatrixRMaj a, DMatrixRMaj b) {
        DMatrixRMaj c = zeros(a.numRows, a.numCols);
        CommonOps_DDRM.subtract(a, b, c);
        return c;
    }

    public static double normF(DMatrixRMaj a) {
        double sum = 0.0;
        for (int i = 0; i < a.getNumElements(); i++) {
            sum += a.data[i] * a.data[i];
        }
        return Math.sqrt(sum);
    }

    /** Inverse; callers must guard against near-singular matrices first. */
    public static DMatrixRMaj inverse(DMatrixRMaj a) {
        DMatrixRMaj inv = zeros(a.numRows, a.numCols);
        CommonOps_DDRM.invert(a, inv);
        return inv;
    }

    public static double determinant(DMatrixRMaj a) {
        return CommonOps_DDRM.det(a);
    }

    public static double trace(DMatrixRMaj a) {
        return CommonOps_DDRM.trace(a);
    }

    public record SVD(DMatrixRMaj u, double[] sv, DMatrixRMaj vt) {
    }

    /** Thin SVD with singular values sorted in descending order. */
    public static SVD svd(DMatrixRMaj a) {
        @SuppressWarnings("unchecked")
        SingularValueDecomposition_F64<DMatrixRMaj> d =
                (SingularValueDecomposition_F64<DMatrixRMaj>) DecompositionFactory_DDRM.svd(a.numRows, a.numCols, true, true, false);
        if (!d.decompose(a.copy())) {
            throw new IllegalStateException("SVD decomposition failed");
        }
        return new SVD(d.getU(null, false), d.getSingularValues(), d.getV(null, true));
    }

    /**
     * SVD-based least squares solution x to A x = b. Never forms (A'A)^-1.
     * Singular values below rcond * smax are treated as rank-deficient
     * (minimum-norm solution), and near-singular-but-invertible systems are
     * reported via the condition number instead of being silently inverted.
     */
    public static Fit solveLeastSquares(DMatrixRMaj design, DMatrixRMaj rhs, double rcond, boolean fullRankRequired) {
        int rows = design.numRows;
        int cols = design.numCols;
        SVD svd = svd(design);
        double[] s = svd.sv();
        double smax = s.length == 0 ? 0.0 : s[0];
        double threshold = rcond * (smax > 0 ? smax : 1.0);
        int rank = 0;
        for (double v : s) {
            if (v > threshold) {
                rank++;
            }
        }
        double smin = s[Math.min(s.length - 1, cols - 1)];
        double cond = smin > 0 ? smax / smin : Double.POSITIVE_INFINITY;

        DMatrixRMaj utb = multiply(transpose(svd.u()), rhs);
        DMatrixRMaj x = zeros(cols, 1);
        for (int j = 0; j < rank; j++) {
            x.set(j, 0, utb.get(j, 0) / s[j]);
        }
        DMatrixRMaj solution = zeros(cols, 1);
        CommonOps_DDRM.mult(transposeSlim(svd.vt(), cols), x, solution);

        DMatrixRMaj predicted = multiply(design, solution);
        DMatrixRMaj residual = subtract(rhs, predicted);
        double rss = 0.0;
        for (int i = 0; i < residual.numRows; i++) {
            rss += residual.get(i, 0) * residual.get(i, 0);
        }
        boolean rankDeficient = rank < cols;
        boolean forcedFullRank = rankDeficient && fullRankRequired;
        return new Fit(solution, s, rank, cols - rank, cond, rankDeficient, forcedFullRank, rss, rows - rank,
                predicted, residual, rcond, design.copy());
    }

    private static DMatrixRMaj transposeSlim(DMatrixRMaj vt, int cols) {
        DMatrixRMaj v = zeros(cols, vt.numRows);
        CommonOps_DDRM.transpose(vt, v);
        return v;
    }

    /** Covariance of x for whitened least squares: V diag(1/s^2) V' times scale. */
    public static DMatrixRMaj covariance(DMatrixRMaj design, double rcond, double varianceScale) {
        SVD svd = svd(design);
        double[] s = svd.sv();
        double smax = s.length == 0 ? 0.0 : s[0];
        double threshold = rcond * (smax > 0 ? smax : 1.0);
        int n = design.numCols;
        DMatrixRMaj cov = zeros(n, n);
        DMatrixRMaj v = transposeSlim(svd.vt(), n);
        for (int k = 0; k < s.length; k++) {
            if (s[k] <= threshold) {
                continue;
            }
            double factor = varianceScale / (s[k] * s[k]);
            for (int i = 0; i < n; i++) {
                double vik = v.get(i, k);
                for (int j = 0; j < n; j++) {
                    cov.set(i, j, cov.get(i, j) + factor * vik * v.get(j, k));
                }
            }
        }
        return cov;
    }

    public static double[] column(DMatrixRMaj a, int col) {
        double[] out = new double[a.numRows];
        for (int i = 0; i < a.numRows; i++) {
            out[i] = a.get(i, col);
        }
        return out;
    }

    public static double[] toArray(DMatrixRMaj a) {
        double[] out = new double[a.numRows * a.numCols];
        System.arraycopy(a.data, 0, out, 0, out.length);
        return out;
    }

    /**
     * Polar decomposition A = R H with R orthogonal (reflection-corrected so
     * det R = +1 when possible) and H symmetric positive semidefinite, via SVD.
     */
    public static Polar polarDecomposition(DMatrixRMaj a) {
        SVD svd = svd(a);
        DMatrixRMaj u = svd.u();
        DMatrixRMaj vt = svd.vt();
        DMatrixRMaj r = multiply(u, vt);
        double det = determinant(r);
        boolean corrected = false;
        if (det < 0) {
            for (int i = 0; i < u.numCols; i++) {
                u.set(u.numRows - 1, i, -u.get(u.numRows - 1, i));
            }
            r = multiply(u, vt);
            corrected = true;
        }
        DMatrixRMaj h = multiply(transpose(r), a);
        symmetrize(h);
        return new Polar(r, h, corrected, det);
    }

    private static void symmetrize(DMatrixRMaj h) {
        for (int i = 0; i < h.numRows; i++) {
            for (int j = i + 1; j < h.numCols; j++) {
                double avg = 0.5 * (h.get(i, j) + h.get(j, i));
                h.set(i, j, avg);
                h.set(j, i, avg);
            }
        }
    }

    public record Polar(DMatrixRMaj orthogonal, DMatrixRMaj symmetric, boolean reflectionCorrected, double rawDet) {
    }
}
