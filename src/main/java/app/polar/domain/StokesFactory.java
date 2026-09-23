package app.polar.domain;

/**
 * Builds Stokes vectors for ideal linear-polarizer state positions.
 *
 * The linear Stokes encoding is (1, cos(2a), s sin(2a), 0), where s carries
 * the rotation sign convention. Nothing here assumes a particular angle sign:
 * callers pass the convention recorded with the optical state.
 */
public final class StokesFactory {
    public static final String ORDER = "IQUV";
    public static final String CCW_POSITIVE = "CCW_SIGN_POSITIVE_VIEWED_FROM_DETECTOR";
    public static final String CW_POSITIVE = "CW_SIGN_POSITIVE_VIEWED_FROM_DETECTOR";

    private StokesFactory() {
    }

    public static Qty[] linearPolarizer(double angleRad, String rotationConvention) {
        double sign = CW_POSITIVE.equals(rotationConvention) ? -1.0 : 1.0;
        double c = Math.cos(2 * angleRad);
        double q = sign * Math.sin(2 * angleRad);
        return new Qty[]{
                Qty.of(1.0, "1"),
                Qty.of(c, "1"),
                Qty.of(q, "1"),
                Qty.of(0.0, "1")};
    }
}
