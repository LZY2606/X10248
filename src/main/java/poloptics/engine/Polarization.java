package poloptics.engine;

/**
 * Polarization state encodings.
 *
 * A measurement configuration consists of a generator (polarization state
 * preparation, PSG) Stokes vector G and an analyzer (polarization state
 * analyzer, PSA) Stokes vector A. For a sample Mueller matrix M the detected
 * intensity is
 *
 *   I = A^T M G  (times scalar gain, plus dark level)
 *
 * Using row-major stacking m = vec(M^T) = [M00,M01,...,M33] gives the design
 * row W = kron(G^T, A^T).
 */
public final class Polarization {
    private Polarization() {}

    /** Linear retarder Mueller matrix; theta in radians, delta retardance in radians. */
    public static double[][] retarder(double theta, double delta, boolean ccwPositive) {
        double s = ccwPositive ? 1 : -1;
        double t = s * 2 * theta;
        double c = Math.cos(t), sn = Math.sin(t);
        double cd = Math.cos(delta), sd = Math.sin(delta);
        // Rotation R(2theta) applied to a retarder with axes x,y
        double[][] r = {
            {1, 0, 0, 0},
            {0, c, sn, 0},
            {0, -sn, c, 0},
            {0, 0, 0, 1}
        };
        double[][] rm = {
            {1, 0, 0, 0},
            {0, c, -sn, 0},
            {0, sn, c, 0},
            {0, 0, 0, 1}
        };
        double[][] d = {
            {1, 0, 0, 0},
            {0, 1, 0, 0},
            {0, 0, cd, sd},
            {0, 0, -sd, cd}
        };
        return Mat.mul(Mat.mul(rm, d), r);
    }

    /** Ideal linear polarizer at theta (rad). */
    public static double[][] linearPolarizer(double theta, boolean ccwPositive) {
        double s = ccwPositive ? 1 : -1;
        double t = s * 2 * theta;
        double c = Math.cos(t), sn = Math.sin(t);
        double[][] r = {
            {1, 0, 0, 0},
            {0, c, sn, 0},
            {0, -sn, c, 0},
            {0, 0, 0, 1}
        };
        double[][] rm = {
            {1, 0, 0, 0},
            {0, c, -sn, 0},
            {0, sn, c, 0},
            {0, 0, 0, 1}
        };
        double[][] p = {
            {0.5, 0.5, 0, 0},
            {0.5, 0.5, 0, 0},
            {0, 0, 0, 0},
            {0, 0, 0, 0}
        };
        return Mat.mul(Mat.mul(rm, p), r);
    }

    /** First row of a polarizer Mueller matrix = analyzer Stokes row (A^T). */
    public static double[] analyzerStokes(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return new double[]{1, c, s, 0};
    }

    /** Output Stokes vector (up to scalar) of a linear polarizer = its first column. */
    public static double[] generatorStokes(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return new double[]{1, c, s, 0};
    }

    /** Circular states: handednessSign +1 = RCP (S3=+1). */
    public static double[] circularStokes(int handednessSign) {
        return new double[]{1, 0, 0, handednessSign};
    }

    /** kronecker(g^T, a^T): 16-element design row matching vec(M^T) row-major. */
    public static double[] designRow(double[] g, double[] a) {
        double[] w = new double[16];
        for (int mi = 0; mi < 4; mi++)
            for (int mj = 0; mj < 4; mj++)
                w[mi * 4 + mj] = g[mj] * a[mi];
        return w;
    }

    public static double[][] fromVec(double[] m) {
        double[][] mm = new double[4][4];
        for (int i = 0; i < 16; i++) mm[i / 4][i % 4] = m[i];
        return mm;
    }

    public static double[] toVec(double[][] mm) {
        double[] m = new double[16];
        for (int i = 0; i < 4; i++) System.arraycopy(mm[i], 0, m, i * 4, 4);
        return m;
    }
}
