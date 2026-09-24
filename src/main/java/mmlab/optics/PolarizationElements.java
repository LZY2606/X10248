package mmlab.optics;

/**
 * Canonical (I,Q,U,V) Stokes vectors and ideal element Mueller matrices used to
 * build generator/analyzer state encodings. Angles follow the supplied
 * {@link RotationConvention}; the convention choice becomes part of the
 * fingerprint.
 */
public final class PolarizationElements {

    private PolarizationElements() {
    }

    public static double[] stokesLinearPolarizer(double theta) {
        double c2 = Math.cos(2.0 * theta);
        double s2 = Math.sin(2.0 * theta);
        return new double[]{1.0, c2, s2, 0.0};
    }

    /** Linear polarizer followed by a retarder (linear retardance delta, axis theta). */
    public static double[] stokesGeneratorPsg(double theta, double delta) {
        double c2 = Math.cos(2.0 * theta);
        double s2 = Math.sin(2.0 * theta);
        double cd = Math.cos(delta);
        double sd = Math.sin(delta);
        double i = 1.0;
        double q = c2 * c2 + s2 * s2 * cd;
        double u = s2 * c2 * (1.0 - cd);
        double v = s2 * sd;
        return new double[]{i, q, u, v};
    }

    public static double[][] linearPolarizerMueller(double theta) {
        double c2 = Math.cos(2.0 * theta);
        double s2 = Math.sin(2.0 * theta);
        double[][] m = new double[4][4];
        double[][] base = {
                {1, c2, s2, 0},
                {c2, c2 * c2, c2 * s2, 0},
                {s2, c2 * s2, s2 * s2, 0},
                {0, 0, 0, 0}
        };
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                m[i][j] = 0.5 * base[i][j];
            }
        }
        return m;
    }

    public static double[][] linearRetarderMueller(double theta, double delta) {
        double c2 = Math.cos(2.0 * theta);
        double s2 = Math.sin(2.0 * theta);
        double cd = Math.cos(delta);
        double sd = Math.sin(delta);
        double[][] m = {
                {1, 0, 0, 0},
                {0, c2 * c2 + s2 * s2 * cd, c2 * s2 * (1 - cd), s2 * sd},
                {0, c2 * s2 * (1 - cd), s2 * s2 + c2 * c2 * cd, -c2 * sd},
                {0, -s2 * sd, c2 * sd, cd}
        };
        return m;
    }
}
