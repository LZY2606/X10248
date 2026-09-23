package polroom.domain;

import org.apache.commons.math3.linear.RealMatrix;

/** Mueller matrices of basic polarization components in canonical SHV order. */
public final class Polarization {

    public static RealMatrix linearRetarder(double deltaRad, double thetaRad,
                                            Conventions.RotationConvention convention) {
        double t = convention.sign() * thetaRad;
        double c = Math.cos(2 * t);
        double s = Math.sin(2 * t);
        RealMatrix rot = Mat.m(new double[][]{
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}
        });
        double cd = Math.cos(deltaRad);
        double sd = Math.sin(deltaRad);
        RealMatrix fast = Mat.m(new double[][]{
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, cd, sd},
                {0, 0, -sd, cd}
        });
        RealMatrix rotInv = rot.transpose();
        return rotInv.multiply(fast).multiply(rot);
    }

    public static RealMatrix linearDiattenuator(double tMax, double tMin, double thetaRad,
                                                Conventions.RotationConvention convention) {
        double t = convention.sign() * thetaRad;
        double a = (tMax + tMin) / 2.0;
        double d = (tMax - tMin) / (tMax + tMin);
        double c2 = Math.cos(2 * t);
        double s2 = Math.sin(2 * t);
        double sp = Math.sqrt(1 - d * d);
        double c2sq = c2 * c2;
        double s2sq = s2 * s2;
        double q33 = c2sq + sp * s2sq;
        double q44 = s2sq + sp * c2sq;
        double q34 = c2 * s2 * (1 - sp);
        return Mat.m(new double[][]{
                {a, a * d * c2, a * d * s2, 0},
                {a * d * c2, a * q33, a * q34, 0},
                {a * d * s2, a * q34, a * q44, 0},
                {0, 0, 0, a * sp}
        });
    }

    public static RealMatrix idealLinearPolarizer(double thetaRad,
                                                  Conventions.RotationConvention convention) {
        double t = convention.sign() * thetaRad;
        double c = Math.cos(t);
        double s = Math.sin(t);
        double c2 = c * c;
        double s2 = s * s;
        double cs = c * s;
        return Mat.m(new double[][]{
                {0.5, 0.5 * (c2 - s2), cs, 0},
                {0.5 * (c2 - s2), 0.5 * (c2 - s2) * (c2 - s2), cs * (c2 - s2), 0},
                {cs, cs * (c2 - s2), 2 * cs * cs, 0},
                {0, 0, 0, 0}
        });
    }

    /** Stokes vector of an ideal linear polarizer state at angle theta. */
    public static double[] linearStokes(double thetaRad, Conventions.RotationConvention convention) {
        double t = convention.sign() * thetaRad;
        return new double[]{1, Math.cos(2 * t), Math.sin(2 * t), 0};
    }

    /** General elliptical state from polar/azimuth angles of the polarization ellipse. */
    public static double[] ellipticalStokes(double azimuthRad, double ellipticityRad,
                                            Conventions.RotationConvention convention) {
        double t = convention.sign() * azimuthRad;
        double c = Math.cos(2 * ellipticityRad);
        return new double[]{
                1,
                c * Math.cos(2 * t),
                c * Math.sin(2 * t),
                Math.sin(2 * ellipticityRad)
        };
    }

    private Polarization() {
    }
}
