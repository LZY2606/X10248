package polar.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Polarimetric conventions that change the meaning of numbers without
 * changing the numbers themselves. They are part of every derivation
 * fingerprint so a convention change invalidates derived results instead of
 * silently reinterpreting them.
 */
public final class Conventions {

    /** Order of the Stokes vector components. */
    public enum StokesOrder { IQUV, IVUQ }

    /** Positive rotation sense when looking into the beam. */
    public enum RotationSign { CCW_POSITIVE, CW_POSITIVE }

    public final StokesOrder stokesOrder;
    public final RotationSign rotationSign;

    public Conventions(StokesOrder stokesOrder, RotationSign rotationSign) {
        this.stokesOrder = stokesOrder;
        this.rotationSign = rotationSign;
    }

    public static Conventions standard() {
        return new Conventions(StokesOrder.IQUV, RotationSign.CCW_POSITIVE);
    }

    /**
     * Reorders a Stokes 4-vector given in {@code from} order into {@code to}
     * order. I stays first in all supported orders; Q/U/V are permuted.
     */
    public static double[] reorder(double[] s, StokesOrder from, StokesOrder to) {
        if (from == to) return s.clone();
        // IQUV -> IVUQ swaps U and V.
        return new double[]{s[0], s[1], s[3], s[2]};
    }

    /** Rotation matrix R(theta) for a Mueller calculus, honouring the sign convention. */
    public static double[][] rotation(double thetaDeg, RotationSign sign) {
        double t = Math.toRadians(sign == RotationSign.CCW_POSITIVE ? thetaDeg : -thetaDeg);
        double c = Math.cos(2 * t), s = Math.sin(2 * t);
        return new double[][]{
                {1, 0, 0, 0},
                {0, c, s, 0},
                {0, -s, c, 0},
                {0, 0, 0, 1}};
    }

    public String descriptor() {
        return "stokes=" + stokesOrder + ";rotation=" + rotationSign;
    }

    public static String fingerprintOf(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
