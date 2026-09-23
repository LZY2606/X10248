package poloptics.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * GLS/OLS Mueller estimator from intensity rows.
 *
 * I_k - dark_k = gain * W_k m,  W_k = kron(G_k^T, A_k^T).
 *
 * With per-measurement variances sigma2_k we whiten rows by 1/sigma.
 * Correlated errors are handled by passing a full measurement covariance
 * matrix (inverse-square-root whitening via Cholesky of the inverse).
 */
public final class MuellerSolver {
    public static final double SINGULAR_THRESHOLD = 1e-9; // relative sigma threshold
    public static final double NEAR_SINGULAR_WARN = 1e5;  // condition-number advisory band

    private MuellerSolver() {}

    public static EstimateResult estimate(double[][] w, double[] y, double[] variance) {
        int n = w.length;
        double[][] ww = new double[n][16];
        double[] yy = new double[n];
        for (int k = 0; k < n; k++) {
            double s = Math.sqrt(Math.max(variance[k], 1e-30));
            yy[k] = y[k] / s;
            for (int j = 0; j < 16; j++) ww[k][j] = w[k][j] / s;
        }
        return solveWhitened(ww, yy);
    }

    /** Correlated measurements: C (n x n) covariance; whiten with C^{-1/2}. */
    public static EstimateResult estimateCorrelated(double[][] w, double[] y, double[][] cov) {
        int n = w.length;
        double[][] halfInv = inverseSqrt(cov);
        double[][] ww = Mat.mul(halfInv, w);
        double[] yy = Mat.mul(halfInv, y);
        return solveWhitened(ww, yy);
    }

    private static EstimateResult solveWhitened(double[][] ww, double[] yy) {
        Mat.Svd svd = Mat.svd(ww);
        double[] sig = svd.sigma;
        double smax = sig[0];
        double tol = SINGULAR_THRESHOLD * Math.max(smax, 1e-30);
        int rank = 0;
        for (double v : sig) if (v > tol) rank++;
        int nullity = 16 - rank;
        double cond = sig[15] > tol ? sig[0] / sig[15] : Double.POSITIVE_INFINITY;

        // min-norm solution: m = V Sigma^+ U^T y
        double[] uty = new double[16];
        for (int j = 0; j < rank; j++) {
            double s = 0;
            for (int i = 0; i < yy.length; i++) s += svd.u[i][j] * yy[i];
            uty[j] = s / sig[j];
        }
        double[] m = new double[16];
        // V = transpose(Vt); m = V * uty
        for (int j = 0; j < 16; j++) {
            double s = 0;
            for (int k = 0; k < rank; k++) s += svd.vt[k][j] * uty[k];
            m[j] = s;
        }

        double[] fit = Mat.mul(ww, m);
        double ss = 0;
        for (int i = 0; i < yy.length; i++) ss += (yy[i] - fit[i]) * (yy[i] - fit[i]);
        double rms = Math.sqrt(ss / Math.max(1, yy.length - rank));

        // covariance = V_r diag(1/sigma^2) V_r^T (whitened space)
        double[][] covM = new double[16][16];
        for (int a = 0; a < 16; a++)
            for (int b = 0; b < 16; b++) {
                double s = 0;
                for (int k = 0; k < rank; k++) s += svd.vt[k][a] * svd.vt[k][b] / (sig[k] * sig[k]);
                covM[a][b] = s;
                if (a != b || nullity > 0) {
                    // mark undetermined directions with infinite variance
                    if (nullity > 0) {
                        double projSelf = 0;
                        for (int k = rank; k < 16; k++) projSelf += svd.vt[k][a] * svd.vt[k][a];
                        if (projSelf > 1e-12) covM[a][a] = Double.POSITIVE_INFINITY;
                    }
                }
            }

        List<double[]> nullspace = new ArrayList<>();
        for (int k = rank; k < 16; k++) {
            double[] vec = new double[16];
            System.arraycopy(svd.vt[k], 0, vec, 0, 16);
            nullspace.add(vec);
        }

        boolean solvable = nullity == 0 && Double.isFinite(cond);
        String diag;
        if (!solvable) {
            diag = "设计矩阵奇异：数值秩 " + rank + "/16，零空间维数 " + nullity
                    + "，保留最小二乘特解与零空间候选，未作强行求逆。";
        } else if (cond > NEAR_SINGULAR_WARN) {
            diag = "设计矩阵近奇异：条件数 " + String.format("%.3e", cond)
                    + "，解唯一但误差放大显著。";
        } else {
            diag = "设计矩阵满秩，条件数 " + String.format("%.3f", cond) + "。";
        }
        return new EstimateResult(solvable, cond, rank, nullity, m, covM, nullspace, rms, diag);
    }

    /** Symmetric inverse square root C^{-1/2} of SPD matrix via eigendecomposition. */
    static double[][] inverseSqrt(double[][] c) {
        int n = c.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(c[i], 0, a[i], 0, n);
        double[][] v = Mat.identity(n);
        double[] d = new double[n];
        jacobiPublic(a, v, d);
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++) d[i] = 1 / Math.sqrt(Math.max(d[i], 1e-30));
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++) r[i][j] += v[i][k] * d[k] * v[j][k];
        return r;
    }

    private static void jacobiPublic(double[][] a, double[][] v, double[] d) {
        int n = a.length;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-22) break;
            for (int p = 0; p < n; p++) for (int q = p + 1; q < n; q++) {
                if (Math.abs(a[p][q]) < 1e-18) continue;
                double tau = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                double cc = 1 / Math.sqrt(1 + t * t), s = t * cc;
                double app = a[p][p], aqq = a[q][q], apq = a[p][q];
                a[p][p] = app - t * apq; a[q][q] = aqq + t * apq; a[p][q] = a[q][p] = 0;
                for (int k = 0; k < n; k++) if (k != p && k != q) {
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = a[p][k] = cc * akp - s * akq;
                    a[k][q] = a[q][k] = s * akp + cc * akq;
                }
                for (int k = 0; k < n; k++) {
                    double vkp = v[k][p], vkq = v[k][q];
                    v[k][p] = cc * vkp - s * vkq;
                    v[k][q] = s * vkp + cc * vkq;
                }
            }
        }
        for (int i = 0; i < n; i++) d[i] = a[i][i];
    }
}
