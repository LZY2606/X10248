package polarmueller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mueller 矩阵数值层：Stokes 约定、设计矩阵、条件数、最小二乘、Cloude 物理性、Lu-Chipman 分解。 */
public final class Mueller {

    private Mueller() {}

    /** 由偏振片角度（度）与波片标记生成 Stokes 向量；旋转正负与 Stokes 顺序由参数决定。 */
    public static double[] stokes(double polDeg, boolean circular, String rotation, String order) {
        double t = Math.toRadians(polDeg);
        double sign = "-".equals(rotation) ? -1.0 : 1.0;
        double q = Math.cos(2 * t);
        double u = sign * Math.sin(2 * t);
        double v = circular ? sign : 0.0;
        double[] iquv = {1.0, q, u, v};
        Map<Character, Integer> idx = Map.of('I', 0, 'Q', 1, 'U', 2, 'V', 3);
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[i] = iquv[idx.get(order.charAt(i))];
        return out;
    }

    /** 设计矩阵行：I = a^T M g => row = kron(a, g)，vec 按行主序。 */
    public static double[] designRow(double[] a, double[] g) {
        double[] row = new double[16];
        for (int j = 0; j < 4; j++)
            for (int k = 0; k < 4; k++) row[j * 4 + k] = a[j] * g[k];
        return row;
    }

    public static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] matmul(double[][] a, double[][] b) {
        int n = a.length, m = b[0].length, p = b.length;
        double[][] r = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < p; k++) {
                double s = a[i][k];
                if (s == 0) continue;
                for (int j = 0; j < m; j++) r[i][j] += s * b[k][j];
            }
        return r;
    }

    public static double[] matvec(double[][] a, double[] x) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < x.length; j++) r[i] += a[i][j] * x[j];
        return r;
    }

    /** Jacobi 特征值（对称矩阵），返回特征值数组。 */
    public static double[] jacobiEigen(double[][] ain) {
        int n = ain.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) a[i] = Arrays.copyOf(ain[i], n);
        for (int sweep = 0; sweep < 200; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-20) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-15) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = (theta >= 0 ? 1 : -1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double cs = 1 / Math.sqrt(t * t + 1);
                    double sn = t * cs;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = cs * akp - sn * akq;
                        a[k][q] = sn * akp + cs * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = cs * apk - sn * aqk;
                        a[q][k] = sn * apk + cs * aqk;
                    }
                }
            }
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = a[i][i];
        return d;
    }

    /** 条件数 = 最大/最小奇异值；最小奇异值<=0 时返回无穷大。 */
    public static double conditionNumber(double[][] a) {
        double[][] ata = matmul(transpose(a), a);
        double[] eig = jacobiEigen(ata);
        double max = 0, min = Double.POSITIVE_INFINITY;
        for (double e : eig) {
            max = Math.max(max, e);
            min = Math.min(min, e);
        }
        if (min <= 1e-12) return Double.POSITIVE_INFINITY;
        return Math.sqrt(max / min);
    }

    /** Gauss-Jordan 求逆；奇异时抛异常。 */
    public static double[][] invert(double[][] ain) {
        int n = ain.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(ain[i], 0, a[i], 0, n);
            a[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            if (Math.abs(a[piv][col]) < 1e-14) throw new ArithmeticException("singular matrix");
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            double d = a[col][col];
            for (int j = 0; j < 2 * n; j++) a[col][j] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = a[r][col];
                if (f == 0) continue;
                for (int j = 0; j < 2 * n; j++) a[r][j] -= f * a[col][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], n, inv[i], 0, n);
        return inv;
    }

    /** 最小二乘解（调用方须先确认条件数可接受）。 */
    public static double[] leastSquares(double[][] a, double[] b) {
        double[][] ata = matmul(transpose(a), a);
        double[] atb = matvec(transpose(a), b);
        return matvec(invert(ata), atb);
    }

    /** 广义最小二乘的协方差：(A^T S^-1 A)^-1。 */
    public static double[][] glsCovariance(double[][] a, double[][] cov) {
        double[][] cinv = invert(cov);
        double[][] at = transpose(a);
        return invert(matmul(matmul(at, cinv), a));
    }

    /** Cloude 相干矩阵特征值（4 个），用于物理可实现性判定。 */
    public static double[] cloudeEigenvalues(double[][] m) {
        // Pauli: s0=I, s1, s2 实；s3 纯虚，conj(s3) = -s3
        // sigma1=[[1,0],[0,-1]], sigma2=[[0,1],[1,0]], sigma3=[[0,-i],[i,0]]
        double[][] re = new double[4][4];
        double[][] im = new double[4][4];
        for (int p = 0; p < 4; p++) {
            for (int q = 0; q < 4; q++) {
                double mn = m[p][q];
                if (mn == 0) continue;
                // kron(sigma_p, conj(sigma_q))
                for (int i = 0; i < 2; i++)
                    for (int j = 0; j < 2; j++)
                        for (int k = 0; k < 2; k++)
                            for (int l = 0; l < 2; l++) {
                                double[] sp = pauli(p, i, j);
                                double[] sq = pauli(q, k, l);
                                double conjIm = (q == 3) ? -sq[1] : sq[1];
                                // (sp.re + i sp.im)*(sq.re + i conjIm)
                                re[2 * i + k][2 * j + l] +=
                                    mn * 0.25 * (sp[0] * sq[0] - sp[1] * conjIm);
                                im[2 * i + k][2 * j + l] +=
                                    mn * 0.25 * (sp[0] * conjIm + sp[1] * sq[0]);
                            }
            }
        }
        // Hermitian 4x4 -> 实对称 8x8 [[Re,-Im],[Im,Re]]，特征值成对
        double[][] big = new double[8][8];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                big[i][j] = re[i][j];
                big[i][4 + j] = -im[i][j];
                big[4 + i][j] = im[i][j];
                big[4 + i][4 + j] = re[i][j];
            }
        double[] eig = jacobiEigen(big);
        Arrays.sort(eig);
        return new double[] {eig[0], eig[2], eig[4], eig[6]};
    }

    private static double[] pauli(int idx, int i, int j) {
        // 返回 {re, im}
        switch (idx) {
            case 0:
                return new double[] {i == j ? 1 : 0, 0};
            case 1:
                if (i == j) return new double[] {i == 0 ? 1 : -1, 0};
                return new double[] {0, 0};
            case 2:
                return new double[] {i != j ? 1 : 0, 0};
            default:
                if (i == 0 && j == 1) return new double[] {0, -1};
                if (i == 1 && j == 0) return new double[] {0, 1};
                return new double[] {0, 0};
        }
    }

    /** Lu-Chipman 风格分解候选；违约只标记，不投影修正。 */
    public static Map<String, Object> decompose(double[][] m, String method) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();
        double m00 = m[0][0];
        double[] d = new double[3];
        double dn = 0;
        for (int i = 0; i < 3; i++) {
            d[i] = m00 != 0 ? m[0][i + 1] / m00 : 0;
            dn += d[i] * d[i];
        }
        dn = Math.sqrt(dn);
        if (m00 <= 0) violations.add("m00<=0：总强度非正");
        if (dn >= 1) violations.add("diattenuation>=1：起偏度越界");
        double retardanceDeg = Double.NaN;
        double depolResidual = Double.NaN;
        try {
            double s = Math.sqrt(Math.max(0, 1 - dn * dn));
            double[][] md = new double[4][4];
            md[0][0] = m00;
            for (int i = 0; i < 3; i++) {
                md[0][i + 1] = m00 * d[i];
                md[i + 1][0] = m00 * d[i];
                for (int j = 0; j < 3; j++) {
                    double base = (i == j) ? s : 0;
                    double extra = dn > 1e-12 ? (1 - s) * d[i] * d[j] / (dn * dn) : 0;
                    md[i + 1][j + 1] = m00 * (base + extra);
                }
            }
            double[][] mdInv = invert(md);
            double[][] mp = "reverse".equals(method) ? matmul(mdInv, m) : matmul(m, mdInv);
            double n00 = mp[0][0];
            double[][] sub = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) sub[i][j] = mp[i + 1][j + 1] / n00;
            double[][] r = polarRotation(sub);
            double tr = r[0][0] + r[1][1] + r[2][2];
            retardanceDeg = Math.toDegrees(Math.acos(clamp((tr - 1) / 2, -1, 1)));
            double res = 0;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    double diff = sub[i][j] - r[i][j];
                    res += diff * diff;
                }
            depolResidual = Math.sqrt(res);
        } catch (ArithmeticException e) {
            violations.add("分解矩阵奇异：" + e.getMessage());
        }
        out.put("method", method);
        out.put("diattenuation", dn);
        out.put("diattenuationVector", d);
        out.put("retardanceDeg", retardanceDeg);
        out.put("depolResidual", depolResidual);
        out.put("violations", violations);
        return out;
    }

    /** 3x3 矩阵的正交极因子（Newton 迭代）。 */
    static double[][] polarRotation(double[][] m) {
        double[][] x = new double[3][3];
        for (int i = 0; i < 3; i++) x[i] = Arrays.copyOf(m[i], 3);
        for (int it = 0; it < 50; it++) {
            double[][] invT = transpose(invert(x));
            double[][] next = new double[3][3];
            double diff = 0;
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    next[i][j] = 0.5 * (x[i][j] + invT[i][j]);
                    diff = Math.max(diff, Math.abs(next[i][j] - x[i][j]));
                }
            x = next;
            if (diff < 1e-12) break;
        }
        return x;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static double frobeniusDiff(double[][] a, double[][] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[i].length; j++) {
                double d = a[i][j] - b[i][j];
                s += d * d;
            }
        return Math.sqrt(s);
    }
}
