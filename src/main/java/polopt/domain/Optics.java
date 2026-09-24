package polopt.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Stokes / Mueller polarization optics.
 *
 * Stokes convention (order "S0S1S2S3"): S = [I, Q, U, V].
 * Rotation convention ("ccw-positive"): physical optical axis angle theta is
 * taken counter-clockwise positive looking into the beam; "cw-positive" flips
 * the sign. Both the order and the rotation convention enter fingerprints.
 */
public final class Optics {

    public static final List<String> DEFAULT_ORDER = List.of("S0", "S1", "S2", "S3");

    private Optics() {
    }

    /** Mueller matrix of an ideal linear polarizer whose axis is at theta (radians). */
    public static double[][] linearPolarizer(double theta) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        return new double[][] {
                { 0.5, 0.5 * c, 0.5 * s, 0.0 },
                { 0.5 * c, 0.5 * c * c, 0.5 * s * c, 0.0 },
                { 0.5 * s, 0.5 * c * s, 0.5 * s * s, 0.0 },
                { 0.0, 0.0, 0.0, 0.0 }
        };
    }

    /** Ideal linear retarder, retardance delta about fast axis theta (radians). */
    public static double[][] retarder(double theta, double delta) {
        double c2 = Math.cos(2 * theta);
        double s2 = Math.sin(2 * theta);
        double cd = Math.cos(delta);
        double sd = Math.sin(delta);
        double[][] m = new double[4][4];
        m[0][0] = 1.0;
        m[1][1] = c2 * c2 + s2 * s2 * cd;
        m[1][2] = c2 * s2 * (1 - cd);
        m[2][1] = c2 * s2 * (1 - cd);
        m[2][2] = s2 * s2 + c2 * c2 * cd;
        m[3][3] = cd;
        m[2][3] = s2 * sd;
        m[3][2] = -s2 * sd;
        m[1][3] = -c2 * sd;
        m[3][1] = c2 * sd;
        return m;
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        int n = a.length;
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double v = 0.0;
                for (int k = 0; k < n; k++) {
                    v += a[i][k] * b[k][j];
                }
                r[i][j] = v;
            }
        }
        return r;
    }

    public static double[] apply(double[][] m, double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < m.length; i++) {
            double s = 0.0;
            for (int j = 0; j < v.length; j++) {
                s += m[i][j] * v[j];
            }
            r[i] = s;
        }
        return r;
    }

    /**
     * Stokes vector produced by a hardware state encoding.
     * Supported element kinds:
     *   {"kind":"polarizer","axisDeg":..}
     *   {"kind":"qwp","fastAxisDeg":..,"inputStokes":[..]}
     * Rotation sign: "ccw-positive" (default) or "cw-positive" (negates angles).
     */
    public static double[] stateStokes(polopt.domain.StateEncoding state, String rotationConvention) {
        double sign = "cw-positive".equals(rotationConvention) ? -1.0 : 1.0;
        double[] s = state.inputStokes() == null
                ? new double[] { 1.0, 1.0, 0.0, 0.0 }
                : state.inputStokes().clone();
        for (polopt.domain.StateEncoding.Element el : state.elements()) {
            double theta = sign * Math.toRadians(el.axisDeg());
            double[][] m = switch (el.kind()) {
                case "polarizer" -> linearPolarizer(theta);
                case "qwp" -> retarder(theta, Math.PI / 2.0);
                case "hwp" -> retarder(theta, Math.PI);
                default -> throw new IllegalArgumentException("unknown element kind: " + el.kind());
            };
            s = apply(m, s);
        }
        return s;
    }

    /** Reorder a Stokes vector from canonical [S0,S1,S2,S3] to the declared order. */
    public static double[] reorder(double[] canonical, List<String> order) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            int idx = DEFAULT_ORDER.indexOf(order.get(i));
            out[i] = canonical[idx];
        }
        return out;
    }

    public static List<double[]> canonicalBasisStates() {
        List<double[]> list = new ArrayList<>();
        list.add(new double[] { 1, 1, 0, 0 }); // H
        list.add(new double[] { 1, -1, 0, 0 }); // V
        list.add(new double[] { 1, 0, 1, 0 }); // P(+45)
        list.add(new double[] { 1, 0, 0, 1 }); // R
        list.add(new double[] { 1, -1, 0, 0 }); // L handled separately if needed
        return list;
    }
}
