package pol;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小自包含线性代数：实对称矩阵 Jacobi 特征分解、SVD/伪逆、条件数、
 * 以及把复 Hermitian 矩阵实化为 2n 阶实对称矩阵的特征值工具。
 * 全部为领域层（偏振估计）服务，不引入外部数值库，保证完全离线。
 */
public final class LinAlg {

    private LinAlg() {}

    public static final class EigResult {
        public final double[] values;
        public final double[][] vectors;
        EigResult(double[] values, double[][] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /** 实对称矩阵 Jacobi 循环扫描，返回升序特征值及对应特征向量列。 */
    public static EigResult symmetricEigen(double[][] aIn) {
        int n = aIn.length;
        double[][] a = copy(aIn);
        double[][] v = identity(n);
        double tolerance = 1e-14;
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < tolerance * tolerance) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-15) continue;
                    double app = a[p][p];
                    double aqq = a[q][q];
                    double tau = (aqq - app) / (2.0 * apq);
                    double t;
                    if (tau >= 0) t = 1.0 / (tau + Math.sqrt(1.0 + tau * tau));
                    else t = -1.0 / (-tau + Math.sqrt(1.0 + tau * tau));
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double s = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p];
                        double vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] vals = new double[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            vals[i] = a[i][i];
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(vals[x], vals[y]));
        double[] sortedVals = new double[n];
        double[][] sortedVecs = new double[n][n];
        for (int i = 0; i < n; i++) {
            sortedVals[i] = vals[idx[i]];
            for (int k = 0; k < n; k++) sortedVecs[k][i] = v[k][idx[i]];
        }
        return new EigResult(sortedVals, sortedVecs);
    }

    /** 复 Hermitian 矩阵（实部 re，虚部 im）特征值，经 2n 实对称化后取一半。 */
    public static double[] hermitianEigenvalues(double[][] re, double[][] im) {
        int n = re.length;
        double[][] big = new double[2 * n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                big[i][j] = re[i][j];
                big[n + i][n + j] = re[i][j];
                big[i][n + j] = -im[i][j];
                big[n + i][j] = im[i][j];
            }
        }
        EigResult er = symmetricEigen(big);
        double[] half = new double[n];
        System.arraycopy(er.values, 0, half, 0, n);
        return half;
    }

    public static final class SvdSolve {
        public final boolean solved;
        public final double[] x;
        public final double[] singularValues;
        public final double condition;
        public final int rank;
        public final int nullity;
        public final double[][] nullSpace;
        public final double residualNorm;
        public final String diagnosis;

        SvdSolve(boolean solved, double[] x, double[] sv, double condition, int rank,
                 int nullity, double[][] nullSpace, double residualNorm, String diagnosis) {
            this.solved = solved;
            this.x = x;
            this.singularValues = sv;
            this.condition = condition;
            this.rank = rank;
            this.nullity = nullity;
            this.nullSpace = nullSpace;
            this.residualNorm = residualNorm;
            this.diagnosis = diagnosis;
        }
    }

    /**
     * 通过 W^T W 的特征分解求解 W x = b。
     * 近奇异（最小奇异值/最大奇异值 &lt; threshold 或秩亏）时不强行求逆：
     * solved=false，返回奇异值、秩、零空间，供上层保留全部合法候选。
     */
    public static SvdSolve svdSolve(double[][] w, double[] b, double threshold) {
        int rows = w.length;
        int cols = w[0].length;
        double[][] g = new double[cols][cols];
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < cols; j++) {
                double s = 0;
                for (int k = 0; k < rows; k++) s += w[k][i] * w[k][j];
                g[i][j] = s;
            }
        }
        EigResult eig = symmetricEigen(g);
        double[] sv = new double[cols];
        for (int i = 0; i < cols; i++) sv[i] = Math.sqrt(Math.max(0, eig.values[i]));
        double smax = sv[cols - 1];
        int rank = 0;
        for (double v : sv) if (v > threshold * Math.max(1.0, smax)) rank++;
        int nullity = cols - rank;
        double cond = rank == 0 ? Double.POSITIVE_INFINITY : smax / sv[cols - rank];
        boolean singular = nullity > 0;

        List<double[]> nullBasis = new ArrayList<>();
        for (int i = 0; i < cols - rank; i++) {
            double[] vec = new double[cols];
            for (int k = 0; k < cols; k++) vec[k] = eig.vectors[k][i];
            nullBasis.add(normalize(vec));
        }
        if (singular) {
            return new SvdSolve(false, null, sv, cond, rank, nullity,
                    nullBasis.toArray(new double[0][]), Double.NaN,
                    "NEAR_SINGULAR_DESIGN: rank=" + rank + "/" + cols
                            + ", condition=" + fmt(cond) + "; 不强行求逆");
        }
        double[] x = new double[cols];
        for (int j = 0; j < cols; j++) {
            double[] uj = new double[cols];
            for (int k = 0; k < cols; k++) uj[k] = eig.vectors[k][j];
            double wtb = 0;
            for (int k = 0; k < cols; k++) {
                double wtbk = 0;
                for (int r = 0; r < rows; r++) wtbk += w[r][k] * b[r];
                wtb += uj[k] * wtbk;
            }
            double coef = wtb / sv[j];
            for (int k = 0; k < cols; k++) x[k] += coef * uj[k];
        }
        double resid = 0;
        for (int r = 0; r < rows; r++) {
            double pred = 0;
            for (int k = 0; k < cols; k++) pred += w[r][k] * x[k];
            double d = b[r] - pred;
            resid += d * d;
        }
        return new SvdSolve(true, x, sv, cond, rank, 0,
                new double[0][], Math.sqrt(resid),
                "OK: rank=" + rank + ", condition=" + fmt(cond));
    }

    /** Cov(m) = Pinv Sigma Pinv^T；Sigma 为测量协方差（对角或完整相关阵）。 */
    public static double[][] parameterCovariance(double[][] w, double[][] sigma, double threshold) {
        int rows = w.length;
        int cols = w[0].length;
        double[][] g = new double[cols][cols];
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < cols; j++) {
                double s = 0;
                for (int k = 0; k < rows; k++) s += w[k][i] * w[k][j];
                g[i][j] = s;
            }
        }
        EigResult eig = symmetricEigen(g);
        double smax = Math.sqrt(Math.max(0, eig.values[cols - 1]));
        double[][] pinv = new double[cols][rows];
        for (int j = 0; j < cols; j++) {
            double s = Math.sqrt(Math.max(0, eig.values[j]));
            if (s <= threshold * Math.max(1.0, smax)) continue;
            for (int r = 0; r < rows; r++) {
                double wr = 0;
                for (int k = 0; k < cols; k++) wr += eig.vectors[k][j] * w[r][k];
                for (int k = 0; k < cols; k++) pinv[k][r] += eig.vectors[k][j] * wr / s;
            }
        }
        double[][] cov = new double[cols][cols];
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < cols; j++) {
                double s = 0;
                for (int a = 0; a < rows; a++) {
                    for (int b = 0; b < rows; b++) s += pinv[i][a] * sigma[a][b] * pinv[j][b];
                }
                cov[i][j] = s;
            }
        }
        return cov;
    }

    /** 实对称矩阵的对称平方根 X，X^2 = a（特征值被夹到非负，仅供分解内部使用）。 */
    public static double[][] symmetricSqrt(double[][] a) {
        EigResult eig = symmetricEigen(a);
        int n = a.length;
        double[][] x = new double[n][n];
        for (int j = 0; j < n; j++) {
            double root = Math.sqrt(Math.max(0, eig.values[j]));
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) x[i][k] += root * eig.vectors[i][j] * eig.vectors[k][j];
            }
        }
        return x;
    }

    public static double[][] inverse3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2];
        double d = m[1][0], e = m[1][1], f = m[1][2];
        double g = m[2][0], h = m[2][1], i = m[2][2];
        double a2 = e * i - f * h, b2 = -(d * i - f * g), c2 = d * h - e * g;
        double det = a * a2 + b * b2 + c * c2;
        double id = 1.0 / det;
        return new double[][]{
                {a2 * id, (c * h - b * i) * id, (b * f - c * e) * id},
                {b2 * id, (a * i - c * g) * id, (c * d - a * f) * id},
                {c2 * id, (b * g - a * h) * id, (a * e - b * d) * id}};
    }

    public static double[][] matMul(double[][] a, double[][] b) {
        int n = a.length, m = b[0].length, k = b.length;
        double[][] r = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int p = 0; p < k; p++) s += a[i][p] * b[p][j];
                r[i][j] = s;
            }
        return r;
    }

    public static double[][] transpose(double[][] a) {
        double[][] r = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[j][i] = a[i][j];
        return r;
    }

    public static double frobDiff(double[][] a, double[][] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) {
                double d = a[i][j] - b[i][j];
                s += d * d;
            }
        return Math.sqrt(s);
    }

    static double[] normalize(double[] v) {
        double n = 0;
        for (double x : v) n += x * x;
        n = Math.sqrt(n);
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) r[i] = v[i] / n;
        return r;
    }

    static double[][] identity(int n) {
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++) r[i][i] = 1;
        return r;
    }

    static double[][] copy(double[][] a) {
        double[][] r = new double[a.length][];
        for (int i = 0; i < a.length; i++) r[i] = a[i].clone();
        return r;
    }

    static String fmt(double v) {
        if (Double.isInfinite(v)) return "Inf";
        if (Double.isNaN(v)) return "NaN";
        return String.format(java.util.Locale.ROOT, "%.4g", v);
    }
}
