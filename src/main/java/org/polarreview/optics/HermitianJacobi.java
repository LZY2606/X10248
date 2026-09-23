package org.polarreview.optics;

/**
 * Cyclic Jacobi eigendecomposition for small Hermitian matrices (the Cloude
 * coherency matrix is 4x4). Each 2x2 block is diagonalized in two steps:
 * a diagonal phase unitary removes the imaginary part, then a standard real
 * symmetric Jacobi rotation zeros the off-diagonal. Pure-Java so the whole
 * case pack is fully offline.
 */
public final class HermitianJacobi {
    public final double[] eigenvalues;        // ascending
    public final double[][] eigenvectorsReal; // columns correspond to eigenvalues
    public final double[][] eigenvectorsImag;

    private HermitianJacobi(double[] ev, double[][] vr, double[][] vi) {
        this.eigenvalues = ev;
        this.eigenvectorsReal = vr;
        this.eigenvectorsImag = vi;
    }

    public static HermitianJacobi decompose(double[][] reIn, double[][] imIn) {
        int n = reIn.length;
        double[][] hr = copy(reIn);
        double[][] hi = copy(imIn);
        double[][] vr = identity(n);
        double[][] vi = new double[n][n];

        for (int sweep = 0; sweep < 200; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++)
                    off += hr[p][q] * hr[p][q] + hi[p][q] * hi[p][q];
            if (off < 1e-28) break;

            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    double zr = hr[p][q], zi = hi[p][q];
                    if (zr * zr + zi * zi < 1e-24) continue;

                    // G columns: G0 = (c, -s e^{+i a}), G1 = (s, c e^{+i a})
                    double a = Math.atan2(zi, zr);
                    double ca = Math.cos(a), sa = Math.sin(a);
                    double tau = (hr[p][p] - hr[q][q]) / (2 * Math.hypot(zr, zi));
                    double t = (tau >= 0)
                            ? 1.0 / (tau + Math.sqrt(1 + tau * tau))
                            : -1.0 / (-tau + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;

                    double[][] gr = {
                            {c, s},
                            {-s * ca, c * ca}
                    };
                    double[][] gi = {
                            {0, 0},
                            {-s * sa, c * sa}
                    };
                    applyBlock(hr, hi, vr, vi, gr, gi, p, q);
                }
            }
        }

        double[] ev = new double[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) { ev[i] = hr[i][i]; idx[i] = i; }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (ev[j] < ev[i]) {
                    double te = ev[i]; ev[i] = ev[j]; ev[j] = te;
                    int ti = idx[i]; idx[i] = idx[j]; idx[j] = ti;
                }
        double[][] er = new double[n][n];
        double[][] ei = new double[n][n];
        for (int col = 0; col < n; col++)
            for (int row = 0; row < n; row++) {
                er[row][col] = vr[row][idx[col]];
                ei[row][col] = vi[row][idx[col]];
            }
        return new HermitianJacobi(ev, er, ei);
    }

    /** H <- G^H H G ; V <- V G, restricted to the p/q block. */
    private static void applyBlock(double[][] hr, double[][] hi, double[][] vr, double[][] vi,
                                   double[][] gr, double[][] gi, int p, int q) {
        int n = hr.length;
        int[] id = {p, q};

        // columns of V*G
        double[][] newVr = new double[n][2];
        double[][] newVi = new double[n][2];
        for (int b = 0; b < 2; b++) {
            for (int k = 0; k < n; k++) {
                for (int a2 = 0; a2 < 2; a2++) {
                    int ka = id[a2];
                    newVr[k][b] += vr[k][ka] * gr[a2][b] - vi[k][ka] * gi[a2][b];
                    newVi[k][b] += vr[k][ka] * gi[a2][b] + vi[k][ka] * gr[a2][b];
                }
            }
        }
        for (int b = 0; b < 2; b++)
            for (int k = 0; k < n; k++) {
                vr[k][id[b]] = newVr[k][b];
                vi[k][id[b]] = newVi[k][b];
            }

        // Hp = H G restricted
        double[][] hpr = new double[n][2];
        double[][] hpi = new double[n][2];
        for (int k = 0; k < n; k++) {
            for (int b = 0; b < 2; b++) {
                for (int a2 = 0; a2 < 2; a2++) {
                    int ka = id[a2];
                    hpr[k][b] += hr[k][ka] * gr[a2][b] - hi[k][ka] * gi[a2][b];
                    hpi[k][b] += hr[k][ka] * gi[a2][b] + hi[k][ka] * gr[a2][b];
                }
            }
        }
        // Hnew = G^H Hp: (G^H)[a][k] = conj(G[k][a])
        double[][] outr = new double[2][2];
        double[][] outi = new double[2][2];
        for (int a2 = 0; a2 < 2; a2++) {
            for (int b = 0; b < 2; b++) {
                for (int k2 = 0; k2 < 2; k2++) {
                    int kr = id[k2];
                    double ghr = gr[k2][a2], ghi = -gi[k2][a2];
                    outr[a2][b] += ghr * hpr[kr][b] - ghi * hpi[kr][b];
                    outi[a2][b] += ghr * hpi[kr][b] + ghi * hpr[kr][b];
                }
            }
        }
        for (int a2 = 0; a2 < 2; a2++)
            for (int b = 0; b < 2; b++) {
                hr[id[a2]][id[b]] = outr[a2][b];
                hi[id[a2]][id[b]] = outi[a2][b];
            }
    }

    private static double[][] copy(double[][] a) {
        double[][] b = new double[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    private static double[][] identity(int n) {
        double[][] a = new double[n][n];
        for (int i = 0; i < n.length; i++) a[i][i] = 1;
        return a;
    }
}
