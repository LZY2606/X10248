package org.polarreview.optics;

import org.polarreview.model.Ids;

import java.util.Arrays;
import java.util.Locale;

/**
 * Stokes ordering and rotation-handedness conventions. These conventions are
 * first-class: they enter the fingerprint of every estimate and decomposition,
 * and a change (S1,S2,S3,S0 reordering or flipped rotation sign) creates a new
 * interpretation instead of silently replacing old conclusions.
 */
public final class Conventions {
    public static final String DEFAULT_ORDER = "S0,S1,S2,S3";
    public static final String ROT_RHP = "right-handed-positive";
    public static final String ROT_LHP = "left-handed-positive";

    private Conventions() {}

    /** Reorder a Stokes vector expressed in {@code order} into canonical S0,S1,S2,S3. */
    public static double[] reorder(double[] s, String order) {
        String[] names = order.split(",");
        if (names.length != 4) {
            throw new IllegalArgumentException("stokes order must list 4 components: " + order);
        }
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            int idx = switch (names[i].trim().toUpperCase(Locale.ROOT)) {
                case "S0" -> 0;
                case "S1" -> 1;
                case "S2" -> 2;
                case "S3" -> 3;
                default -> throw new IllegalArgumentException("unknown stokes component: " + names[i]);
            };
            out[idx] = s[i];
        }
        return out;
    }

    /** Flip handedness by reversing the circular component S3. */
    public static double[] applyRotationSign(double[] canonical, String convention) {
        double[] out = canonical.clone();
        if (ROT_LHP.equals(convention)) out[3] = -out[3];
        return out;
    }

    /** Stable convention string used in fingerprints. */
    public static String conventionKey(String stokesOrder, String rotationConvention) {
        return stokesOrder + "|" + rotationConvention;
    }

    public static String fingerprint(String ruleVersion, String... parts) {
        String joined = ruleVersion + "||" + String.join("|", Arrays.asList(parts));
        return "fp_" + Ids.sha256(joined).substring(0, 20);
    }
}
