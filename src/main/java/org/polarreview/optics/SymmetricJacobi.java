package org.polarreview.optics;

/** Jacobi eigendecomposition for 3x3 real symmetric matrices. */
final class SymmetricJacobi {
    static double[] eigenvalues(double[][] in) {
        return eigen(in)[0];
    }

    static double[][] eigenvectors(double[][] in) {
        return eigen(in)[1];
    }

    private static Object[] eigen(double[][] in) {
        int n = 3;
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) System.arraycopy(in[i], 0, a[i], 0, 3);
        double[][] v = Mat3.I();
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int p = 0; p < n; p++)
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            if (off < 1e-30) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-18) continue;
                    double tau = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = (tau >= 0) ? 1 / (tau + Math.sqrt(1 + tau * tau))
                            : -1 / (-tau + Math.sqrt(1 + tau * tau));
                    double c = 1 / Math.sqrt(1 + t * t);
                    double s = t * c;
                    double app = a[p][p], apq = a[p][q], aqq = a[q][q];
                    a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
                    a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
                    a[p][q] = 0;
                    a[q][p] = 0;
                    for (int k = 0; k < n; k++) {
                        if (k == p || k == q) continue;
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[p][k] = a[k][p];
                        a[k][q] = s * akp + c * akq;
                        a[q][k] = a[k][q];
                    }
                    for (int k = 0; k < n; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        double[] ev = {a[0][0], a[1][1], a[2][2]};
        // sort ascending
        for (int i = 0; i < 3; i++)
            for (int j = i + 1; j < 3; j++)
                if (ev[j] < ev[i]) {
                    double t = ev[i]; ev[i] = ev[j]; ev[j] = t;
                    for (int k = 0; k < 3; k++) {
                        double x = v[k][i]; v[k][i] = v[k][j]; v[k][j] = x;
                    }
                }
        return new Object[] {ev, v};
    }
}
