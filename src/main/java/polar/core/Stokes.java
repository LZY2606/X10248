package polar.core;

/**
 * Stokes/Mueller conventions. The Stokes component order and the rotation
 * sign convention are explicit inputs: they change derived results and are
 * therefore part of every derivation fingerprint.
 */
public final class Stokes {
    private Stokes() {}

    /** Supported Stokes component orderings. */
    public enum Order {
        IQUV, IQVU
    }

    /** Rotation sign convention for coordinate rotations. */
    public enum RotationConvention {
        /** R(+theta) rotates the basis counter-clockwise (optics textbook). */
        RIGHT_HANDED,
        /** R(+theta) rotates the basis clockwise (engineering convention). */
        LEFT_HANDED
    }

    public record Conventions(Order order, RotationConvention rotation) {
        public static Conventions standard() {
            return new Conventions(Order.IQUV, RotationConvention.RIGHT_HANDED);
        }
    }

    /**
     * Stokes vector of fully polarized light at linear azimuth {@code thetaDeg}
     * under the given conventions. Basis component order follows
     * {@code conv.order()}; handedness flips the sign of U.
     */
    public static double[] linearPolarization(double thetaDeg, Conventions conv) {
        double t = Math.toRadians(thetaDeg);
        double c = Math.cos(2 * t);
        double s = Math.sin(2 * t);
        if (conv.rotation() == RotationConvention.LEFT_HANDED) s = -s;
        return switch (conv.order()) {
            case IQUV -> new double[]{1, c, s, 0};
            case IQVU -> new double[]{1, c, 0, s};
        };
    }

    /** Mueller rotation matrix R(theta) under the given conventions. */
    public static double[][] rotation(double thetaDeg, Conventions conv) {
        double t = Math.toRadians(thetaDeg);
        double c = Math.cos(2 * t);
        double s = Math.sin(2 * t);
        if (conv.rotation() == RotationConvention.LEFT_HANDED) s = -s;
        return switch (conv.order()) {
            case IQUV -> new double[][]{
                    {1, 0, 0, 0},
                    {0, c, -s, 0},
                    {0, s, c, 0},
                    {0, 0, 0, 1}};
            case IQVU -> new double[][]{
                    {1, 0, 0, 0},
                    {0, c, 0, -s},
                    {0, 0, 1, 0},
                    {0, s, 0, c}};
        };
    }

    /** Ideal linear polarizer Mueller matrix at azimuth thetaDeg. */
    public static double[][] linearPolarizer(double thetaDeg, Conventions conv) {
        double t = Math.toRadians(thetaDeg);
        double c2 = Math.cos(2 * t), s2 = Math.sin(2 * t);
        if (conv.rotation() == RotationConvention.LEFT_HANDED) s2 = -s2;
        double[][] m;
        switch (conv.order()) {
            case IQUV -> m = new double[][]{
                    {1, c2, s2, 0},
                    {c2, c2 * c2, c2 * s2, 0},
                    {s2, c2 * s2, s2 * s2, 0},
                    {0, 0, 0, 0}};
            case IQVU -> m = new double[][]{
                    {1, c2, 0, s2},
                    {c2, c2 * c2, 0, c2 * s2},
                    {0, 0, 0, 0},
                    {s2, c2 * s2, 0, s2 * s2}};
            default -> throw new IllegalStateException();
        }
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) m[i][j] *= 0.5;
        return m;
    }
}
