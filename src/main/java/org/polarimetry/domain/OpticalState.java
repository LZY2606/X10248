package org.polarimetry.domain;

import java.util.List;

/**
 * One generator/analyzer state: the original encoding is preserved verbatim
 * ({@code rawEncoding}) together with the unit/angle basis it was declared in.
 * The canonical Stokes vector is a derived layer.
 */
public record OpticalState(
        String code,
        String kind,           // "linear-polarizer" | "qwp" | "hwp" | "general"
        String angleText,      // original representation, e.g. "32.5 deg"
        Double angleRad,       // parsed angle in rad; null if not convertible
        String angleBasis,     // "rad" | "deg" | "unparseable"
        double retardanceRad,  // 0 for polarizer, pi/2 qwp, pi hwp
        double[] declaredStokes,
        double[] canonicalStokes,
        List<String> diagnostics) {

    public boolean convertible() { return angleRad != null; }
}
