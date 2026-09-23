package org.polarreview.optics;

/** Small 3x3 real linear algebra (polar decomposition used by Lu-Chipman). */
final class Mat3 {
    final double[][] a;
    Mat3(double[][] a) { this.a = a; }

    static double[][] I() {
        return new double[][] {{1,0,0},{0,1,0},{0,0,1}};
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int k = 0; k < 3; k++)
                for (int j = 0; j < 3; j++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    static double[][] t(double[][] a) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[j][i] = a[i][j];
        return r;
    }

    static double det(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
             - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
             + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** Symmetric positive-semidefinite square root via Jacobi eigendecomposition. */
    static double[][] sqrtSym(double[][] a) {
        double[][] e = SymmetricJacobi.eigenvectors(a);
        double[] ev = SymmetricJacobi.eigenvalues(a);
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double root = Math.sqrt(Math.max(0, ev[i]));
            for (int p = 0; p < 3; p++)
                for (int q = 0; q < 3; q++)
                    r[p][q] += e[p][i] * root * e[q][i];
        }
        return r;
    }

    static double[][] invSym(double[][] a) {
        double[][] e = SymmetricJacobi.eigenvectors(a);
        double[] ev = SymmetricJacobi.eigenvalues(a);
        double floor = 1e-14;
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double inv = 1.0 / Math.max(ev[i], floor);
            for (int p = 0; p < 3; p++)
                for (int q = 0; q < 3; q++)
                    r[p][q] += e[p][i] * inv * e[q][i];
        }
        return r;
    }
}
