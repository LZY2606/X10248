package org.gsb.mueller.core;

import java.util.Locale;

/**
 * Immutable dense real matrix with the small linear-algebra operations needed by
 * the Mueller chamber: multiply, transpose, inverse (LU), symmetric eigendecomposition
 * (Jacobi), and SVD (one-sided Jacobi) -- all without native/external libraries.
 */
public final class Mat {
    public final int r, c;
    final double[] a;

    public Mat(int r, int c) {
        if (r < 0 || c < 0) throw new IllegalArgumentException("negative dimension");
        this.r = r; this.c = c; this.a = new double[r * c];
    }

    public static Mat of(double[][] rows) {
        Mat m = new Mat(rows.length, rows[0].length);
        for (int i = 0; i < m.r; i++)
            for (int j = 0; j < m.c; j++) m.a[i * m.c + j] = rows[i][j];
        return m;
    }

    public static Mat zeros(int r, int c) { return new Mat(r, c); }

    public static Mat eye(int n) {
        Mat m = new Mat(n, n);
        for (int i = 0; i < n; i++) m.a[i * n + i] = 1.0;
        return m;
    }

    public static Mat col(double... v) {
        Mat m = new Mat(v.length, 1);
        System.arraycopy(v, 0, m.a, 0, v.length);
        return m;
    }

    public double get(int i, int j) { return a[i * c + j]; }

    public Mat set(int i, int j, double v) {
        Mat m = this.copy();
        m.a[i * c + j] = v;
        return m;
    }

    public double[] rowMajor() { return a.clone(); }

    public Mat copy() {
        Mat m = new Mat(r, c);
        System.arraycopy(a, 0, m.a, 0, a.length);
        return m;
    }

    public Mat T() {
        Mat m = new Mat(c, r);
        for (int i = 0; i < r; i++)
            for (int j = 0; j < c; j++) m.a[j * r + i] = a[i * c + j];
        return m;
    }

    public Mat plus(Mat o) {
        if (r != o.r || c != o.c) throw new IllegalArgumentException("shape");
        Mat m = new Mat(r, c);
        for (int k = 0; k < a.length; k++) m.a[k] = a[k] + o.a[k];
        return m;
    }

    public Mat minus(Mat o) { return plus(o.scale(-1)); }

    public Mat scale(double s) {
        Mat m = new Mat(r, c);
        for (int k = 0; k < a.length; k++) m.a[k] = a[k] * s;
        return m;
    }

    public Mat mul(Mat o) {
        if (c != o.r) throw new IllegalArgumentException("shape " + r + "x" + c + " * " + o.r + "x" + o.c);
        Mat m = new Mat(r, o.c);
        for (int i = 0; i < r; i++)
            for (int k = 0; k < c; k++) {
                double aik = a[i * c + k];
                if (aik == 0) continue;
                for (int j = 0; j < o.c; j++)
                    m.a[i * o.c + j] += aik * o.a[k * o.c + j];
            }
        return m;
    }

    public Mat mul(double s) { return scale(s); }

    public double trace() {
        double t = 0;
        int n = Math.min(r, c);
        for (int i = 0; i < n; i++) t += get(i, i);
        return t;
    }

    public double normF() {
        double s = 0;
        for (double v : a) s += v * v;
        return Math.sqrt(s);
    }

    /** LU inverse (partial pivoting); only for non-singular square matrices. */
    public Mat inv() {
        if (r != c) throw new IllegalStateException("not square");
        int n = r;
        double[][] lu = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a, i * n, lu[i], 0, n);
        int[] piv = new int[n];
        for (int i = 0; i < n; i++) piv[i] = i;
        for (int k = 0; k < n; k++) {
            int p = k;
            for (int i = k + 1; i < n; i++)
                if (Math.abs(lu[i][k]) > Math.abs(lu[p][k])) p = i;
            if (Math.abs(lu[p][k]) < 1e-14) throw new SingularException("matrix is singular");
            double[] tmp = lu[p]; lu[p] = lu[k]; lu[k] = tmp;
            int t = piv[p]; piv[p] = piv[k]; piv[k] = t;
            for (int i = k + 1; i < n; i++) {
                lu[i][k] /= lu[k][k];
                for (int j = k + 1; j < n; j++) lu[i][j] -= lu[i][k] * lu[k][j];
            }
        }
        Mat inv = new Mat(n, n);
        for (int col = 0; col < n; col++) {
            double[] e = new double[n];
            e[col] = 1.0;
            double[] b = new double[n];
            for (int i = 0; i < n; i++) b[i] = e[piv[i]];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                double s = b[i];
                for (int j = 0; j < i; j++) s -= lu[i][j] * y[j];
                y[i] = s;
            }
            for (int i = n - 1; i >= 0; i--) {
                double s = y[i];
                for (int j = i + 1; j < n; j++) s -= lu[i][j] * inv.a[j * n + col];
                inv.a[i * n + col] = s / lu[i][i];
            }
        }
        return inv;
    }

    /** Symmetric eigendecomposition by cyclic Jacobi rotations. A must be square symmetric. */
    public static EVD symmetricEVD(Mat a) {
        int n = a.r;
        double[][] q = new double[n][n];
        for (int i = 0; i < n; i++) q[i][i] = 1;
        double[][] w = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a.a, i * n, w[i], 0, n);
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int qq = p + 1; qq < n; qq++) off += w[p][qq] * w[p][qq];
            if (off < 1e-28) break;
            for (int p = 0; p < n - 1; p++) {
                for (int qq = p + 1; qq < n; qq++) {
                    double apq = w[p][qq];
                    if (Math.abs(apq) < 1e-15) continue;
                    double tau = (w[qq][qq] - w[p][p]) / (2 * apq);
                    double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    for (int k = 0; k < n; k++) {
                        double wpk = w[p][k], wqk = w[qq][k];
                        w[p][k] = c * wpk - s * wqk;
                        w[qq][k] = s * wpk + c * wqk;
                    }
                    for (int k = 0; k < n; k++) {
                        double wkp = w[k][p], wkq = w[k][qq];
                        w[k][p] = c * wkp - s * wkq;
                        w[k][qq] = s * wkp + c * wkq;
                    }
                    for (int k = 0; k < n; k++) {
                        double qkp = q[k][p], qkq = q[k][qq];
                        q[k][p] = c * qkp - s * qkq;
                        q[k][qq] = s * qkp + c * qkq;
                    }
                }
            }
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = w[i][i];
        for (int i = 1; i < n; i++) {
            double key = d[i]; int j = i - 1;
            while (j >= 0 && d[j] < key) {
                d[j + 1] = d[j];
                for (int k = 0; k < n; k++) { double t = q[k][j + 1]; q[k][j + 1] = q[k][j]; q[k][j] = t; }
                j--;
            }
            d[j + 1] = key;
        }
        Mat V = new Mat(n, n);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) V.a[i * n + j] = q[i][j];
        return new EVD(d, V);
    }

    /**
     * One-sided Jacobi SVD valid for any shape: A = U diag(s) Vt.
     * Singular values are returned ascending-largest first; nullspace vectors of A
     * (for a fat/short design matrix) appear as the last columns of V.
     */
    public static SVD svd(Mat A) {
        int m = A.r, n = A.c;
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1;
        double[][] w = new double[m][n];
        for (int i = 0; i < m; i++) System.arraycopy(A.a, i * n, w[i], 0, n);
        for (int sweep = 0; sweep < 80; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int q = p + 1; q < n; q++) {
                    double alpha = 0, beta = 0, gamma = 0;
                    for (int i = 0; i < m; i++) {
                        alpha += w[i][p] * w[i][p];
                        beta += w[i][q] * w[i][q];
                        gamma += w[i][p] * w[i][q];
                    }
                    off += gamma * gamma;
                    if (Math.abs(gamma) < 1e-15 * Math.sqrt(alpha * beta + 1e-300)) continue;
                    double zeta = (beta - alpha) / (2 * gamma);
                    double t = (zeta >= 0 ? 1 : -1) / (Math.abs(zeta) + Math.sqrt(1 + zeta * zeta));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    for (int i = 0; i < m; i++) {
                        double wp = w[i][p], wq = w[i][q];
                        w[i][p] = c * wp - s * wq;
                        w[i][q] = s * wp + c * wq;
                    }
                    for (int i = 0; i < n; i++) {
                        double vp = v[i][p], vq = v[i][q];
                        v[i][p] = c * vp - s * vq;
                        v[i][q] = s * vp + c * vq;
                    }
                }
            if (off < 1e-30) break;
        }
        double[] sig = new double[n];
        for (int j = 0; j < n; j++) {
            double norm = 0;
            for (int i = 0; i < m; i++) norm += w[i][j] * w[i][j];
            sig[j] = Math.sqrt(norm);
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(sig[y], sig[x]));
        double[] s = new double[n];
        Mat U = new Mat(m, n);
        Mat V = new Mat(n, n);
        for (int k = 0; k < n; k++) {
            int j = idx[k];
            s[k] = sig[j];
            for (int i = 0; i < n; i++) V.a[i * n + k] = v[i][j];
            if (sig[j] > 1e-14)
                for (int i = 0; i < m; i++) U.a[i * n + k] = w[i][j] / sig[j];
        }
        return new SVD(U, s, V);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r; i++) {
            sb.append('[');
            for (int j = 0; j < c; j++) {
                sb.append(String.format(Locale.ROOT, "% .6g", a[i * c + j]));
                if (j + 1 < c) sb.append(' ');
            }
            sb.append("]\n");
        }
        return sb.toString();
    }

    public static final class SingularException extends RuntimeException {
        public SingularException(String m) { super(m); }
    }

    public static final class EVD {
        public final double[] d;
        public final Mat V;
        EVD(double[] d, Mat V) { this.d = d; this.V = V; }
    }

    public static final class SVD {
        public final Mat U, V;
        public final double[] s;
        SVD(Mat U, double[] s, Mat V) { this.U = U; this.s = s; this.V = V; }
        public double cond() {
            double smax = s[0], smin = Double.POSITIVE_INFINITY;
            for (double v : s) if (v > 1e-12) smin = Math.min(smin, v);
            return smin == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : smax / smin;
        }
    }
}
