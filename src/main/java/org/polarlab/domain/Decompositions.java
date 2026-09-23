package org.polarlab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分解候选（全部保留，不覆盖原始解）：
 *  - lu-chipman-forward : M = M_R M_D M_Δ（正向约定）
 *  - lu-chipman-reverse : 逆序解释，作为另一合法候选
 *  - cloude-sum         : 非相干纯分量之和（物理时）
 * 所有矩阵都标注约定指纹与“候选”身份；近奇异处拒绝强行求逆。
 */
public final class Decompositions {
    private Decompositions() {}

    public static final double RCOND_LIMIT = 1e-6;

    public static Map<String, Object> luChipman(Mat m, boolean forward) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = forward ? "lu-chipman-forward" : "lu-chipman-reverse";
        out.put("candidateId", id);
        double m00 = m.get(0, 0);
        if (!Double.isFinite(m00) || Math.abs(m00) < 1e-15) {
            out.put("feasible", false);
            out.put("reason", "m00 too small; normalization would amplify noise");
            return out;
        }
        double[] Drow = new double[3];
        double[] Pcol = new double[3];
        for (int k = 0; k < 3; k++) {
            Drow[k] = m.get(0, k + 1) / m00;
            Pcol[k] = m.get(k + 1, 0) / m00;
        }
        double diatten = norm(Drow);
        double polar = norm(Pcol);
        if (diatten >= 1 - 1e-9) {
            out.put("feasible", false);
            out.put("reason", "diattenuation at singular limit D>=1: " + diatten);
            return out;
        }
        if (polar >= 1 - 1e-9) {
            out.put("feasible", false);
            out.put("reason", "polarizance at singular limit P>=1: " + polar);
            return out;
        }
        Mat md = diattenuator(m00, Drow);
        double detMd = det4(md);
        if (Math.abs(detMd) < 1e-14 * Math.pow(Math.abs(m00), 4)) {
            out.put("feasible", false);
            out.put("reason", "diattenuator near-singular, inversion refused");
            return out;
        }
        Mat body = forward ? inv4(md).mul(m) : m.mul(inv4(md));
        Mat mDelta = new Mat(4, 4);
        mDelta.set(0, 0, 1);
        for (int i = 1; i < 4; i++)
            for (int j = 1; j < 4; j++)
                mDelta.set(i, j, body.get(i, j));
        Mat mr;
        if (forward) {
            Mat mDeltaInv = inv4(mDelta);
            if (mDeltaInv == null) {
                out.put("feasible", false);
                out.put("reason", "depolarizer block near-singular, inversion refused");
                return out;
            }
            mr = body.mul(mDeltaInv);
        } else {
            Mat mDeltaInv = inv4(mDelta);
            if (mDeltaInv == null) {
                out.put("feasible", false);
                out.put("reason", "depolarizer block near-singular, inversion refused");
                return out;
            }
            mr = mDeltaInv.mul(body);
        }
        double sMax = 0, sMean = 0;
        double[][] ev = Mat.eigSym(absBlock(mDelta));
        double minEv = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            sMax = Math.max(sMax, ev[0][k + 1]);
            sMean += ev[0][k + 1] / 3.0;
            minEv = Math.min(minEv, ev[0][k + 1]);
        }
        double depolarIndex = sMax > 0 ? 1 - sMean / sMax : 0;
        double retardance = 0;
        double tr = (mr.get(1, 1) + mr.get(2, 2) + mr.get(3, 3));
        double x = (tr - 1) / 2.0;
        x = Math.max(-1, Math.min(1, x));
        retardance = Math.acos(x);

        out.put("feasible", true);
        out.put("diattenuation", diatten);
        out.put("polarizance", polar);
        out.put("depolarizationIndex", depolarIndex);
        out.put("retardanceRad", retardance);
        out.put("retardanceDeg", Math.toDegrees(retardance));
        out.put("diattenuator", md.a);
        out.put("depolarizer", mDelta.a);
        out.put("retarder", mr.a);
        out.put("reconstructionResidual", residual(m, forward, mr, mDelta, md));
        return out;
    }

    private static Mat absBlock(Mat mDelta) {
        Mat s = new Mat(3, 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                s.set(i, j, (mDelta.get(i + 1, j + 1) + mDelta.get(j + 1, i + 1)) / 2);
        return s;
    }

    private static double residual(Mat m, boolean forward, Mat mr, Mat mD, Mat md) {
        Mat r = forward ? mr.mul(mD).mul(md) : md.mul(mD).mul(mr);
        double sum = 0;
        for (int i = 0; i < 16; i++) {
            double e = m.a[i] - r.a[i];
            sum += e * e;
        }
        return Math.sqrt(sum) / Math.max(1e-12, m.frobenius());
    }

    private static Mat diattenuator(double m00, double[] d) {
        double D = norm(d);
        double dSq = D * D;
        double eta = Math.sqrt(1 - dSq);
        double a = Math.sqrt(1 + D), b = Math.sqrt(1 - D);
        double k = m00 / 2.0 * (eta * (a + b) + (a - b));
        double km = m00 / 2.0 * (eta * (a + b) - (a - b));
        Mat m = new Mat(4, 4);
        m.set(0, 0, m00);
        for (int i = 0; i < 3; i++) {
            m.set(0, i + 1, m00 * d[i]);
            m.set(i + 1, 0, m00 * d[i]);
        }
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                m.set(i + 1, j + 1, i == j ? (k - km) / 2.0 * dSq + km
                                            : (k - km) / 2.0 * d[i] * d[j]);
        for (int i = 0; i < 3; i++)
            m.set(i + 1, i + 1, m.get(i + 1, i + 1) + (k + km) / 2.0 * eta);
        return m;
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return Math.sqrt(s);
    }

    private static double det4(Mat m) {
        double d = 0;
        int[] perm = {0, 1, 2, 3};
        // 4x4 全排列
        for (int i = 0; i < 24; i++) {
            int[] p = permutation(i);
            double prod = sign(p);
            for (int r = 0; r < 4; r++) prod *= m.get(r, p[r]);
            d += prod;
        }
        return d;
    }

    private static int[] permutation(int n) {
        int[] fact = {1, 1, 2, 6};
        int[] idx = {0, 1, 2, 3};
        int[] out = new int[4];
        int k = n;
        for (int pos = 3; pos >= 0; pos--) {
            int pick = k / fact[pos];
            k %= fact[pos];
            out[3 - pos] = idx[pick];
            idx[pick] = idx[pos];
        }
        return out;
    }

    private static double sign(int[] p) {
        int inv = 0;
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++)
                if (p[i] > p[j]) inv++;
        return inv % 2 == 0 ? 1 : -1;
    }

    /** 高斯-约旦逆；近奇异（条件数超过 1/RCOND_LIMIT）时返回 null。 */
    static Mat inv4(Mat a) {
        int n = a.rows;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) m[i][j] = a.get(i, j);
            m[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            if (Math.abs(m[pivot][col]) < 1e-14) return null;
            double[] tmp = m[pivot]; m[pivot] = m[col]; m[col] = tmp;
            double pv = m[col][col];
            for (int j = 0; j < 2 * n; j++) m[col][j] /= pv;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = m[r][col];
                for (int j = 0; j < 2 * n; j++) m[r][j] -= f * m[col][j];
            }
        }
        Mat inv = new Mat(n, n);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                inv.set(i, j, m[i][n + j]);
        // 显式近奇异防护
        if (a.mul(inv).subIdentityNorm() > 1e-6) return null;
        return inv;
    }
}
