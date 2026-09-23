package org.gsb.mueller.core;

/**
 * Immutable dense complex matrix in row-major order (re[], im[]).
 * Only the operations needed for the Cloude coherency matrix are provided:
 * Hermitian eigendecomposition (complex cyclic Jacobi), products A^H A, and
 * sqrt of a positive semidefinite Hermitian matrix from its eigendecomposition.
 */
public final class CMat {
    public final int r, c;
    final double[] re, im;

    public CMat(int r, int c) {
        this.r = r; this.c = c;
        this.re = new double[r * c]; this.im = new double[r * c];
    }

    public static CMat zeros(int r, int c) { return new CMat(r, c); }

    public double re(int i, int j) { return re[i * c + j]; }
    public double im(int i, int j) { return im[i * c + j]; }

    public CMat set(int i, int j, double x, double y) {
        CMat m = new CMat(r, c);
        System.arraycopy(re, 0, m.re, 0, re.length);
        System.arraycopy(im, 0, m.im, 0, im.length);
        m.re[i * c + j] = x; m.im[i * c + j] = y;
        return m;
    }

    public CMat conjT() {
        CMat m = new CMat(c, r);
        for (int i = 0; i < r; i++)
            for (int j = 0; j < c; j++) {
                m.re[j * r + i] = re[i * c + j];
                m.im[j * r + i] = -im[i * c + j];
            }
        return m;
    }

    public CMat mul(CMat o) {
        if (c != o.r) throw new IllegalArgumentException("shape");
        CMat m = new CMat(r, o.c);
        for (int i = 0; i < r; i++)
            for (int k = 0; k < c; k++) {
                double ar = re[i * c + k], ai = im[i * c + k];
                if (ar == 0 && ai == 0) continue;
                for (int j = 0; j < o.c; j++) {
                    double br = o.re[k * o.c + j], bi = o.im[k * o.c + j];
                    m.re[i * o.c + j] += ar * br - ai * bi;
                    m.im[i * o.c + j] += ar * bi + ai * br;
                }
            }
        return m;
    }

    public CMat plus(CMat o) {
        CMat m = new CMat(r, c);
        for (int k = 0; k < re.length; k++) { m.re[k] = re[k] + o.re[k]; m.im[k] = im[k] + o.im[k]; }
        return m;
    }

    public CMat scale(double s) {
        CMat m = new CMat(r, c);
        for (int k = 0; k < re.length; k++) { m.re[k] = re[k] * s; m.im[k] = im[k] * s; }
        return m;
    }

    /** Complex eigendecomposition of a Hermitian matrix; eigenvalues descending. */
    public static EVD hermitianEVD(CMat A) {
        int n = A.r;
        double[][] wRe = new double[n][n], wIm = new double[n][n];
        double[][] vRe = new double[n][n], vIm = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) { wRe[i][j] = A.re[i * n + j]; wIm[i][j] = A.im[i * n + j]; }
        for (int i = 0; i < n; i++) vRe[i][i] = 1;
        for (int sweep = 0; sweep < 80; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int q = p + 1; q < n; q++) off += wRe[p][q] * wRe[p][q] + wIm[p][q] * wIm[p][q];
            if (off < 1e-28) break;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apqRe = wRe[p][q], apqIm = wIm[p][q];
                    double mag2 = apqRe * apqRe + apqIm * apqIm;
                    if (mag2 < 1e-28) continue;
                    // Solve the 2x2 Hermitian subproblem [app, z; z*, aqq]
                    double app = wRe[p][p], aqq = wRe[q][q];
                    // tau, theta for unitary rotation: Givens-like
                    double theta = Math.atan2(-2 * apqIm, aqq - app) / 2;
                    double cot2 = (aqq - app) / (2 * Math.sqrt(mag2));
                    double phi = Math.atan2(apqIm, apqRe);
                    // robust t for (aqq-app) via real 2x2 after phase removal
                    double alpha = app;
                    double beta = aqq;
                    double g = Math.cos(2 * theta) * apqRe - Math.sin(2 * theta) * apqIm;
                    double t;
                    double diff = (beta - alpha) - 2 * (Math.sin(2 * theta) * apqRe + Math.cos(2 * theta) * apqIm);
                    // fallback: direct Jacobi on transformed real pair
                    double x = Math.cos(2 * phi) * apqRe + Math.sin(2 * phi) * apqIm;
                    double tau = (aqq - app) / (2 * (Math.abs(x) < 1e-15 ? 1e-15 : x));
                    t = (tau >= 0 ? 1 : -1) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    // G = [[c, -s e^{-iphi}], [s e^{iphi}, c]]
                    double gmRe = -s * Math.cos(phi), gmIm = s * Math.sin(phi);
                    double gpRe = s * Math.cos(phi), gpIm = s * Math.sin(phi);
                    for (int k = 0; k < n; k++) {
                        double wpR = wRe[p][k], wpI = wIm[p][k];
                        double wqR = wRe[q][k], wqI = wIm[q][k];
                        // row p: c*wp + gm*wq
                        wRe[p][k] = c * wpR + gmRe * wqR - gmIm * wqI;
                        wIm[p][k] = c * wpI + gmRe * wqI + gmIm * wqR;
                        // row q: gp*wp + c*wq
                        wRe[q][k] = gpRe * wpR - gpIm * wpI + c * wqR;
                        wIm[q][k] = gpRe * wpI + gpIm * wpR + c * wqI;
                    }
                    for (int k = 0; k < n; k++) {
                        double wpR = wRe[k][p], wpI = wIm[k][p];
                        double wqR = wRe[k][q], wqI = wIm[k][q];
                        // col p: wp*c + wq*conj(gp) = wp*c + wq*(gpRe,-gpIm)
                        wRe[k][p] = wpR * c + wqR * gpRe + wqI * gpIm;
                        wIm[k][p] = wpI * c + wqR * -gpIm + wqI * gpRe;
                        // col q: wp*conj(gm) + wq*c ; conj(gm)=(gmRe,-gmIm)
                        wRe[k][q] = wpR * gmRe + wpI * gmIm + wqR * c;
                        wIm[k][q] = wpR * -gmIm + wpI * gmRe + wqI * c;
                    }
                    for (int k = 0; k < n; k++) {
                        double vpR = vRe[k][p], vpI = vIm[k][p];
                        double vqR = vRe[k][q], vqI = vIm[k][q];
                        vRe[k][p] = vpR * c + vqR * gpRe + vqI * gpIm;
                        vIm[k][p] = vpI * c + vqR * -gpIm + vqI * gpRe;
                        vRe[k][q] = vpR * gmRe + vpI * gmIm + vqR * c;
                        vIm[k][q] = vpR * -gmIm + vpI * gmRe + vqI * c;
                    }
                }
            }
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = wRe[i][i];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(d[y], d[x]));
        double[] dd = new double[n];
        CMat V = new CMat(n, n);
        for (int k = 0; k < n; k++) {
            int j = idx[k];
            dd[k] = d[j];
            for (int i = 0; i < n; i++) { V.re[i * n + k] = vRe[i][j]; V.im[i * n + k] = vIm[i][j]; }
        }
        return new EVD(dd, V);
    }

    public static final class EVD {
        public final double[] d;
        public final CMat V;
        EVD(double[] d, CMat V) { this.d = d; this.V = V; }
    }
}
