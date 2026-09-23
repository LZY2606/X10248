package io.polroom;

/**
 * The two interpretation choices that enter every polarimetric fingerprint:
 *  - stokes order (IQUV canonical, or the imported raw order)
 *  - sign of the optical rotation convention used to build states from angular recipes.
 */
public record Conventions(String stokesOrder, int rotationSign) {
    public static final String IQUV = "IQUV";
    public static final String IQVU = "IQVU";

    public static Conventions canonical() {
        return new Conventions(IQUV, +1);
    }

    /** Reorders a raw Stokes tuple into canonical IQUV according to stokesOrder. */
    public double[] toCanonical(double[] raw) {
        if (raw.length != 4) throw new IllegalArgumentException("Stokes vector must have 4 components");
        if (IQUV.equals(stokesOrder)) return raw.clone();
        if (IQVU.equals(stokesOrder)) {
            return new double[] {raw[0], raw[1], raw[3], raw[2]};
        }
        throw new IllegalStateException("Unknown Stokes order: " + stokesOrder);
    }

    public String fingerprint() {
        return "stokes=" + stokesOrder + ";rotsign=" + (rotationSign >= 0 ? "+" : "-");
    }
}
