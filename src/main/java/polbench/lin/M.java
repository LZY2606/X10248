package polbench.lin;

import java.util.Arrays;

/**
 * Dense matrix primitives with no external math dependency.
 * SVD is one-sided Jacobi; symmetric eigen is cyclic Jacobi.
 */
public final class M {
    public final double[][] u, s, vt;
    public final double[] sv;

    private M(double[][] u, double[] sv, double[][] vt) {
        this.u = u;
        this.sv = sv;
        this.vt = vt;
        this.s = new double[sv.length][sv.length];
        for (int i = 0; i < sv.length; i++) s[i][i] = sv[i];
    }

    public static double[][] id(int n) {
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) a[i][i] = 1;
        return a;
    }

    public static double[][] tr(double[][] a) {
        int m = a.length, n = a[0].length;
        double[][] t = new double[n][m];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) t[j][i] = a[i][j];
        return t;
    }

    public static double[][] mul(double[][] a, double[][] b) {
        int m = a.length, k = b.length, n = b[0].length;
        double[][] c = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int p = 0; p < k; p++) {
                double aip = a[i][p];
                for (int j = 0; j < n; j++) c[i][j] += aip * b[p][j];
            }
        return c;
    }

    public static double[] mul(double[][] a, double[] x) {
        int m = a.length, n = a[0].length;
        double[] y = new double[m];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) y[i] += a[i][j] * x[j];
        return y;
    }

    public static double[][] tmul(double[][] a, double[][] b) { return mul(tr(a), b); }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    public static double norm2(double[] a) { return Math.sqrt(dot(a, a)); }

    public static double[][] scale(double[][] a, double k) {
        double[][] c = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) c[i][j] = a[i][j] * k;
        return c;
    }

    public static double[][] add(double[][] a, double[][] b) {
        double[][] c = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) c[i][j] = a[i][j] + b[i][j];
        return c;
    }

    /** One-sided Jacobi SVD of a (m >= n recommended; transposed internally otherwise). */
    public static M svd(double[][] a0) {
        boolean trans = a0.length < a0[0].length;
        double[][] a = trans ? tr(a0) : map(a0);
        int m = a.length, n = a[0].length;
        double[][] v = id(n);
        double eps = 1e-14;
        for (int iter = 0; iter < 100; iter++) {
            int rotated = 0;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double alpha = 0, beta = 0, gamma = 0;
                    for (int i = 0; i < m; i++) {
                        alpha += a[i][p] * a[i][p];
                        beta += a[i][q] * a[i][q];
                        gamma += a[i][p] * a[i][q];
                    }
                    if (Math.abs(gamma) <= eps * Math.sqrt(alpha * beta) + 1e-300) continue;
                    double zeta = (beta - alpha) / (2.0 * gamma);
                    double t = (zeta >= 0 ? 1 : -1) / (Math.abs(zeta) + Math.sqrt(1 + zeta * zeta));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    for (int i = 0; i < m; i++) {
                        double ap = a[i][p], aq = a[i][q];
                        a[i][p] = c * ap - s * aq;
                        a[i][q] = s * ap + c * aq;
                    }
                    for (int i = 0; i < n; i++) {
                        double vp = v[i][p], vq = v[i][q];
                        v[i][p] = c * vp - s * vq;
                        v[i][q] = s * vp + c * vq;
                    }
                    rotated++;
                }
            }
            if (rotated == 0) break;
        }
        double[] sig = new double[n];
        double[][] uu = new double[m][n];
        for (int j = 0; j < n; j++) {
            double nr = 0;
            for (int i = 0; i < m; i++) nr += a[i][j] * a[i][j];
            sig[j] = Math.sqrt(nr);
            if (sig[j] > 1e-300)
                for (int i = 0; i < m; i++) uu[i][j] = a[i][j] / sig[j];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(sig[y], sig[x]));
        double[] sv = new double[n];
        double[][] u2 = new double[m][n];
        double[][] v2 = new double[n][n];
        for (int j = 0; j < n; j++) {
            int k = order[j];
            sv[j] = sig[k];
            for (int i = 0; i < m; i++) u2[i][j] = uu[i][k];
            for (int i = 0; i < n; i++) v2[i][j] = v[i][k];
        }
        double[][] vtr = tr(v2);
        if (trans) {
            int r = Math.max(m, n);
            double[][] uBig = new double[r][r];
            double[][] vtBig = new double[r][r];
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) vtBig[i][j] = tr(u2)[i][j];
            double[][] vtrT = tr(vtr);
            for (int i = 0; i < m; i++)
                for (int j = 0; j < m; j++) uBig[i][j] = vtrT[i][j];
            double[] svBig = new double[r];
            System.arraycopy(sv, 0, svBig, 0, sv.length);
            return new M(uBig, svBig, vtBig);
        }
        return new M(u2, sv, vtr);
    }

    private static double[][] map(double[][] a) {
        double[][] c = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) c[i] = a[i].clone();
        return c;
    }

    /** Pseudoinverse via SVD; nullspace basis (right-singular vectors below tol). */
    public static double[][] pinv(double[][] a, double relTol) {
        M svd = svd(a);
        int n = svd.vt.length;
        double cutoff = relTol * svd.sv[0];
        double[][] sinv = new double[n][svd.u[0].length];
        for (int i = 0; i < svd.sv.length && i < n && i < svd.u[0].length; i++) {
            if (svd.sv[i] > cutoff) sinv[i][i] = 1.0 / svd.sv[i];
        }
        return mul(mul(tr(svd.vt), sinv), tr(svd.u));
    }

    public static double cond(double[][] a) {
        double[] sv = svd(a).sv;
        double min = sv[sv.length - 1];
        return min <= 0 ? Double.POSITIVE_INFINITY : sv[0] / min;
    }

    public static int rank(double[][] a, double relTol) {
        double[] sv = svd(a).sv;
        double cut = relTol * sv[0];
        int r = 0;
        for (double v : sv) if (v > cut) r++;
        return r;
    }

    /** Cyclic Jacobi eigen-decomposition of a real symmetric matrix. Returns {values desc, V columns}. */
    public static double[][][] syevd(double[][] a0) {
        int n = a0.length;
        double[][] a = map(a0);
        double[][] v = id(n);
        for (int iter = 0; iter < 100; iter++) {
            int rotated = 0;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) <= 1e-15) continue;
                    double tau = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    double app = a[p][p], aqq = a[q][q], apq = a[p][q];
                    a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
                    a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
                    a[p][q] = a[q][p] = 0;
                    for (int k = 0; k < n; k++) {
                        if (k == p || k == q) continue;
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = a[p][k] = c * akp - s * akq;
                        a[k][q] = a[q][k] = s * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double vp = v[k][p], vq = v[k][q];
                        v[k][p] = c * vp - s * vq;
                        v[k][q] = s * vp + c * vq;
                    }
                    rotated++;
                }
            }
            if (rotated == 0) break;
        }
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) vals[i] = a[i][i];
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> Double.compare(vals[y], vals[x]));
        double[] ev = new double[n];
        double[][] vv = new double[n][n];
        for (int j = 0; j < n; j++) {
            ev[j] = vals[ord[j]];
            for (int i = 0; i < n; i++) vv[i][j] = v[i][ord[j]];
        }
        return new double[][][]{new double[][]{ev}, vv};
    }

    public static double[] eigSym(double[][] a) { return syevd(a)[0][0]; }

    /** Inverse of symmetric positive-definite matrix via Cholesky. */
    public static double[][] invSPD(double[][] c0) {
        int n = c0.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = c0[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                l[i][j] = (i == j) ? Math.sqrt(Math.max(sum, 1e-30)) : sum / l[j][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int col = 0; col < n; col++) {
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = (i == col ? 1 : 0);
                for (int k = 0; k < i; k++) sum -= l[i][k] * y[k];
                y[i] = sum / l[i][i];
            }
            for (int i = n - 1; i >= 0; i--) {
                double sum = y[i];
                for (int k = i + 1; k < n; k++) sum -= l[k][i] * inv[k][col];
                inv[i][col] = sum / l[i];
            }
        }
        return inv;
    }

    public static double trace(double[][] a) {
        double t = 0;
        for (int i = 0; i < Math.min(a.length, a[0].length); i++) t += a[i][i];
        return t;
    }
}
