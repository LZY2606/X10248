package org.polarreview.optics;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.SingularOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition;

/**
 * SVD of the weighted design matrix used both for the least-squares solve and
 * for the condition-number / rank diagnostics shown on the review page.
 */
public final class SVD {
    public final double[] singularValues;
    public final double conditionNumber;
    public final int rank;
    public final double rankTol;

    private SVD(double[] sv, double cond, int rank, double tol) {
        this.singularValues = sv;
        this.conditionNumber = cond;
        this.rank = rank;
        this.rankTol = tol;
    }

    public static SVD of(double[][] a, double relativeTol) {
        DMatrixRMaj A = new DMatrixRMaj(a);
        SingularValueDecomposition<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(A.numRows, A.numCols, true, true, false);
        if (!svd.decompose(A)) {
            throw new IllegalStateException("SVD failed");
        }
        double[] sv = SingularOps_DDRM.singularValues(svd);
        double smax = sv[0];
        double tol = relativeTol * smax;
        int rank = 0;
        for (double v : sv) if (v > tol) rank++;
        double cond = smax > 0 ? smax / Math.max(sv[sv.length - 1], Double.MIN_NORMAL) : Double.POSITIVE_INFINITY;
        return new SVD(sv, cond, rank, tol);
    }
}
