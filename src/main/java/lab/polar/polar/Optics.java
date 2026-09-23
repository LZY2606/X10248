package lab.polar.polar;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mueller matrices of ideal reference optics and Stokes-state parsing. */
public final class Optics {
    private Optics() {}

    public static double[][] identity() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) m[i][i] = 1;
        return m;
    }

    /** Ideal linear polarizer with transmission axis theta (radians, resolved sign). */
    public static double[][] linearPolarizer(double theta) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        double[][] m = new double[4][4];
        double[] first = {1, c, s, 0};
        for (int r = 0; r < 4; r++)
            for (int col = 0; col < 4; col++) m[r][col] = 0.5 * first[r] * first[col];
        return m;
    }

    /**
     * Ideal linear retarder. retardance delta in radians; theta is the FAST axis angle.
     * The cross-term sign follows the stated rotation convention.
     */
    public static double[][] linearRetarder(double delta, double theta) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        double cd = Math.cos(delta);
        double sd = Math.sin(delta);
        double[][] m = {
                {1, 0, 0, 0},
                {0, c * c + s * s * cd, c * s * (1 - cd), s * sd},
                {0, c * s * (1 - cd), s * s + c * c * cd, -c * sd},
                {0, -s * sd, c * sd, cd}
        };
        return m;
    }

    /** Quarter-wave linear retarder with fast axis theta. */
    public static double[][] quarterWavePlate(double theta) {
        return linearRetarder(Math.PI / 2, theta);
    }

    /** Half-wave linear retarder with fast axis theta. */
    public static double[][] halfWavePlate(double theta) {
        return linearRetarder(Math.PI, theta);
    }

    /** Rotate Mueller matrix by a resolved physical angle. */
    public static double[][] rotate(double theta, double[][] m) {
        double c = Math.cos(2 * theta);
        double s = Math.sin(2 * theta);
        double[][] r = {
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}
        };
        return multiply(multiply(r, m), transpose(r));
    }

    /**
     * Parse an optical state encoding into a normalized Stokes vector.
     * Supported kinds:
     *  H,V,P,M,R,L              - named pure states
     *  ELLIPSE:{psi_deg}:{chi_deg} - angles in degrees
     *  STOKES:[S0,S1,S2,S3]     - values already in the stated order
     * Angles honor the rotation sign convention; components honor the Stokes order.
     */
    public static ParsedState parseState(String encoding, Conventions.StokesOrder order,
                                         Conventions.RotationConvention rot) {
        String code = encoding.trim();
        double[] canonical;
        String kind;
        switch (code) {
            case "H" -> { canonical = norm(1, 1, 0, 0); kind = "NAMED"; }
            case "V" -> { canonical = norm(1, -1, 0, 0); kind = "NAMED"; }
            case "P" -> { canonical = norm(1, 0, 1, 0); kind = "NAMED"; }
            case "M" -> { canonical = norm(1, 0, -1, 0); kind = "NAMED"; }
            case "R" -> { canonical = norm(1, 0, 0, 1); kind = "NAMED"; }
            case "L" -> { canonical = norm(1, 0, 0, -1); kind = "NAMED"; }
            default -> {
                if (code.startsWith("ELLIPSE:")) {
                    String[] p = code.split(":");
                    double psi = rot.resolveAngle(Math.toRadians(Double.parseDouble(p[1])));
                    double chi = rot.resolveAngle(Math.toRadians(Double.parseDouble(p[2])));
                    double s0 = 1;
                    double s1 = Math.cos(2 * chi) * Math.cos(2 * psi);
                    double s2 = Math.cos(2 * chi) * Math.sin(2 * psi);
                    double s3 = Math.sin(2 * chi);
                    canonical = new double[] {s0, s1, s2, s3};
                    kind = "ELLIPSE";
                } else if (code.startsWith("STOKES:")) {
                    String[] p = code.substring(8).split(",");
                    double[] raw = new double[4];
                    for (int i = 0; i < 4; i++) raw[i] = Double.parseDouble(p[i].trim());
                    canonical = order.toCanonical(raw);
                    double n = Math.sqrt(canonical[1] * canonical[1] + canonical[2] * canonical[2]
                            + canonical[3] * canonical[3]);
                    if (canonical[0] == 0) canonical[0] = n;
                    kind = "STOKES";
                } else {
                    throw new IllegalArgumentException("Unknown optical state encoding: " + encoding);
                }
            }
        }
        double[] inOrder = order.fromCanonical(canonical);
        return new ParsedState(code, kind, canonical, inOrder);
    }

    public record ParsedState(String encoding, String kind, double[] canonical, double[] inOrder) {
        public Map<String, Object> descriptor(Conventions.StokesOrder order) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("encoding", encoding);
            m.put("kind", kind);
            m.put("stokesCanonical", canonical);
            m.put("stokesInOrder", inOrder);
            m.put("order", order.code);
            return m;
        }
    }

    private static double[] norm(double... s) {
        return s; // named states already S0=1
    }

    static double[][] multiply(double[][] a, double[][] b) {
        int n = a.length;
        double[][] r = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double acc = 0;
                for (int k = 0; k < n; k++) acc += a[i][k] * b[k][j];
                r[i][j] = acc;
            }
        return r;
    }

    static double[][] transpose(double[][] a) {
        double[][] t = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }
}
