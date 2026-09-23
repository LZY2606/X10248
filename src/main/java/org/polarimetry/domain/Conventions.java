package org.polarimetry.domain;

/**
 * Versioned interpretation rules. The convention id and rule version participate
 * in every derived fingerprint, so changing Stokes order or rotation sign never
 * silently replaces an old conclusion.
 */
public record Conventions(String ruleVersion, String stokesOrder, String rotationSign) {
    public static final String RULE_VERSION = "rules-1.0.0";
    public static final String ORDER_ISQU = "I,S,Q,U";
    public static final String ORDER_IQS = "I,Q,U,S"; // V/S swapped columns
    public static final String ROT_OPTICS = "optics-positive";   // R(theta) above
    public static final String ROT_REVERSED = "engineering-reversed"; // -theta

    public static Conventions standard() {
        return new Conventions(RULE_VERSION, ORDER_ISQU, ROT_OPTICS);
    }

    /** index permutation mapping declared channel name -> working canonical slot 0..3 */
    public int[] channelPermutation() {
        return switch (stokesOrder) {
            case ORDER_ISQU -> new int[]{0, 1, 2, 3};
            case ORDER_IQS -> new int[]{0, 2, 1, 3};
            default -> throw new IllegalArgumentException("unknown stokes order: " + stokesOrder);
        };
    }

    public double signTheta() {
        return switch (rotationSign) {
            case ROT_OPTICS -> 1.0;
            case ROT_REVERSED -> -1.0;
            default -> throw new IllegalArgumentException("unknown rotation sign: " + rotationSign);
        };
    }

    public String fingerprintTag() {
        return ruleVersion + "|" + stokesOrder + "|" + rotationSign;
    }

    // ---- canonical Mueller matrices (I,S,Q,U working basis) ----

    /** Ideal linear polarizer at angle theta (radians). */
    public static Linear linearPolarizer(double theta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        double[][] p = {
                {1, 0, c, s},
                {0, 0, 0, 0},
                {c, 0, c * c, c * s},
                {s, 0, c * s, s * s}
        };
        return Linear.of(p).times(0.5);
    }

    /** Ideal linear retarder at angle theta with retardance delta (radians). */
    public static Linear linearRetarder(double theta, double delta) {
        double c = Math.cos(2 * theta), s = Math.sin(2 * theta);
        double cd = Math.cos(delta), sd = Math.sin(delta);
        double[][] m = {
                {1, 0, 0, 0},
                {0, cd + c * c * (1 - cd), c * s * (1 - cd), s * sd},
                {0, c * s * (1 - cd), cd + s * s * (1 - cd), -c * sd},
                {0, -s * sd, c * sd, cd}
        };
        return Linear.of(m);
    }

    /** Linear retarder applying the configured rotation-sign convention. */
    public Linear retarder(double theta, double delta) {
        return linearRetarder(signTheta() * theta, delta);
    }

    /** Stokes vector of a perfectly polarized linear state at angle theta: M(theta)*[1,0,0,0]. */
    public double[] linearStokes(double theta) {
        Linear p = linearPolarizer(signTheta() * theta);
        double[] s = new double[4];
        for (int i = 0; i < 4; i++) {
            s[i] = p.get(i, 0); // first column acting on (1,0,0,0) gives [1,0,cos2t,sin2t]/2
        }
        return s;
    }

    /**
     * Map a 4-vector from the declared Stokes order into canonical (I,S,Q,U).
     */
    public double[] toCanonical(double[] declared) {
        int[] perm = channelPermutation();
        double[] out = new double[4];
        for (int slot = 0; slot < 4; slot++) out[slot] = declared[perm[slot]];
        return out;
    }
}
