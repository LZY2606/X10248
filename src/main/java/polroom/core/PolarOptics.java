package polroom.core;

/**
 * Stokes vectors and ideal optical states in the canonical I,Q,U,V convention.
 * Angles are in radians in the convention's angle basis; {@link Conventions#rotationSign}
 * determines how a configured angle maps to the physical rotation angle.
 */
public final class PolarOptics {
    private PolarOptics() {}

    /** Unpolarized unit intensity. */
    public static double[] unpolarized() { return new double[]{1, 0, 0, 0}; }

    /** Linear polarizer transmission axis at angle theta. */
    public static double[][] linearPolarizer(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return new double[][]{
                {0.5, 0.5 * c, 0.5 * s, 0},
                {0.5 * c, 0.5 * c * c, 0.5 * s * c, 0},
                {0.5 * s, 0.5 * s * c, 0.5 * s * s, 0},
                {0, 0, 0, 0}
        };
    }

    /** Linear retarder with fast axis theta and retardance delta. */
    public static double[][] linearRetarder(double theta, double delta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        double cd = Math.cos(delta), sd = Math.sin(delta);
        double[][] M = new double[4][4];
        M[0][0] = 1;
        M[1][1] = c * c + s * s * cd;
        M[1][2] = s * c * (1 - cd);
        M[1][3] = s * sd;
        M[2][1] = s * c * (1 - cd);
        M[2][2] = s * s + c * c * cd;
        M[2][3] = -c * sd;
        M[3][1] = -s * sd;
        M[3][2] = c * sd;
        M[3][3] = cd;
        return M;
    }

    /** Rotation of Stokes coordinates by angle theta. */
    public static double[][] rotation(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return new double[][]{
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}
        };
    }

    /** Multiply 4x4 matrix by 4-vector. */
    public static double[] mv(double[][] A, double[] v) {
        double[] r = new double[4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) r[i] += A[i][j] * v[j];
        return r;
    }

    public static double[][] mm(double[][] A, double[][] B) {
        double[][] R = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) sum += A[i][k] * B[k][j];
                R[i][j] = sum;
            }
        return R;
    }

    /**
     * Physical rotation angle from a configured instrument angle and the convention.
     * sign = +1 means the configured angle is used as-is; -1 mirrors it.
     */
    public static double physicalAngle(double configuredAngleDeg, int rotationSign) {
        return Math.toRadians(configuredAngleDeg) * rotationSign;
    }

    /**
     * Generator (PSG) state produced by a linear polarizer at angle p followed by a
     * linear retarder with fast axis r and retardance delta.
     */
    public static double[] generatorState(double pDeg, double rDeg, double deltaRad, int rotationSign) {
        double p = physicalAngle(pDeg, rotationSign);
        double r = physicalAngle(rDeg, rotationSign);
        double[] s = mv(linearPolarizer(p), unpolarized());
        return mv(linearRetarder(r, deltaRad), s);
    }

    /** Analyzer (PSA) analyzing Stokes vector for a polarizer at angle a followed by retarder. */
    public static double[] analyzerVector(double aDeg, double rDeg, double deltaRad, int rotationSign) {
        double a = physicalAngle(aDeg, rotationSign);
        double r = physicalAngle(rDeg, rotationSign);
        // intensity = (1/2) [1, cos2t cos2r+sin2t sin2r cosd, sin2t cos2r-cos2t sin2r cosd, -sin2r sind]
        // evaluated for analyzer polarizer t: first row of P(t)*R(r,d)
        double[][] M = mm(linearPolarizer(a), linearRetarder(r, deltaRad));
        return new double[]{M[0][0] * 2, M[0][1] * 2, M[0][2] * 2, M[0][3] * 2};
    }

    /**
     * One row of the measurement design matrix for generator Stokes g and analyzer
     * vector a (first element is the intensity transmittance coefficient).
     * Row layout: [a0*g0, a0*g1, a0*g2, a0*g3, a1*g0, ...] over Mueller row index then column.
     */
    public static double[] designRow(double[] g, double[] a) {
        double[] row = new double[16];
        int k = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) row[k++] = a[i] * g[j];
        return row;
    }
}
