package polwork.domain;

import org.apache.commons.math3.linear.RealMatrix;

/**
 * Ideal polarization components and optical-state synthesis.
 *
 * Angles are recorded by the lab in radians in the raw layer. The derived
 * layer applies the convention rotation sign here; the raw representation is
 * never rewritten.
 */
public final class PolarOp {

    private PolarOp() {
    }

    /** Linear polarizer whose transmission axis is at physical angle theta (radians). */
    public static double[][] linearPolarizer(double theta) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        return new double[][] {
                {1, c, s, 0},
                {c, c * c, c * s, 0},
                {s, c * s, s * s, 0},
                {0, 0, 0, 0}
        };
    }

    /** Quarter-wave retarder with fast axis at theta, phase sign delta = +/- pi/2. */
    public static double[][] quarterWavePlate(double theta, int deltaSign) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        double d = deltaSign * Math.PI / 2;
        double cs = Math.cos(d);
        double sn = Math.sin(d);
        double[][] m = new double[4][4];
        m[0][0] = 1;
        m[1][1] = c * c + s * s * cs;
        m[1][2] = c * s * (1 - cs);
        m[1][3] = s * sn;
        m[2][1] = c * s * (1 - cs);
        m[2][2] = s * s + c * c * cs;
        m[2][3] = -c * sn;
        m[3][1] = -s * sn;
        m[3][2] = c * sn;
        m[3][3] = cs;
        return m;
    }

    /** Stokes vector emitted by a PSG described by component rows, fed by unpolarized light (1,0,0,0). */
    public static double[] synthesizeGenerator(Convention convention, String kind, double angleDegrees, double retardanceDegrees) {
        double signed = Math.toRadians(angleDegrees) * convention.angleSign();
        RealMatrix product;
        if ("QWP+POL".equals(kind)) {
            RealMatrix qwp = DMatrix.m(quarterWavePlate(signed, 1));
            RealMatrix pol = DMatrix.m(linearPolarizer(0));
            product = DMatrix.mul(qwp, pol);
        } else if ("POL+QWP".equals(kind)) {
            RealMatrix pol = DMatrix.m(linearPolarizer(signed));
            product = pol;
        } else if ("POL".equals(kind)) {
            product = DMatrix.m(linearPolarizer(signed));
        } else if ("HWP".equals(kind)) {
            product = DMatrix.m(quarterWavePlate(signed, 2));
        } else if ("QWP".equals(kind)) {
            product = DMatrix.m(quarterWavePlate(signed, 1));
        } else {
            throw new IllegalArgumentException("unknown generator kind: " + kind);
        }
        double[] stokes = product.operate(new double[] {1, 0, 0, 0});
        return convention.toConventionOrder(stokes);
    }

    /** First row of the analyzer Mueller matrix: the weight vector w with I = w dot S. */
    public static double[] synthesizeAnalyzer(Convention convention, String kind, double angleDegrees, double retardanceDegrees) {
        double signed = Math.toRadians(angleDegrees) * convention.angleSign();
        double[][] component;
        if ("POL".equals(kind)) {
            component = linearPolarizer(signed);
        } else if ("QWP+POL".equals(kind)) {
            RealMatrix pol = DMatrix.m(linearPolarizer(0));
            RealMatrix qwp = DMatrix.m(quarterWavePlate(signed, 1));
            component = DMatrix.mul(pol, qwp).getData();
        } else {
            throw new IllegalArgumentException("unknown analyzer kind: " + kind);
        }
        double[] iquvRow = component[0].clone();
        return convention.toConventionOrder(iquvRow);
    }

    public static double[][] kron(double[][] a, double[][] b) {
        int ar = a.length;
        int ac = a[0].length;
        int br = b.length;
        int bc = b[0].length;
        double[][] out = new double[ar * br][ac * bc];
        for (int i = 0; i < ar; i++) {
            for (int j = 0; j < ac; j++) {
                for (int k = 0; k < br; k++) {
                    for (int l = 0; l < bc; l++) {
                        out[i * br + k][j * bc + l] = a[i][j] * b[k][l];
                    }
                }
            }
        }
        return out;
    }
}
