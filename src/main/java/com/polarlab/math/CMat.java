package com.polarlab.math;

/** Dense complex matrix h[row][col]. */
public final class CMat {
    public final int n;
    public final Cmplx[][] h;

    public CMat(int n) {
        this.n = n;
        this.h = new Cmplx[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) h[i][j] = Cmplx.ZERO;
    }

    public Cmplx get(int r, int c) { return h[r][c]; }

    public void set(int r, int c, Cmplx v) { h[r][c] = v; }

    /** Hermitian eigen-decomposition by complex Jacobi rotations. Eigenvalues descending. */
    public static final class Eigh {
        public final double[] eig;      // descending
        public final Cmplx[][] vec;     // vec[i][j]: component i of eigenvector j
        Eigh(double[] eig, Cmplx[][] vec) { this.eig = eig; this.vec = vec; }
    }

    public Eigh eigh() {
        Cmplx[][] a = new Cmplx[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) a[i][j] = h[i][j];
        Cmplx[][] v = new Cmplx[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) v[i][j] = i == j ? Cmplx.ONE : Cmplx.ZERO;

        for (int sweep = 0; sweep < 120; sweep++) {
            double off = 0;
            for (int p = 0; p < n - 1; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q].abs2();
            if (off < 1e-28) break;

            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    Cmplx apq = a[p][q];
                    if (apq.abs() < 1e-16) continue;
                    double app = a[p][p].re, aqq = a[q][q].re;
                    double theta = (aqq - app) / (2 * apq.abs());
                    double t;
                    if (Math.abs(theta) > 1e15) {
                        t = 1 / (2 * theta);
                    } else {
                        double root = Math.sqrt(1 + theta * theta);
                        t = 1 / (theta + (theta >= 0 ? root : -root));
                    }
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    Cmplx phase = apq.mul(1 / apq.abs()); // e^{i phi}
                    Cmplx ph = phase;
                    Cmplx phC = phase.conj();

                    // rows/cols p,q
                    for (int i = 0; i < n; i++) {
                        if (i != p && i != q) {
                            Cmplx aip = a[i][p], aiq = a[i][q];
                            a[i][p] = aip.mul(c).sub(phC.mul(s).mul(aiq));
                            a[p][i] = a[i][p].conj();
                            a[i][q] = aiq.mul(c).add(ph.mul(s).mul(aip));
                            a[q][i] = a[i][q].conj();
                        }
                        Cmplx vip = v[i][p], viq = v[i][q];
                        v[i][p] = vip.mul(c).sub(phC.mul(s).mul(viq));
                        v[i][q] = viq.mul(c).add(ph.mul(s).mul(vip));
                    }
                    a[p][p] = Cmplx.of(app - t * apq.abs());
                    a[q][q] = Cmplx.of(aqq + t * apq.abs());
                    a[p][q] = Cmplx.ZERO;
                    a[q][p] = Cmplx.ZERO;
                }
            }
        }
        double[] eig = new double[n];
        for (int i = 0; i < n; i++) eig[i] = a[i][i].re;
        // sort descending
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        java.util.Arrays.sort(ord, (x, y) -> Double.compare(eig[y], eig[x]));
        double[] es = new double[n];
        Cmplx[][] vs = new Cmplx[n][n];
        for (int j = 0; j < n; j++) {
            es[j] = eig[ord[j]];
            for (int i = 0; i < n; i++) vs[i][j] = v[i][ord[j]];
        }
        return new Eigh(es, vs);
    }

    /** Nearest PSD coherency matrix under a spectral clip, returned with eigenvalues. */
    public CMat clipNegativeEigenvalues() {
        Eigh e = eigh();
        CMat out = new CMat(n);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                Cmplx sum = Cmplx.ZERO;
                for (int j = 0; j < n; j++) {
                    double ev = Math.max(0, e.eig[j]);
                    sum = sum.add(e.vec[r][j].mul(e.vec[c][j].conj()).mul(ev));
                }
                out.h[r][c] = sum;
            }
        }
        return out;
    }
}
