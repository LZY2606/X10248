package pol.domain;

import org.ejml.data.DMatrixRMaj;

/** Constructors for elementary Mueller blocks (diattenuator, retarder). */
public final class MuellerBlocks {
    private MuellerBlocks() {
    }

    public static DMatrixRMaj diattenuator(double[] d) {
        double dMag = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        DMatrixRMaj md = Mtx.identity(4);
        if (dMag < 1e-15) {
            return md;
        }
        double delta = Math.sqrt(Math.max(0.0, 1.0 - dMag * dMag));
        double eta = delta / (1.0 + delta);
        md.set(1, 0, d[0]);
        md.set(2, 0, d[1]);
        md.set(3, 0, d[2]);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double di = d[i];
                double dj = d[j];
                md.set(i + 1, j + 1, eta * (i == j ? 1.0 : 0.0) + di * dj / (dMag * dMag) * (1.0 - eta));
            }
        }
        return md;
    }

    /**
     * Retarder block for fast axis unit vector r and retardance delta.
     * The sign convention (right-handed propagation axis, retarder matrix
     * R = [[1,0],[0, I sin delta + (1-cos delta) rr' + delta x cross])]
     * must match the rotating-element sign used during state encoding; both
     * enter the analysis fingerprint.
     */
    public static DMatrixRMaj retarder(double[] axis, double retardance) {
        double[] r = normalize(axis);
        double cos = Math.cos(retardance);
        double sin = Math.sin(retardance);
        DMatrixRMaj mr = Mtx.identity(4);
        double[][] rr = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rr[i][j] = r[i] * r[j];
            }
        }
        // cross-product matrix [r]_x acting on a vector v
        double[][] cx = {
                {0.0, -r[2], r[1]},
                {r[2], 0.0, -r[0]},
                {-r[1], r[0], 0.0}
        };
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double value = cos * (i == j ? 1.0 : 0.0)
                        + (1.0 - cos) * rr[i][j]
                        + sin * cx[i][j];
                mr.set(i + 1, j + 1, value);
            }
        }
        return mr;
    }

    private static double[] normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-15) {
            return new double[] {0.0, 0.0, 0.0};
        }
        return new double[] {v[0] / n, v[1] / n, v[2] / n};
    }
}
