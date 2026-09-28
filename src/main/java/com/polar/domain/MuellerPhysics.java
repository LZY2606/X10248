package com.polar.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.EigenDecomposition_F64;

import java.util.ArrayList;
import java.util.List;

/**
 * Mueller 矩阵物理可实现性（Cloude 相干矩阵特征值判据）与 Stokes 顺序映射。
 * 非物理矩阵只记录违约，不做投影后冒充原解。
 */
public final class MuellerPhysics {

    // Pauli 基 E_k (k=0..3) 对应的 Mueller 相干/实展开指标配对：
    // H_{ij} 与 Mueller 元素 M_{ab} 的线性关系（见 Cloude 1986）。
    private static final int[][] A_IDX = {{0, 0}, {0, 1}, {0, 2}, {0, 3}};
    private static final int[][] LAMBDA = {
            {1, 1, 1, 1},
            {1, 1, -1, -1},
            {1, -1, 1, -1},
            {1, -1, -1, 1}
    };

    private static final DMatrixRMaj MAP;   // vec(H) = MAP vec(M)
    private static final DMatrixRMaj INV;   // vec(M) = INV vec(H)

    static {
        DMatrixRMaj map = new DMatrixRMaj(16, 16);
        for (int hi = 0; hi < 4; hi++) {
            for (int hj = 0; hj < 4; hj++) {
                for (int a = 0; a < 4; a++) {
                    for (int b = 0; b < 4; b++) {
                        int[] ai = A_IDX[a];
                        int[] bj = A_IDX[b];
                        int row = 4 * hi + hj;
                        int col = 4 * a + b;
                        double v = 0.25 * LAMBDA[hi][ai[0]] * LAMBDA[hj][ai[1]]
                                * LAMBDA[hi][bj[0]] * LAMBDA[hj][bj[1]];
                        map.add(row, col, v);
                    }
                }
            }
        }
        MAP = map.copy();
        DMatrixRMaj inv = new DMatrixRMaj(16, 16);
        CommonOps_DDRM.invert(MAP, inv);
        INV = inv;
    }

    public record Physicality(
            boolean physicallyRealizable,
            double minEigenvalue,
            double maxEigenvalue,
            double trace,
            List<String> violations,
            double negativity) {}

    private MuellerPhysics() {}

    public static double[] coherency(double[][] M) {
        double[] vm = new double[16];
        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++) vm[4 * a + b] = M[a][b];
        DMatrixRMaj vh = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(MAP, DMatrixRMaj.wrap(16, 1, vm), vh);
        return vh.data;
    }

    public static double[][] fromCoherency(double[] vecH) {
        DMatrixRMaj vm = new DMatrixRMaj(16, 1);
        CommonOps_DDRM.mult(INV, DMatrixRMaj.wrap(16, 1, vecH), vm);
        double[][] M = new double[4][4];
        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++) M[a][b] = vm.data[4 * a + b];
        return M;
    }

    public static Physicality check(double[][] M, double tol) {
        double[] h = coherency(M);
        DMatrixRMaj H = new DMatrixRMaj(4, 4, true, h.clone());
        EigenDecomposition_F64<DMatrixRMaj> eig =
                DecompositionFactory_DDRM.eig(4, true);
        if (!eig.decompose(H.copy())) {
            return new Physicality(false, Double.NaN, Double.NaN, Double.NaN,
                    List.of("EIGENDECOMPOSITION_FAILED"), Double.NaN);
        }
        double[] vals = new double[4];
        double trace = 0;
        for (int i = 0; i < 4; i++) {
            vals[i] = eig.getEigenvalue(i).getReal();
            trace += vals[i];
        }
        double min = vals[0], max = vals[0];
        for (double v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        List<String> violations = new ArrayList<>();
        if (min < -tol) violations.add("COHERENCY_MATRIX_HAS_NEGATIVE_EIGENVALUE");
        if (M[0][0] <= 0) violations.add("M00_NOT_POSITIVE");
        boolean pr = violations.isEmpty();
        return new Physicality(pr, min, max, trace, List.copyOf(violations), Math.max(0, -min));
    }

    /**
     * 非物理候选的“截断/投影”结果仅作为另一候选展示，绝不替换原解。
     * 返回将负特征值截断为 0 重建的 Mueller 矩阵。
     */
    public static double[][] truncatedCandidate(double[][] M) {
        double[] h = coherency(M);
        DMatrixRMaj H = new DMatrixRMaj(4, 4, true, h.clone());
        var svd = DecompositionFactory_DDRM.svd(4, 4, true, true, false);
        svd.decompose(H.copy());
        DMatrixRMaj U = svd.getU(null, false);
        DMatrixRMaj V = svd.getV(null, false);
        double[] s = svd.getSingularValues();
        for (int i = 0; i < 4; i++) s[i] = Math.max(0, s[i]);
        DMatrixRMaj Hpos = new DMatrixRMaj(4, 4);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++)
                    Hpos.add(i, j, U.get(i, k) * s[k] * V.get(j, k));
        return fromCoherency(Hpos.data.clone());
    }

    /** 改变 Stokes 分量存储顺序（行与列同置换），用于“Stokes 顺序变化”边界。 */
    public static double[][] reorderRowsCols(double[][] M, Conventions from, Conventions to) {
        int[] pinv = new int[4];
        for (int i = 0; i < 4; i++) pinv[to.stokesOrder().indexOf(from.stokesOrder().get(i))] = i;
        double[][] out = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) out[i][j] = M[pinv[i]][pinv[j]];
        return out;
    }

    public static double diattenuationMagnitude(double[][] M) {
        double d1 = M[0][1], d2 = M[0][2], d3 = M[0][3], m00 = M[0][0];
        return Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3) / Math.abs(m00);
    }
}
