package com.polarlab.optics;

import com.polarlab.math.Mat;

import java.util.Map;

/**
 * One generator (PSG) or analyzer (PSA) state. The optical encoding (kind, physical angle,
 * retardance) is kept verbatim from the import; the Stokes/analyzer vector is a derived
 * quantity under the supplied {@link BasisConvention}.
 */
public record OpticalState(String id, String kind, double angleRad, double retardanceRad,
                           String element, Map<String, String> rawEncoding) {

    /** 4-vector: generator Stokes or analyzer row, in the convention's basis. */
    public double[] vector(BasisConvention convention) {
        double theta = convention.signedAngle(angleRad);
        double[] canonical;
        if (kind.equals("psg")) {
            Mat lp = retardanceRad != 0
                    ? Mueller.linearRetarder(theta, retardanceRad).mul(new double[]{1, 0, 0, 0})
                    : Mueller.linearPolarizer(theta).mul(new double[]{1, 0, 0, 0});
            canonical = lp;
        } else {
            // analyzer first row (projection) = half [1, cos2θ, sin2θ, 0]
            Mat lp = Mueller.linearPolarizer(theta);
            canonical = new double[]{lp.a[0][0], lp.a[0][1], lp.a[0][2], lp.a[0][3]};
        }
        return convention.fromCanonical(canonical);
    }
}
