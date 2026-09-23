package org.polarlab.domain;

import java.util.Arrays;

/** 最小二乘线性代数工具：SVD、伪逆、克罗内克积、对称特征值（Jacobi）。 */
public final class Mat {
    public final int rows, cols;
    public final double[] a;

    public Mat(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.a = new double[rows * cols];
    }

    public static Mat of(double[][] m) {
        Mat r = new Mat(m.length, m[0].length);
        for (int i = 0; i < r.rows; i++)
            for (int j = 0; j < r.cols; j++) r.set(i, j, m[i][j]);
        return r;
    }

    public double get(int i, int j) { return a[i * cols + j]; }
    public void set(int i, int j, double v) { a[i * cols + j] = v; }

    static Mat eyePadded(int m, int n) {
        Mat r = new Mat(m, n);
        for (int i = 0; i < Math.min(m, n); i++) r.set(i, i, 1);
        return r;
    }

    public Mat copy() {
        Mat r = new Mat(rows, cols);
        System.arraycopy(a, 0, r.a, 0, a.length);
        return r;
    }

    public Mat transpose() {
        Mat r = new Mat(cols, rows);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) r.set(j, i, get(i, j));
        return r;
    }

    public Mat mul(Mat o) {
        if (cols != o.rows) throw new IllegalArgumentException("shape " + rows + "x" + cols + " * " + o.rows + "x" + o.cols);
        Mat r = new Mat(rows, o.cols);
        for (int i = 0; i < rows; i++)
            for (int k = 0; k < cols; k++) {
                double aik = get(i, k);
                if (aik == 0) continue;
                for (int j = 0; j < o.cols; j++)
                    r.a[i * r.cols + j] += aik * o.get(k, j);
            }
        return r;
    }

    /** A (.) x：x 以列向量或 4x4 行优先展平给出。 */
    public double[] mulVec(double[] x) {
        double[] r = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0;
            for (int j = 0; j < cols; j++) s += get(i, j) * x[j];
            r[i] = s;
        }
        return r;
    }

    public static Mat kron(Mat a, Mat b) {
        Mat r = new Mat(a.rows * b.rows, a.cols * b.cols);
        for (int ia = 0; ia < a.rows; ia++)
            for (int ja = 0; ja < a.cols; ja++) {
                double v = a.get(ia, ja);
                for (int ib = 0; ib < b.rows; ib++)
                    for (int jb = 0; jb < b.cols; jb++)
                        r.set(ia * b.rows + ib, ja * b.cols + jb, v * b.get(ib, jb));
            }
        return r;
    }

    public Mat add(Mat o) {
        Mat r = copy();
        for (int i = 0; i < a.length; i++) r.a[i] += o.a[i];
        return r;
    }

    public Mat scale(double s) {
        Mat r = new Mat(rows, cols);
        for (int i = 0; i < a.length; i++) r.a[i] = a[i] * s;
        return r;
    }

    /** 对称 4x4（或 n x n 对称）矩阵特征分解：返回 {eigenvalues(asc), V(columns=vectors)}。 */
    public static double[][] eigSym(Mat m) {
        int n = m.rows;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i][j] = m.get(i, j);
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1;
        int maxSweeps = 100;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-30) break;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-15) continue;
                    double app = a[p][p], aqq = a[q][q];
                    double tau = (aqq - app) / (2 * apq);
                    double t;
                    if (tau >= 0) t = 1.0 / (tau + Math.sqrt(1 + tau * tau));
                    else t = -1.0 / (-tau + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t), s = t * c;
                    a[p][p] = app - t * apq;
                    a[q][q] = aqq + t * apq;
                    a[p][q] = 0; a[q][p] = 0;
                    for (int k = 0; k < n; k++) {
                        if (k != p && k != q) {
                            double akp = a[k][p], akq = a[k][q];
                            a[k][p] = a[p][k] = c * akp - s * akq;
                            a[k][q] = a[q][k] = s * akp + c * akq;
                        }
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
        }
        double[] ev = new double[n];
        for (int i = 0; i < n; i++) ev[i] = a[i][i];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(ev[x], ev[y]));
        double[] es = new double[n];
        Mat vs = new Mat(n, n);
        for (int i = 0; i < n; i++) {
            es[i] = ev[idx[i]];
            for (int k = 0; k < n; k++) vs.set(k, i, v[k][idx[i]]);
        }
        return new double[][]{es, flat(vs)};
    }

    private static double[] flat(Mat m) {
        double[] r = new double[m.a.length];
        System.arraycopy(m.a, 0, r, 0, r.length);
        return r;
    }

    /** SVD 薄分解 A=U S Vt，结果对象携带最小奇异值与条件数。 */
    public Svd svd() {
        return SvdDecomp.compute(this);
    }

    public static final class Svd {
        public final Mat u;
        public final Mat vt;
        public final double[] s;
        public Svd(Mat u, double[] s, Mat vt) { this.u = u; this.s = s; this.vt = vt; }
        public double smin() { return s[s.length - 1]; }
        public double cond() { return s[0] <= 0 ? Double.POSITIVE_INFINITY : s[0] / Math.max(s[s.length - 1], 0); }
        public int rank(double tol) {
            int r = 0;
            for (double v : s) if (v > tol) r++;
            return r;
        }
    }

    /** 仅在最小奇异值/最大奇异值 >= rcond 时才返回逆；近奇异时拒绝（返回 null），不强行求逆。 */
    public double[] solveMinNorm(double[] b, double rcond) {
        Svd svd = svd();
        double tol = rcond * svd.s[0];
        if (svd.smin() <= tol) return null;
        int n = cols;
        double[] z = new double[svd.s.length];
        for (int k = 0; k < svd.s.length; k++)
            z[k] = dotColU(svd.u, k, b) / svd.s[k];
        double[] x = new double[n];
        for (int j = 0; j < n; j++)
            for (int k = 0; k < svd.s.length; k++)
                x[j] += svd.vt.get(k, j) * z[k];
        return x;
    }

    private static double dotColU(Mat u, int k, double[] b) {
        double s = 0;
        for (int i = 0; i < b.length; i++) s += u.get(i, k) * b[i];
        return s;
    }

    double subIdentityNorm() {
        Mat p = this;
        double s = 0;
        int n = Math.min(rows, cols);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double e = p.get(i, j) - (i == j ? 1 : 0);
                s += e * e;
            }
        return Math.sqrt(s);
    }

    public double frobenius() {
        double s = 0;
        for (double v : a) s += v * v;
        return Math.sqrt(s);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++)
                sb.append(String.format("%10.5g ", get(i, j)));
            sb.append('\n');
        }
        return sb.toString();
    }
}
