package pol.domain;

import org.ejml.data.DMatrixRMaj;

/**
 * Jacobi eigenvalue algorithm for small real symmetric matrices (4x4).
 * Returns columns sorted by eigenvalue descending, matching
 * {@link PhysicalCheck#realEigenvalues}. Used where eigenvectors, not just
 * eigenvalues, are required (Cloude components).
 */
public final class JacobiEigen {
    private JacobiEigen() {
    }

    public static double[][] realSymmetricEigenvectors(DMatrixRMaj aIn) {
        int n = aIn.numRows;
        double[][] a = new double[n][n];
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            v[i][i] = 1.0;
            for (int j = 0; j < n; j++) {
                a[i][j] = 0.5 * (aIn.get(i, j) + aIn.get(j, i));
            }
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0.0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    off += a[p][q] * a[p][q];
                }
            }
            if (off < 1e-28) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    rotate(a, v, p, q, n);
                }
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(a[y][y], a[x][x]));
        double[][] sorted = new double[n][n];
        for (int col = 0; col < n; col++) {
            int src = order[col];
            for (int row = 0; row < n; row++) {
                sorted[row][col] = v[row][src];
            }
        }
        return sorted;
    }

    private static void rotate(double[][] a, double[][] v, int p, int q, int n) {
        double apq = a[p][q];
        if (Math.abs(apq) < 1e-300) {
            return;
        }
        double app = a[p][p];
        double aqq = a[q][q];
        double tau = (aqq - app) / (2.0 * apq);
        double t;
        if (tau >= 0) {
            t = 1.0 / (tau + Math.sqrt(1.0 + tau * tau));
        } else {
            t = -1.0 / (-tau + Math.sqrt(1.0 + tau * tau));
        }
        double c = 1.0 / Math.sqrt(1.0 + t * t);
        double s = t * c;
        a[p][p] = app - t * apq;
        a[q][q] = aqq + t * apq;
        a[p][q] = 0.0;
        a[q][p] = 0.0;
        for (int i = 0; i < n; i++) {
            if (i != p && i != q) {
                double aip = a[i][p];
                double aiq = a[i][q];
                a[i][p] = c * aip - s * aiq;
                a[p][i] = a[i][p];
                a[i][q] = s * aip + c * aiq;
                a[q][i] = a[i][q];
            }
            double vip = v[i][p];
            double viq = v[i][q];
            v[i][p] = c * vip - s * viq;
            v[i][q] = s * vip + c * viq;
        }
    }
}
