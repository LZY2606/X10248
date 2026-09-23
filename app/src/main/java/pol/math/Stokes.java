package pol.math;

/** Stokes vectors expressed in the canonical SIQUV order (I,Q,U,V). */
public final class Stokes {
    private Stokes() {}

    /** Linear polarizer at angle theta (degrees, signed under convention). SIQUV, normalized intensity. */
    public static double[] linear(double thetaDeg) {
        double t = Math.toRadians(2 * thetaDeg);
        return new double[]{1, Math.cos(t), Math.sin(t), 0};
    }

    public static double[] rightCircular() { return new double[]{1, 0, 0, 1}; }
    public static double[] leftCircular() { return new double[]{1, 0, 0, -1}; }
    public static double[] unpolarized() { return new double[]{1, 0, 0, 0}; }

    /** Convert SIQUV vector to a given Stokes ordering. */
    public static double[] reorder(double[] siquv, String order) {
        return switch (order) {
            case "SIQUV" -> siquv.clone();
            case "SUVIQ" -> new double[]{siquv[0], siquv[3], siquv[1], siquv[2]};
            default -> throw new IllegalArgumentException("unsupported order " + order);
        };
    }
}
