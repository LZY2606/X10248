package poloptics.domain;

import java.util.Locale;

/**
 * Rotation sign convention and Stokes ordering. Both enter the analysis
 * fingerprint so results computed under different conventions never compare
 * as equal even when the numeric matrix happens to coincide.
 */
public final class Conventions {
    public final String stokesOrder; // e.g. "[S0,S1,S2,S3]"
    public final String rotationSign; // "CCW_POSITIVE" or "CW_POSITIVE"
    public final String angleBasis;   // coordinate basis label

    public Conventions(String stokesOrder, String rotationSign, String angleBasis) {
        this.stokesOrder = stokesOrder;
        this.rotationSign = rotationSign;
        this.angleBasis = angleBasis;
    }

    public static Conventions standard() {
        return new Conventions("[S0,S1,S2,S3]", "CCW_POSITIVE", "laboratory_x_lab_y_look_into_beam");
    }

    public String fingerprintFragment() {
        return "stokes=" + stokesOrder + ";rot=" + rotationSign + ";basis=" + angleBasis;
    }

    @Override public String toString() {
        return fingerprintFragment();
    }
}
