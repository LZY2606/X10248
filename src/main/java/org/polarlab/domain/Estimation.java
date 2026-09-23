package org.polarlab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mueller 估计与误差传播。
 * 近奇异设计矩阵不强行求逆：返回 feasible=false 并给出全部零空间方向候选。
 */
public final class Estimation {
    public static final double NEAR_SINGULAR_RCOND = 1e-6;

    private Estimation() {}

    public static Result estimate(Mat design, double[] intensities, double[] variances,
                                  Mat instrumentW, Mat referenceSample,
                                  Convention convention) {
        Mat d = instrumentW != null ? instrumentW.mul(design) : design;
        double[] y = instrumentW != null ? instrumentW.mulVec(intensities) : intensities;
        if (referenceSample != null) {
            double refResponse = referenceResponse(d, referenceSample);
            if (Math.abs(refResponse) > 1e-12)
                for (int k = 0; k < y.length; k++) y[k] /= refResponse;
        }
        Mat.Svd svd = d.svd();
        double smax = svd.s[0];
        double smin = svd.s[d.cols == svd.s.length ? svd.s.length - 1 : svd.s.length - 1];
        double rcond = smax > 0 ? smin / smax : 0;
        double tol = NEAR_SINGULAR_RCOND * smax;
        int rank = svd.rank(Math.max(tol, 1e-12 * Math.max(1, smax)));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("convention", convention.toMap());
        info.put("nMeasurements", d.rows);
        info.put("rank", rank);
        info.put("singularValues", svd.s);
        info.put("sMin", smin);
        info.put("sMax", smax);
        info.put("conditionNumber", rcond > 0 ? 1 / rcond : Double.POSITIVE_INFINITY);
        info.put("rcond", rcond);
        info.put("instrumentCorrected", instrumentW != null);
        info.put("referenceNormalized", referenceSample != null);

        if (rank < d.cols) {
            // 不强行求逆；列出零空间中合法的最小修正候选，标注“无法唯一决定”
            java.util.List<Map<String, Object>> dirs = new java.util.ArrayList<>();
            for (int k = rank; k < svd.s.length; k++) {
                Map<String, Object> dir = new LinkedHashMap<>();
                double[] z = new double[16];
                for (int j = 0; j < 16; j++) z[j] = svd.vt.get(k, j);
                dir.put("nullDirection", z);
                dir.put("singularValue", svd.s[k]);
                dirs.add(dir);
            }
            // 最小范数解仍给出，但明确标记为 non-unique、供诊断而非裁定
            double[] mn = minNorm(svd, y);
            Mat cand = PolarOptics.fromVec(mn);
            PolarOptics.Physicality ph = PolarOptics.checkPhysical(cand);
            info.put("feasible", false);
            info.put("status", rank == 0 ? "SINGULAR_DESIGN" : "NEAR_SINGULAR_DESIGN");
            info.put("nullSpace", dirs);
            info.put("minNormDiagnostic", resultMap(cand, ph, convention));
            info.put("candidates", java.util.List.of());
            return new Result(false, cand, null, info);
        }

        Mat cov = covariance(svd, variances, d.rows, d.cols);
        double[] x = minNorm(svd, y);
        Mat m = PolarOptics.fromVec(x);
        PolarOptics.Physicality ph = PolarOptics.checkPhysical(m);

        // 显式噪声候选：确定性扰动（sigma 缩放的固定方向），用于检验非物理噪声解标注
        java.util.List<Map<String, Object>> candidates = new java.util.ArrayList<>();
        candidates.add(resultMap(m, ph, convention));
        Mat noisy = deterministicNoiseCandidate(m, cov);
        PolarOptics.Physicality phN = PolarOptics.checkPhysical(noisy);
        Map<String, Object> noisyMap = resultMap(noisy, phN, convention);
        noisyMap.put("candidateId", "noise-realization-sigma2");
        noisyMap.put("note", "deterministic +2sigma diagnostic realization; never projected");
        candidates.add(noisyMap);

        info.put("feasible", true);
        info.put("status", "UNIQUE_ESTIMATE");
        info.put("candidates", candidates);
        info.put("covarianceVec16", cov.a);
        return new Result(true, m, cov, info);
    }

    private static double referenceResponse(Mat d, Mat ref) {
        double[] v = PolarOptics.toVec(ref);
        double[] r = d.mulVec(v);
        double s = 0;
        for (double x : r) s += x;
        return s / Math.max(1, r.length);
    }

    private static double[] minNorm(Mat.Svd svd, double[] y) {
        int n = svd.vt.cols;
        double[] z = new double[svd.s.length];
        for (int k = 0; k < svd.s.length; k++) {
            if (svd.s[k] <= 1e-14 * Math.max(1, svd.s[0])) { z[k] = 0; continue; }
            double uTy = 0;
            for (int i = 0; i < y.length; i++) uTy += svd.u.get(i, k) * y[i];
            z[k] = uTy / svd.s[k];
        }
        double[] x = new double[n];
        for (int j = 0; j < n; j++) {
            double s = 0;
            for (int k = 0; k < svd.s.length; k++) s += svd.vt.get(k, j) * z[k];
            x[j] = s;
        }
        return x;
    }

    /** Cov(vec M) = V S^-2 Vt * 单位权方差（相关误差通过相关块进入 variances 语义）。 */
    private static Mat covariance(Mat.Svd svd, double[] variances, int rows, int cols) {
        double sigma2 = 1;
        if (variances != null) {
            double s = 0; int n = 0;
            for (Double v : variances) if (v != null && Double.isFinite(v) && v > 0) { s += v; n++; }
            sigma2 = n > 0 ? s / n : 1;
        }
        Mat cov = new Mat(cols, cols);
        for (int i = 0; i < cols; i++)
            for (int j = 0; j < cols; j++) {
                double acc = 0;
                for (int k = 0; k < svd.s.length; k++) {
                    if (svd.s[k] <= 1e-14 * Math.max(1, svd.s[0])) continue;
                    acc += svd.vt.get(k, i) * svd.vt.get(k, j) / (svd.s[k] * svd.s[k]);
                }
                cov.set(i, j, acc * sigma2);
            }
        return cov;
    }

    private static Mat deterministicNoiseCandidate(Mat m, Mat cov) {
        Mat n = new Mat(4, 4);
        for (int i = 0; i < 16; i++) {
            double sd = cov != null ? Math.sqrt(Math.max(0, cov.get(i, i))) : 0;
            // 固定方向：按元素位置交替取 ±2σ，保证可复现、不使用随机数
            n.a[i] = m.a[i] + ((i % 2 == 0) ? 2 * sd : -2 * sd);
        }
        return n;
    }

    private static Map<String, Object> resultMap(Mat m, PolarOptics.Physicality ph, Convention c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("candidateId", "ls-estimate");
        map.put("elements", m.a);
        map.put("conventionFingerprint", c.fingerprint());
        map.put("physicallyRealizable", ph.physicallyRealizable());
        map.put("minCoherencyEigenvalue", ph.minEigenvalue());
        map.put("physicalityResidual", ph.residualNorm());
        map.put("violations", ph.violations());
        return map;
    }

    public record Result(boolean feasible, Mat matrix, Mat covariance, Map<String, Object> info) {}
}
