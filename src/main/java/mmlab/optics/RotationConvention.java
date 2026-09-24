package mmlab.optics;

/**
 * Signed rotation convention for polarization elements. The sign distinguishes
 * counter-clockwise versus clockwise fast-axis rotation and must enter the
 * derivation fingerprint.
 */
public final class RotationConvention {
    /** Positive angle = counter-clockwise when looking into the beam. */
    public static final RotationConvention POSITIVE_CCW =
            new RotationConvention("POSITIVE_CCW", +1.0, "lab-frame, looking into beam");
    /** Positive angle = clockwise when looking into the beam. */
    public static final RotationConvention POSITIVE_CW =
            new RotationConvention("POSITIVE_CW", -1.0, "lab-frame, looking into beam");

    public final String name;
    public final double sign;
    public final String reference;

    public RotationConvention(String name, double sign, String reference) {
        this.name = name;
        this.sign = sign;
        this.reference = reference;
    }

    public double apply(double angleRad) {
        return sign * angleRad;
    }

    public static RotationConvention byName(String name) {
        return switch (name) {
            case "POSITIVE_CCW" -> POSITIVE_CCW;
            case "POSITIVE_CW" -> POSITIVE_CW;
            default -> throw new IllegalArgumentException("unknown rotation convention: " + name);
        };
    }

    public RotationConvention flipped() {
        return new RotationConvention(name + "_FLIPPED", -sign, reference);
    }
}
