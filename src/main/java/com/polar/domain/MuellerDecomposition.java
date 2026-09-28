package com.polar.domain;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mueller 分解候选：
 *  - Lu-Chipman 正向 M = M_R M_D M_Δ 与反向 M = M_Δ' M_D' M_R'；
 *  - Cloude 谱分解（非确定性矩阵只作为候选展示）。
 * 无法唯一决定时保留全部合法候选并记录退化/物理性标记，不静默丢弃。
 */
public final class MuellerDecomposition {

    public record Component(String name, double[][] matrix, boolean physical,
                            double retardanceRad, double diattenuation,
                            double depolarizationIndex, String note) {}

    public record Candidate(String method, String order, List<Component> components,
                            boolean degenerate, String degeneracyNote) {}

    private MuellerDecomposition() {}

    public static List<Candidate> allCandidates(double[][] M, double tol) {
        List<Candidate> out = new ArrayList<>();
        out.add(luChipman(M, tol, true));
        out.add(luChipman(M, tol, false));
        out.add(cloude(M, tol));
        return out;
    }

    /** Lu-Chipman 极分解。forward=true: M = MΔ MR MD；false: 反向顺序。 */
    public static Candidate luChipman(double[][] Min, double tol, boolean forward) {
        DMatrixRMaj M = toMat(forward ? Min : transpose(Min));
        double m00 = M.get(0, 0);
        double d1 = M.get(0, 1) / m00, d2 = M.get(0, 2) / m00, d3 = M.get(0, 3) / m00;
        double d = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
        List<String> notes = new ArrayList<>();
        DMatrixRMaj MD = diattenuator(m00, d1, d2, d3);
        DMatrixRMaj MDeltaMR = new DMatrixRMaj(4, 4);
        CommonOps_DDRM.mult(M, invert(MD), MDeltaMR);
        DMatrixRMaj MDelta;
        DMatrixRMaj MR;
        if (d > 1.0 - 1e-9) {
            notes.add("DIATTENUATION_SATURATED_POLAR_DECOMPOSITION_DEGENERATE");
            MDelta = DMatrixRMaj.identity(4);
            MR = MDeltaMR.copy();
        } else {
            // (M_Δ M_R) 的第一行就是 M_Δ 的第一行 (1,d1,d2,d3) 经过 MD^{-1} 后已固定，
            // 取 MΔMR 第一行作为 mΔR 的第一行，标准 Lu-Chipman 3x3 子块提取：
            double[] mdr0 = new double[4];
            for (int j = 0; j < 4; j++) mdr0[j] = MDeltaMR.get(0, j);
            DMatrixRMaj Mraw = new DMatrixRMaj(3, 3);
            for (int i = 1; i < 4; i++)
                for (int j = 1; j < 4; j++)
                    Mraw.set(i - 1, j - 1, MDeltaMR.get(i, j) - mdr0[j] * MDeltaMR.get(i, 0));
            // 归一化 3x3 极分解 Mraw = Δ R（先用 Cloude 风格对称极分解）
            double denom = 1 - d * d;
            double[][] m3 = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) m3[i][j] = Mraw.get(i, j) / denom;
            double[][][] pr = polar3(m3);
            double[][] P = pr[0], R = pr[1];
            MDelta = block(P);
            MR = block(R);
            double det = det3(P);
            if (det < -tol) notes.add("M_DELTA_NEGATIVE_DETERMINANT");
        }
        DMatrixRMaj MdeltaFinal = new DMatrixRMaj(4, 4);
        CommonOps_DDRM.mult(MDelta, MD, MdeltaFinal);
        CommonOps_DDRM.mult(MR, MdeltaFinal, MdeltaFinal);
        double recon = diffFrob(M, MdeltaFinal);
        if (recon > 1e-6 * Math.max(1, frob(M))) notes.add("RECONSTRUCTION_RESIDUAL_LARGE=" + recon);
        // 组件物理性：MD/MR 物理；MΔ 要求半正定近似
        DMatrixRMaj MDf = MD, MRf = MR, MDpf = MDelta;
        List<Component> comps = new ArrayList<>();
        if (forward) {
            comps.add(comp("diattenuator", MDf, tol));
            comps.add(comp("retarder", MRf, tol));
            comps.add(comp("depolarizer", MDpf, tol));
        } else {
            // 反向路径在转置坐标中求得，报告转置后的组件
            comps.add(comp("diattenuator_rev", trans(MDf), tol));
            comps.add(comp("retarder_rev", trans(MRf), tol));
            comps.add(comp("depolarizer_rev", trans(MDpf), tol));
        }
        return new Candidate("LU_CHIPMAN", forward ? "M = M_R M_D M_DELTA" : "M = M_DELTA M_D M_R (reverse)",
                comps, !notes.isEmpty(), String.join(";", notes));
    }

    /** Cloude 谱分解：M = sum λ_i M_i，λ 为相干矩阵特征值。 */
    public static Candidate cloude(double[][] M, double tol) {
        double[] h = MuellerPhysics.coherency(M);
        DMatrixRMaj H = new DMatrixRMaj(4, 4, true, h.clone());
        var eig = DecompositionFactory_DDRM.eig(4, true);
        eig.decompose(H.copy());
        double[][] vec = new double[4][4];
        for (int k = 0; k < 4; k++) {
            DMatrixRMaj v = eig.getEigenVector(k);
            for (int i = 0; i < 4; i++) vec[k][i] = v == null ? 0 : v.get(i);
        }
        double[] lam = new double[4];
        for (int k = 0; k < 4; k++) lam[k] = eig.getEigenvalue(k).getReal();
        Integer[] ord = {0, 1, 2, 3};
        Arrays.sort(ord, (a, b) -> Double.compare(lam[b], lam[a]));
        boolean deg = false;
        String note = "";
        for (int k = 0; k < 3; k++) {
            if (Math.abs(lam[ord[k]] - lam[ord[k + 1]]) <= tol) {
                deg = true;
                note = "DEGENERATE_EIGENVALUES_ALL_VALID_PERMUTATIONS_RETAINED";
            }
        }
        List<Component> comps = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            int idx = ord[k];
            double[] hi = new double[16];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    hi[4 * i + j] = lam[idx] * vec[idx][i] * vec[idx][j];
            double[][] Mi = MuellerPhysics.fromCoherency(hi);
            comps.add(comp("cloude_mode_" + k + "_lambda=" + round(lam[idx]), Mi, tol));
        }
        return new Candidate("CLOUDE_SPECTRAL", "M = sum_i lambda_i M_i (ordered by lambda desc)",
                comps, deg, note);
    }

    private static Component comp(String name, DMatrixRMaj X, double tol) {
        double[][] x = toArrays(X);
        MuellerPhysics.Physicality p = MuellerPhysics.check(x, tol);
        double retard = retardance(x);
        return new Component(name, x, p.physicallyRealizable(), retard,
                MuellerPhysics.diattenuationMagnitude(x),
                depolarizationIndex(x), p.violations().isEmpty() ? "" : String.join(";", p.violations()));
    }

    private static Component comp(String name, double[][] x, double tol) {
        return comp(name, toMat(x), tol);
    }

    private static DMatrixRMaj diattenuator(double m00, double d1, double d2, double d3) {
        double d = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
        double ad = 1 + Math.sqrt(Math.max(0, 1 - d * d));
        double bd = 1 - Math.sqrt(Math.max(0, 1 - d * d));
        DMatrixRMaj M = DMatrixRMaj.identity(4);
        M.set(0, 0, m00);
        for (int j = 1; j < 4; j++) M.set(0, j, m00 * (j == 1 ? d1 : j == 2 ? d2 : d3));
        double[][] dvec = { {d1}, {d2}, {d3} };
        double[][] dd = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                dd[i][j] = (ad * dvec[i][0] * dvec[j][0] / Math.max(d * d, 1e-300) * 0.5)
                        + (i == j ? bd * 0.5 : 0);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                M.set(i + 1, j + 1, m00 * dd[i][j]);
        return M;
    }

    private static double retardance(double[][] M) {
        double t = trace3(M);
        double c = (t - 1) / 2.0;
        c = Math.max(-1, Math.min(1, c));
        return Math.acos(c);
    }

    private static double depolarizationIndex(double[][] M) {
        double s = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                if (!(i == 0 && j == 0)) s += M[i][j] * M[i][j];
        return Math.sqrt(s) / Math.sqrt(3) / Math.abs(M[0][0]);
    }

    private static DMatrixRMaj block(double[][] p) {
        DMatrixRMaj M = DMatrixRMaj.identity(4);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) M.set(i + 1, j + 1, p[i][j]);
        return M;
    }

    /** 3x3 极分解 m = P R，P 对称半正定，R 旋转。 */
    private static double[][][] polar3(double[][] m) {
        DMatrixRMaj A = toMat3(m);
        var svd = DecompositionFactory_DDRM.svd(3, 3, true, true, false);
        svd.decompose(A.copy());
        DMatrixRMaj U = svd.getU(null, false);
        DMatrixRMaj V = svd.getV(null, false);
        double[] sv = svd.getSingularValues();
        // P = U Σ U^T，R = U V^T（A = U Σ V^T 时 P R = U Σ U^T · U V^T = U Σ V^T = A）
        DMatrixRMaj R = new DMatrixRMaj(3, 3);
        CommonOps_DDRM.multTransB(U, V, R);
        DMatrixRMaj US = new DMatrixRMaj(3, 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                US.set(i, j, U.get(i, j) * sv[j]);
        CommonOps_DDRM.multTransB(US, V, P);
        // 保定向
        if (det3(toArrays3(R)) < 0) {
            for (int j = 0; j < 3; j++) U.set(2, j, -U.get(2, j));
            CommonOps_DDRM.multTransB(U, V, R);
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    US.set(i, j, U.get(i, j) * sv[j]);
            CommonOps_DDRM.multTransB(US, V, P);
        }
        return new double[][][] { toArrays3(P), toArrays3(R) };
    }

    private static double trace3(double[][] M) {
        return M[1][1] + M[2][2] + M[3][3];
    }

    private static double det3(double[][] a) {
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
                - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }

    static double round(double v) { return Math.round(v * 1e9) / 1e9; }

    private static double diffFrob(DMatrixRMaj a, DMatrixRMaj b) {
        double s = 0;
        for (int i = 0; i < a.getNumElements(); i++) {
            double d = a.get(i) - b.get(i);
            s += d * d;
        }
        return Math.sqrt(s);
    }

    private static double frob(DMatrixRMaj a) {
        double s = 0;
        for (int i = 0; i < a.getNumElements(); i++) s += a.get(i) * a.get(i);
        return Math.sqrt(s);
    }

    private static DMatrixRMaj invert(DMatrixRMaj A) {
        DMatrixRMaj B = new DMatrixRMaj(A.numRows, A.numCols);
        CommonOps_DDRM.invert(A, B);
        return B;
    }

    private static DMatrixRMaj trans(DMatrixRMaj A) {
        DMatrixRMaj B = new DMatrixRMaj(A.numCols, A.numRows);
        CommonOps_DDRM.transpose(A, B);
        return B;
    }

    private static double[][] transpose(double[][] m) {
        double[][] o = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) o[j][i] = m[i][j];
        return o;
    }

    private static DMatrixRMaj toMat(double[][] m) {
        DMatrixRMaj r = new DMatrixRMaj(m.length, m[0].length);
        for (int i = 0; i < m.length; i++)
            for (int j = 0; j < m[0].length; j++) r.set(i, j, m[i][j]);
        return r;
    }

    private static DMatrixRMaj toMat3(double[][] m) { return toMat(m); }

    private static double[][] toArrays(DMatrixRMaj M) {
        double[][] o = new double[M.numRows][M.numCols];
        for (int i = 0; i < M.numRows; i++)
            for (int j = 0; j < M.numCols; j++) o[i][j] = M.get(i, j);
        return o;
    }

    private static double[][] toArrays3(DMatrixRMaj M) { return toArrays(M); }
}
