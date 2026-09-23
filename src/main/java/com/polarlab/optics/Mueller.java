package com.polarlab.optics;

import com.polarlab.math.Mat;

/** Mueller matrices and canonical optical elements, all on a declared Stokes basis. */
public final class Mueller {

    public static final Mat IDENTITY = Mat.identity(4);

    /** Linear polarizer at theta (radians), ideal. */
    public static Mat linearPolarizer(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return Mat.of(new double[][]{
                {1, c, s, 0},
                {c, c * c, c * s, 0},
                {s, c * s, s * s, 0},
                {0, 0, 0, 0}
        }).mul(0.5);
    }

    /** Linear retarder with fast axis theta and retardance delta (radians). */
    public static Mat linearRetarder(double theta, double delta) {
        double c2 = Math.cos(2 * theta), s2 = Math.sin(2 * theta);
        double cd = Math.cos(delta), sd = Math.sin(delta);
        return Mat.of(new double[][]{
                {1, 0, 0, 0},
                {0, c2 * c2 + s2 * s2 * cd, c2 * s2 * (1 - cd), s2 * sd},
                {0, c2 * s2 * (1 - cd), s2 * s2 + c2 * c2 * cd, -c2 * sd},
                {0, -s2 * sd, c2 * sd, cd}
        });
    }

    /** Pure rotator of angle theta under the configured sign convention. */
    public static Mat rotator(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        return Mat.of(new double[][]{
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}
        });
    }

    /** Perfect depolarizer. */
    public static Mat idealDepolarizer() {
        return Mat.of(new double[][]{
                {1, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0}
        });
    }

    /**
     * Diagonal depolarizing Mueller matrix with depolarization factors (a,b,c):
     * diag(1, a, b, c). Used to build physically admissible depolarizing samples.
     */
    public static Mat depolarizing(double a, double b, double c) {
        return Mat.of(new double[][]{
                {1, 0, 0, 0},
                {0, a, 0, 0},
                {0, 0, b, 0},
                {0, 0, 0, c}
        });
    }
}
