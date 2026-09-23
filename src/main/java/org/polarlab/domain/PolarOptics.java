package org.polarlab.domain;

import java.util.ArrayList;
import java.util.List;

/** 偏振光学算子：PSG/分析器 Stokes、测量设计矩阵、Cloude 物理可实现性。 */
public final class PolarOptics {
    private PolarOptics() {}

    /** 线偏振态（θ，弧度），标准 ISQUV 约定下 S=(1,cos2θ,sin2θ,0)。 */
    public static double[] linearStokes(double thetaRad) {
        return new double[]{1, Math.cos(2 * thetaRad), Math.sin(2 * thetaRad), 0};
    }

    public static double[] circularStokes(boolean right) {
        return new double[]{1, 0, 0, right ? 1 : -1};
    }

    /**
     * 构造测量设计矩阵。第 k 次测量强度
     *   I_k = a_k^T M g_k = (g_k ⊗ a_k)^T vec(M, 行优先)
     * g/a 的 Stokes 分量顺序按约定排列；θ 的正负按 rotationSign。
     */
    public static Mat designMatrix(List<StatePair> states, Convention conv) {
        int[] p = conv.permutationToStandard();
        Mat d = new Mat(states.size(), 16);
        for (int k = 0; k < states.size(); k++) {
            StatePair sp = states.get(k);
            double[] gStd = sp.generator.stokesStd(conv);
            double[] aStd = sp.analyzer.stokesStd(conv);
            double[] g = permuteForward(gStd, p);
            double[] a = permuteForward(aStd, p);
            int c = 0;
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    d.set(k, c++, g[j] * a[i]);
        }
        return d;
    }

    private static double[] permuteForward(double[] std, int[] stdToSrc) {
        // stdToSrc[k]：标准位置 k 的分量在源排列中的位置
        double[] out = new double[4];
        for (int k = 0; k < 4; k++) out[stdToSrc[k]] = std[k];
        return out;
    }

    public static Mat fromVec(double[] vec) {
        Mat m = new Mat(4, 4);
        int c = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) m.set(i, j, vec[c++]);
        return m;
    }

    public static double[] toVec(Mat m) { return m.a.clone(); }

    /** 光学状态：类型 + 角度（弧度，按旋转正负约定解释）+ 标签。 */
    public record OpticState(String kind, double thetaRad, String label) {
        public double[] stokesStd(Convention conv) {
            double theta = conv.signedAngleRad(thetaRad);
            return switch (kind) {
                case "linear" -> linearStokes(theta);
                case "circular+" -> circularStokes(true);
                case "circular-" -> circularStokes(false);
                default -> throw new IllegalArgumentException("unknown optic kind: " + kind);
            };
        }
    }

    public record StatePair(OpticState generator, OpticState analyzer) {}

    /**
     * Cloude 相干矩阵 T（4x4 Hermitian，分解为 16x16 实对称 H）。
     * 用 Pauli 基 m_1..m_4 的克罗内克展开：M = Σ β_ij m_i ⊗ m_j，
     * T_ij = (1/4) Σ_kl β_kl Tr(m_j m_i ⊗ m_l m_k)^*；对实 Mueller 取实部。
     */
    public static Mat coherencyReal(Mat m) {
        // Pauli/2 风格基矢 A_i（4x4），M = Σ M_ij A_i ⊗ A_j 形式的展开
        double[][][] basis = pauliSpinBasis();
        double[][] beta = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                Mat k = Mat.kron(Mat.of(basis[i]), Mat.of(basis[j]));
                double s = 0;
                for (int t = 0; t < 16; t++) s += m.a[t] * k.a[t];
                beta[i][j] = s / 4.0;
            }
        // H = 1/2 [[Re T, -Im T],[Im T, Re T]]，实 Mueller 的虚部来自反对称组合
        Mat h = new Mat(8, 8);
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                re[i][j] = (beta[i][j] + beta[j][i]) / 2.0;
                im[i][j] = (beta[i][j] - beta[j][i]) / 2.0;
            }
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                h.set(i, j, re[i][j]);
                h.set(i + 4, j + 4, re[i][j]);
                h.set(i, j + 4, -im[i][j]);
                h.set(i + 4, j, im[i][j]);
            }
        return h;
    }

    private static double[][][] pauliSpinBasis() {
        double[][][] b = new double[4][4][4];
        b[0] = new double[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
        b[1] = new double[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, -1, 0}, {0, 0, 0, -1}};
        b[2] = new double[][]{{0, 0, 1, 0}, {0, 0, 0, 1}, {1, 0, 0, 0}, {0, 1, 0, 0}};
        b[3] = new double[][]{{0, 0, 0, 1}, {0, 0, 1, 0}, {0, -1, 0, 0}, {-1, 0, 0, 0}};
        return b;
    }

    public record Physicality(double minEigenvalue, boolean physicallyRealizable,
                              double residualNorm, List<String> violations) {}

    /**
     * 物理可实现性：Cloude 相干矩阵最小特征值 >= -tol。
     * 不做投影；违约只标注。另附经典必要条件残差：m00>0、|m0i|,|mi0|<=m00。
     */
    public static Physicality checkPhysical(Mat m) {
        double tol = 1e-9 * Math.max(1, m.frobenius() / 4);
        Mat h = coherencyReal(m);
        double[][] eig = Mat.eigSym(h);
        double[] ev = eig[0];
        double min = Double.POSITIVE_INFINITY;
        for (double v : ev) min = Math.min(min, v);
        List<String> violations = new ArrayList<>();
        if (min < -tol)
            violations.add(String.format("cloude-coherency min eigenvalue %.6g < 0", min));
        double m00 = m.get(0, 0);
        if (!(m00 >= 0)) violations.add("m00 < 0: " + m00);
        for (int k = 1; k < 4; k++) {
            if (Math.abs(m.get(0, k)) > m00 + tol)
                violations.add(String.format("|m0%d| > m00", k));
            if (Math.abs(m.get(k, 0)) > m00 + tol)
                violations.add(String.format("|m%d0| > m00", k));
        }
        double residual = Math.max(0, -min);
        return new Physicality(min, min >= -tol && violations.isEmpty(), residual, violations);
    }

    /**
     * Cloude 分解（合法候选时）：M = Σ_k λ_k M_k，λ_k 为 T 的特征值，
     * 每个 M_k 为纯 Mueller。返回全部非零成分（多候选）。
     */
    public static List<Component> cloudeDecomposition(Mat m) {
        double[][][] basis = pauliSpinBasis();
        Mat h = coherencyReal(m);
        double[][] eig = Mat.eigSym(h);
        double[] ev = eig[0];
        Mat v = new Mat(8, 8);
        System.arraycopy(eig[1], 0, v.a, 0, 64);
        List<Component> out = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            if (Math.abs(ev[k]) < 1e-12) continue;
            // 实块的每个特征向量对应 T 的特征向量（前4实部，后4虚部）
            double[] re = new double[4];
            double[] im = new double[4];
            for (int i = 0; i < 4; i++) { re[i] = v.get(i, k); im[i] = v.get(i + 4, k); }
            Mat mk = new Mat(4, 4);
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) {
                    double tij = re[i] * re[j] + im[i] * im[j];
                    Mat prod = Mat.kron(Mat.of(basis[i]), Mat.of(basis[j]));
                    for (int t = 0; t < 16; t++) mk.a[t] += 4 * tij * prod.a[t];
                }
            out.add(new Component(ev[k], mk));
        }
        return out;
    }

    public record Component(double weight, Mat matrix) {}
}
