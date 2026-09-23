package io.polroom;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

import java.util.List;

/**
 * Forward model: I_k = g_k^T M a_k  with dark current D_k added, and the measured design
 * expressed in the PSG/PSA instrument matrices W_G, W_A (reference-sample calibrated).
 *
 * Stacked: y = W m_vec, where row k of W is
 *   a_{k,0} g_k , a_{k,1} g_k , a_{k,2} g_k , a_{k,3} g_k   (each block a 4-vector).
 */
public final class MeasurementEngine {

    public record Design(RealMatrix w, double conditionNumber, int effectiveRank,
                         int rows, int cols, RealMatrix nullSpace, boolean singular) {}

    private MeasurementEngine() {}

    public static RealMatrix designRow(double[] generator, double[] analyzer) {
        RealMatrix row = MatrixUtils.createRealMatrix(1, 16);
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                row.setEntry(0, j * 4 + i, analyzer[j] * generator[i]);
            }
        }
        return row;
    }

    public static Design buildDesign(List<double[]> generators, List<double[]> analyzers, double tol) {
        RealMatrix w = MatrixUtils.createRealMatrix(generators.size(), 16);
        for (int k = 0; k < generators.size(); k++) {
            w.setRowMatrix(k, designRow(generators.get(k), analyzers.get(k)));
        }
        double[] sv = LA.singularValues(w);
        double cond = sv[sv.length - 1] <= LA.EPS * Math.max(1, sv[0])
                ? Double.POSITIVE_INFINITY : sv[0] / sv[sv.length - 1];
        int rank = LA.effectiveRank(w, tol);
        boolean singular = rank < 16;
        return new Design(w, cond, rank, w.getRowDimension(), w.getColumnDimension(),
                singular ? LA.nullSpace(w, tol) : MatrixUtils.createRealMatrix(16, 0), singular);
    }

    public static double[] subtractDark(double[] counts, double[] dark) {
        double[] y = new double[counts.length];
        for (int i = 0; i < y.length; i++) y[i] = counts[i] - dark[i];
        return y;
    }

    public static double predict(double[][] m, double[] g, double[] a) {
        double v = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) v += a[j] * m[i][j] * g[i];
        return v;
    }
}
