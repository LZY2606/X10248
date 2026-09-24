package polopt.domain;

import org.ejml.simple.SimpleMatrix;
import org.ejml.simple.SimpleSVD;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller-matrix estimation from intensity measurements.
 *
 * Model: y_k(=I_k - d_k) = a_k^T M g_k = w_k vec_col(M) + residual.
 *
 * A near-singular design matrix is never force-inverted into a single answer:
 *   - RANK_DEFICIENT: an affine solution family {x0 + N z} is stored explicitly
 *     together with the null-space basis; callers enumerate physical candidates.
 *   - NEAR_SINGULAR: rank is nominal but the condition number crosses the
 *     threshold, so inversion is abstained and the situation is flagged.
 * The minimum-norm x0 for a deficient system is kept ONLY as the family anchor,
 * never presented as "the" unique solution.
 */
public final class SVDEstimator {

    public enum Status { FULL_RANK, NEAR_SINGULAR, RANK_DEFICIENT, INSUFFICIENT_DATA }

    public record Result(
            Status status,
            int rank,
            int nullity,
            double conditionNumber,
            double[] singularValues,
            double[][] mueller,
            double[][] muellerCovariance,
            double[][] elementStd,
            double[] familyAnchor,
            double[][] nullBasis,
            double threshold,
            List<String> diagnostics) {
    }

    public static final double DEFAULT_COND_THRESHOLD = 1.0e6;
    public static final double REL_SV_FLOOR = 1.0e-10;

    private SVDEstimator() {
    }

    public static Result estimate(double[][] W, double[] y, double[][] covariance, double condThreshold) {
        List<String> diagnostics = new ArrayList<>();
        int n = W.length;
        if (n == 0 || y.length != n) {
            diagnostics.add("design/intensity row count mismatch or empty");
            return new Result(Status.INSUFFICIENT_DATA, 0, 16, Double.POSITIVE_INFINITY,
                    new double[0], null, null, null, null, null, condThreshold, diagnostics);
        }
        SimpleMatrix wm = new SimpleMatrix(W);
        SimpleMatrix ym = new SimpleMatrix(n, 1, true, y);

        SimpleSVD<SimpleMatrix> svdRaw = wm.svd(true);
        double[] sRaw = singularValues(svdRaw);
        double sMax = sRaw[0];
        int rank = 0;
        for (double v : sRaw) {
            if (v > REL_SV_FLOOR * Math.max(1.0, sMax)) {
                rank++;
            }
        }
        int nullity = 16 - rank;
        double sMin = sRaw[Math.max(0, rank - 1)];
        double cond = rank == 16 ? sMax / Math.max(sMin, Double.MIN_NORMAL) : Double.POSITIVE_INFINITY;

        if (rank < 16) {
            diagnostics.add("design matrix rank " + rank + " < 16; solution is an affine family, not unique");
            SimpleMatrix x0 = minNormSolution(svdRaw, ym, sMax);
            SimpleMatrix nullBasis = nullSpace(svdRaw, sMax);
            return new Result(Status.RANK_DEFICIENT, rank, nullity, cond, sRaw,
                    null, null, null,
                    x0.getDDRM().data, toRows(nullBasis), condThreshold, diagnostics);
        }

        if (cond > condThreshold) {
            diagnostics.add("condition number " + format(cond)
                    + " exceeds threshold " + format(condThreshold) + "; inversion abstained");
            return new Result(Status.NEAR_SINGULAR, rank, 0, cond, sRaw,
                    null, null, null, null, null, condThreshold, diagnostics);
        }

        // Optional covariance weighting (general symmetric positive definite covariance).
        SimpleMatrix solveW = wm;
        SimpleMatrix solveY = ym;
        SimpleMatrix eye = SimpleMatrix.identity(n);
        if (covariance != null) {
            SimpleMatrix c = new SimpleMatrix(covariance);
            SimpleSVD<SimpleMatrix> csvd = c.svd(true);
            SimpleMatrix whiten = csvd.getU().mult(diagInvSqrt(csvd.getW(), n)).mult(csvd.getU().transpose());
            solveW = whiten.mult(wm);
            solveY = whiten.mult(ym);
            eye = whiten; // for covariance propagation below
        }

        SimpleSVD<SimpleMatrix> svd = solveW.svd(true);
        SimpleMatrix invW = svd.getV().mult(inverseDiagonal(svd.getW())).mult(svd.getU().transpose());
        SimpleMatrix x = invW.mult(solveY);

        // Covariance propagation: Sigma_x = (W' C^-1 W)^-1 (unscaled Gauss-Markov).
        SimpleMatrix cInv;
        if (covariance != null) {
            SimpleMatrix cm = new SimpleMatrix(covariance);
            cInv = cm.invert();
        } else {
            cInv = SimpleMatrix.identity(n);
        }
        SimpleMatrix normal = wm.transpose().mult(cInv).mult(wm);
        SimpleMatrix sigmaX = normal.invert();

        double[][] m = fromVec(x.getDDRM().data);
        double[][] cov = toRows(sigmaX);
        double[][] es = elementStd(cov);
        diagnostics.add("full-rank weighted least squares, condition number " + format(cond));
        return new Result(Status.FULL_RANK, rank, 0, cond, sRaw, m, cov, es,
                null, null, condThreshold, diagnostics);
    }

    private static SimpleMatrix diagInvSqrt(SimpleMatrix sw, int n) {
        SimpleMatrix d = new SimpleMatrix(n, n);
        for (int i = 0; i < Math.min(n, sw.numCols()); i++) {
            double v = sw.get(i, i);
            d.set(i, i, v > 1e-300 ? 1.0 / Math.sqrt(v) : 0.0);
        }
        return d;
    }

    private static SimpleMatrix inverseDiagonal(SimpleMatrix sw) {
        SimpleMatrix d = new SimpleMatrix(sw.numCols(), sw.numRows());
        for (int i = 0; i < Math.min(sw.numRows(), sw.numCols()); i++) {
            double v = sw.get(i, i);
            d.set(i, i, v > REL_SV_FLOOR ? 1.0 / v : 0.0);
        }
        return d;
    }

    private static SimpleMatrix minNormSolution(SimpleSVD<SimpleMatrix> svd, SimpleMatrix y, double sMax) {
        SimpleMatrix inv = inverseDiagonalThresholded(svd.getW(), sMax);
        return svd.getV().mult(inv).mult(svd.getU().transpose()).mult(y);
    }

    private static SimpleMatrix nullSpace(SimpleSVD<SimpleMatrix> svd, double sMax) {
        SimpleMatrix v = svd.getV();
        List<SimpleMatrix> cols = new ArrayList<>();
        SimpleMatrix w = svd.getW();
        for (int j = 0; j < v.numCols(); j++) {
            double sv = j < w.numRows() ? w.get(j, j) : 0.0;
            if (sv <= REL_SV_FLOOR * Math.max(1.0, sMax)) {
                cols.add(v.extractVector(false, j));
            }
        }
        SimpleMatrix basis = new SimpleMatrix(v.numRows(), cols.size());
        for (int j = 0; j < cols.size(); j++) {
            basis.setColumn(j, cols.get(j).getDDRM().data);
        }
        return basis;
    }

    private static SimpleMatrix inverseDiagonalThresholded(SimpleMatrix sw, double sMax) {
        SimpleMatrix d = new SimpleMatrix(sw.numCols(), sw.numRows());
        for (int i = 0; i < Math.min(sw.numRows(), sw.numCols()); i++) {
            double v = sw.get(i, i);
            d.set(i, i, v > REL_SV_FLOOR * Math.max(1.0, sMax) ? 1.0 / v : 0.0);
        }
        return d;
    }

    private static double[] singularValues(SimpleSVD<SimpleMatrix> svd) {
        SimpleMatrix w = svd.getW();
        int k = Math.min(w.numRows(), w.numCols());
        double[] s = new double[k];
        for (int i = 0; i < k; i++) {
            s[i] = w.get(i, i);
        }
        java.util.Arrays.sort(s);
        for (int i = 0; i < k / 2; i++) {
            double t = s[i];
            s[i] = s[k - 1 - i];
            s[k - 1 - i] = t;
        }
        return s;
    }

    /** Column-major vec: vec_col(M)[j*4+i] = M[i][j]. */
    public static double[] toVec(double[][] m) {
        double[] x = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                x[j * 4 + i] = m[i][j];
            }
        }
        return x;
    }

    public static double[][] fromVec(double[] x) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                m[i][j] = x[j * 4 + i];
            }
        }
        return m;
    }

    private static double[][] elementStd(double[][] cov) {
        double[][] es = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = cov[j * 4 + i][j * 4 + i];
                es[i][j] = v > 0 ? Math.sqrt(v) : Double.NaN;
            }
        }
        return es;
    }

    private static double[][] toRows(SimpleMatrix m) {
        double[][] r = new double[m.numRows()][m.numCols()];
        for (int i = 0; i < m.numRows(); i++) {
            for (int j = 0; j < m.numCols(); j++) {
                r[i][j] = m.get(i, j);
            }
        }
        return r;
    }

    private static String format(double v) {
        if (Double.isInfinite(v)) {
            return "inf";
        }
        return String.format("%.3g", v);
    }
}
