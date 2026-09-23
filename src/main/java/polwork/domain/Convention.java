package polwork.domain;

import java.util.List;
import java.util.Locale;

/**
 * Rotation sign and Stokes-component ordering. Conventions are part of the
 * interpretation fingerprint: the same angles under a different convention
 * produce different optical states and must not silently replace prior results.
 */
public record Convention(String stokesOrder, String rotationSign) {

    public static final Convention DEFAULT = new Convention("IQUV", "ccw+");

    public Convention {
        if (stokesOrder == null || !stokesOrder.toUpperCase(Locale.ROOT).equals("IQUV")
                || stokesOrder.length() != 4
                || stokesOrder.chars().distinct().count() != 4
                || !stokesOrder.contains("I") || !stokesOrder.contains("Q")
                || !stokesOrder.contains("U") || !stokesOrder.contains("V")) {
            throw new IllegalArgumentException("stokesOrder must be a permutation of I,Q,U,V: " + stokesOrder);
        }
        if (!List.of("ccw+", "cw+").contains(rotationSign)) {
            throw new IllegalArgumentException("rotationSign must be ccw+ or cw+: " + rotationSign);
        }
        stokesOrder = stokesOrder.toUpperCase(Locale.ROOT);
    }

    /** Index (in I,Q,U,V storage) of the component displayed at position p. */
    public int storageIndexOfPosition(int position) {
        char c = stokesOrder.charAt(position);
        return switch (c) {
            case 'I' -> 0;
            case 'Q' -> 1;
            case 'U' -> 2;
            case 'V' -> 3;
            default -> throw new IllegalStateException();
        };
    }

    /** Reorder a vector stored in I,Q,U,V into this convention's order. */
    public double[] toConventionOrder(double[] iquv) {
        double[] out = new double[4];
        for (int p = 0; p < 4; p++) {
            out[p] = iquv[storageIndexOfPosition(p)];
        }
        return out;
    }

    /** +1 when counter-clockwise angles are positive, -1 otherwise. */
    public int angleSign() {
        return "ccw+".equals(rotationSign) ? 1 : -1;
    }

    public String fingerprintToken() {
        return "order=" + stokesOrder + ";rot=" + rotationSign;
    }
}
