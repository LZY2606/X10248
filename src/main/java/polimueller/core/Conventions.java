package polimueller.core;

/**
 * Polarization conventions. Both axes enter the derivation fingerprint:
 * changing either one must change every downstream fingerprint.
 */
public final class Conventions {
    private Conventions() {}

    /** Order of Stokes components in encoded state vectors. */
    public enum StokesOrder {
        I_Q_U_V,   // canonical (I,Q,U,V)
        I_Q_V_U,   // swapped U/V (handedness-first encodings)
        I_V_U_Q    // archival order used by some lab software
    }

    /** Sign convention for rotation angles of optical elements. */
    public enum RotationConvention {
        CCW_POSITIVE,  // counter-clockwise looking into the beam
        CW_POSITIVE    // clockwise looking into the beam
    }

    public record ConventionSet(StokesOrder stokesOrder, RotationConvention rotation) {
        public static ConventionSet canonical() {
            return new ConventionSet(StokesOrder.I_Q_U_V, RotationConvention.CCW_POSITIVE);
        }
        public String key() { return stokesOrder + "|" + rotation; }
    }

    /**
     * Re-express a Stokes vector encoded in {@code from} into the canonical
     * (I_Q_U_V, CCW_POSITIVE) frame. Raw values are never rewritten by the
     * store; this normalization happens only in the derived layer.
     */
    public static double[] toCanonical(double[] s, ConventionSet from) {
        double i, q, u, v;
        switch (from.stokesOrder()) {
            case I_Q_U_V -> { i = s[0]; q = s[1]; u = s[2]; v = s[3]; }
            case I_Q_V_U -> { i = s[0]; q = s[1]; u = s[3]; v = s[2]; }
            case I_V_U_Q -> { i = s[0]; q = s[3]; u = s[2]; v = s[1]; }
            default -> throw new IllegalStateException();
        }
        if (from.rotation() == RotationConvention.CW_POSITIVE) {
            // flipping rotation sign flips the handed components U and V
            u = -u;
            v = -v;
        }
        return new double[]{i, q, u, v};
    }
}
