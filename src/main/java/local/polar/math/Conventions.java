package local.polar.math;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Interpretation conventions. These are part of the derived layer and, being
 * interpretation rather than measurement, MUST enter the rule fingerprint:
 *
 * <ul>
 *   <li>Stokes component order (S0..S3) — an order change changes every
 *       row/column interpretation and therefore the fingerprint;</li>
 *   <li>rotation sign convention for retarder/polarizer angles;</li>
 *   <li>retarder handedness convention;</li>
 *   <li>angle unit used by the imported optical-state encoding;</li>
 *   <li>threshold below which a design matrix is treated as singular /
 *       near-singular (never force an inverse through it).</li>
 * </ul>
 *
 * Raw source bytes never change when conventions change; a new interpretation
 * creates a new branch carrying the new fingerprint.
 */
public record Conventions(
        String stokesOrder,
        String rotationSign,
        String retarderHandedness,
        String angleUnit,
        double singularThreshold,
        double nearSingularThreshold
) {

    public static final String ORDER_IQUV = "IQUV";
    public static final String ORDER_IVQU = "IVQU";
    public static final String SIGN_POSITIVE_RH = "POSITIVE=RIGHT_HANDED";
    public static final String SIGN_NEGATIVE_RH = "NEGATIVE=RIGHT_HANDED";
    public static final String HANDEDNESS_RH = "RETARDER_RH_FAST_AXIS";
    public static final String HANDEDNESS_LH = "RETARDER_LH_FAST_AXIS";

    public Conventions {
        Objects.requireNonNull(stokesOrder);
        Objects.requireNonNull(rotationSign);
        Objects.requireNonNull(retarderHandedness);
        Objects.requireNonNull(angleUnit);
        if (stokesOrder.length() != 4 || !stokesOrder.contains("I")
                || !stokesOrder.contains("Q") || !stokesOrder.contains("U")
                || !stokesOrder.contains("V")) {
            throw new IllegalArgumentException("bad stokes order: " + stokesOrder);
        }
    }

    public static Conventions baseline() {
        return new Conventions(ORDER_IQUV, SIGN_POSITIVE_RH,
                HANDEDNESS_RH, "deg", 1e-9, 1e-6);
    }

    /** Index of the physical component 'I','Q','U','V' in this ordering. */
    public int index(char component) {
        int i = stokesOrder.indexOf(component);
        if (i < 0) throw new IllegalStateException("component missing: " + component);
        return i;
    }

    /** Permutation p so that vector[p] reorders from this order to IQUV. */
    public int[] toIquvPermutation() {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) {
            p[i] = index("IQUV".charAt(i));
        }
        return p;
    }

    public double signMultiplier() {
        return rotationSign.equals(SIGN_POSITIVE_RH) ? 1.0 : -1.0;
    }

    public double handednessMultiplier() {
        return retarderHandedness.equals(HANDEDNESS_RH) ? 1.0 : -1.0;
    }

    public String canonical() {
        return "stokes=" + stokesOrder
                + "|rot=" + rotationSign
                + "|hand=" + retarderHandedness
                + "|angle=" + angleUnit
                + "|singTol=" + singularThreshold
                + "|nearTol=" + nearSingularThreshold;
    }

    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(canonical().getBytes(StandardCharsets.UTF_8));
            return "fp-" + HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
