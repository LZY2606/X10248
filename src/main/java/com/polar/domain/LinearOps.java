package com.polar.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.Norm_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.SingularValueDecomposition_F64;

import java.util.Random;

/**
 * 基于 SVD 的线性工具。近奇异阈值显式可见，奇异矩阵永不使用普通求逆。
 */
public final class LinearOps {

    public static final class SVDSolve {
        public final double[] solution;
        public final double[] singularValues;
        public final double conditionNumber;
        public final int rank;
        public final boolean nearSingular;
        public final boolean rankDeficient;
        public final double effectiveThreshold;
        public final String action;

        SVDSolve(double[] solution, double[] sv, double cond, int rank,
                 boolean nearSingular, boolean rankDeficient,
                 double threshold, String action) {
            this.solution = solution;
            this.singularValues = sv;
            this.conditionNumber = cond;
            this.rank = rank;
            this.nearSingular = nearSingular;
            this.rankDeficient = rankDeficient;
            this.effectiveThreshold = threshold;
            this.action = action;
        }
    }

    private LinearOps() {}

    public static DMatrixRMaj mat(int rows, int cols, double... v) {
        return new DMatrixRMaj(rows, cols, true, v);
    }

    public static double[] solveSvd(double[][] A2, double[] b,
                                    double relThreshold, double nearSingularLog) {
        return svd(A2, b, relThreshold, nearSingularLog).solution;
    }

    public static SVDSolve svd(double[][] A2, double[] b,
                               double relThreshold, double nearSingularLog) {
        int m = A2.length, n = A2[0].length;
        DMatrixRMaj A = new DMatrixRMaj(m, n);
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) A.set(i, j, A2[i][j]);
        }
        DMatrixRMaj y = new DMatrixRMaj(m, 1, true, b.clone());
        SingularValueDecomposition_F64<DMatrixRMaj> svd =
                DecompositionFactory_DDRM.svd(m, n, true, true, false);
        if (!svd.decompose(A)) throw new IllegalStateException("SVD 分解失败");
        double[] sv = svd.getSingularValues();
        double smax = Math.max(sv[0], Double.MIN_NORMAL);
        double absTol = relThreshold * smax;
        int rank = 0;
        for (double s : sv) if (s > absTol) rank++;
        double cond = sv[0] / Math.max(sv[sv.length - 1], Double.MIN_NORMAL);
        boolean rankDef = rank < n;
        boolean nearSing = cond > nearSingularLog || rankDef;
        DMatrixRMaj x = new DMatrixRMaj(n, 1);
        // 显式截断 SVD 最小二乘：A^+ = V Σ^+ U^T
        DMatrixRMaj U = svd.getU(null, false);
        DMatrixRMaj V = svd.getV(null, true);
        DMatrixRMaj Utb = new DMatrixRMaj(m, 1);
        CommonOps_DDRM.multTransA(U, y, Utb);
        DMatrixRMaj z = new DMatrixRMaj(n, 1);
        for (int i = 0; i < n; i++) {
            z.set(i, rank > i ? Utb.get(i) / sv[i] : 0.0);
        }
        CommonOps_DDRM.mult(V, z, x);
        String action = rankDef
                ? "RANK_DEFICIENT_PSEUDOINVERSE_NOT_UNIQUE"
                : (nearSing ? "NEAR_SINGULAR_TRUNCATED_SVD" : "SVD_LEAST_SQUARES");
        return new SVDSolve(x.data, sv, cond, rank, nearSing, rankDef, absTol, action);
    }

    public static double[][] whiten(double[][] cov, Random rngSeedIgnored) {
        return whiten(cov);
    }

    /** L L^T = Sigma 的逆白化 W = L^{-1}；相关误差经 W 变为单位协方差。 */
    public static double[][] whiten(double[][] cov) {
        int n = cov.length;
        DMatrixRMaj S = new DMatrixRMaj(n, n);
        for (int i = 0; i < n; i++) System.arraycopy(cov[i], 0, S.data, i * n, n);
        var chol = DecompositionFactory_DDRM.chol(n, true);
        if (!chol.decompose(S)) throw new IllegalArgumentException("协方差矩阵非正定，无法白化");
        DMatrixRMaj L = chol.getT(null);
        DMatrixRMaj Linv = new DMatrixRMaj(n, n);
        CommonOps_DDRM.invert(L, Linv);
        double[][] w = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) w[i][j] = Linv.get(i, j);
        return w;
    }

    public static double cond(double[][] A2) {
        SVDSolve s = svd(A2, new double[A2.length], 1e-12, 1e12);
        return s.conditionNumber;
    }

    public static double frob(double[][] M) {
        double acc = 0;
        for (double[] row : M) for (double v : row) acc += v * v;
        return Math.sqrt(acc);
    }

    public static double residualNorm(double[][] A2, double[] x, double[] b) {
        double r = 0;
        for (int i = 0; i < b.length; i++) {
            double pred = 0;
            for (int j = 0; j < x.length; j++) pred += A2[i][j] * x[j];
            double d = b[i] - pred;
            r += d * d;
        }
        return Math.sqrt(r);
    }

    static double norm2(DMatrixRMaj v) {
        return Norm_DDRM.fastNormF(v);
    }
}
