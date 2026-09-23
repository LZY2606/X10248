package org.polarlab.domain;

/**
 * 单侧 Jacobi SVD（A 列正交化）：AV = U·diag(S)。
 * 规模小（测量设计矩阵至多 ~32x16）且对秩亏损稳健，零奇异值精确保留。
 */
final class SvdDecomp {
    static Mat.Svd compute(Mat a0) {
        int m = a0.rows, n = a0.cols;
        if (m < n) {
            // 欠定：调用方应按“无法唯一决定”处理；给出全零最小奇异值信号
            Mat u = Mat.eyePadded(m, n);
            double[] s = new double[Math.min(m, n)];
            Mat vt = new Mat(n, n);
            return new Mat.Svd(u, s, vt);
        }
        double[][] b = new double[m][n];
        for (int i = 0; i < m; i++) for (int j = 0; j < n; j++) b[i][j] = a0.get(i, j);
        double[][] v = new double[n][n];
        for (int j = 0; j < n; j++) v[j][j] = 1;

        double tol = 1e-14;
        for (int sweep = 0; sweep < 80; sweep++) {
            boolean converged = true;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double alpha = 0, beta = 0, gamma = 0;
                    for (int i = 0; i < m; i++) {
                        alpha += b[i][p] * b[i][p];
                        beta += b[i][q] * b[i][q];
                        gamma += b[i][p] * b[i][q];
                    }
                    if (Math.abs(gamma) <= tol * Math.sqrt(alpha * beta)) continue;
                    converged = false;
                    double zeta = (beta - alpha) / (2 * gamma);
                    double t = (zeta >= 0 ? 1 : -1)
                            / (Math.abs(zeta) + Math.sqrt(1 + zeta * zeta));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    for (int i = 0; i < m; i++) {
                        double bp = b[i][p], bq = b[i][q];
                        b[i][p] = c * bp - s * bq;
                        b[i][q] = s * bp + c * bq;
                    }
                    for (int i = 0; i < n; i++) {
                        double vp = v[i][p], vq = v[i][q];
                        v[i][p] = c * vp - s * vq;
                        v[i][q] = s * vp + c * vq;
                    }
                }
            }
            if (converged) break;
        }

        double[] s = new double[n];
        double[][] u = new double[m][n];
        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < m; i++) norm += b[i][j] * b[i][j];
            norm = Math.sqrt(norm);
            s[j] = norm;
            if (norm > 1e-14) for (int i = 0; i < m; i++) u[i][j] = b[i][j] / norm;
        }
        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) order[j] = j;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(s[y], s[x]));
        double[] ss = new double[n];
        Mat uu = new Mat(m, n);
        Mat vv = new Mat(n, n);
        for (int k = 0; k < n; k++) {
            int j = order[k];
            ss[k] = s[j];
            for (int i = 0; i < m; i++) uu.set(i, k, u[i][j]);
            for (int i = 0; i < n; i++) vv.set(i, k, v[i][j]);
        }
        return new Mat.Svd(uu, ss, vv.transpose());
    }
}
