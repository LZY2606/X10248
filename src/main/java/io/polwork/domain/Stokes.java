package io.polwork.domain;

import java.util.Arrays;

/**
 * A polarization state in its declared encoding. {@code ordered} holds the four Stokes
 * components exactly in the declared {@code stokesOrder} (e.g. [S0,S2,S3,S1]);
 * {@code canonical} reorders them to [S0,S1,S2,S3] for derived computation.
 */
public record Stokes(String id, double[] ordered, String stokesOrder, String rotationSign,
                     String angleBasis) {

    public static final String CANONICAL_ORDER = "S0,S1,S2,S3";

    public double[] canonical() {
        if (CANONICAL_ORDER.equals(stokesOrder)) {
            return Arrays.copyOf(ordered, ordered.length);
        }
        String[] labels = stokesOrder.split(",");
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            int idx = Arrays.asList(labels).indexOf("S" + i);
            out[i] = ordered[idx];
        }
        return out;
    }

    /** Angle-encoded state with the declared rotation convention applied (derived layer). */
    public static Stokes fromLinearPolarizer(String id, double angleRad, String rotationSign,
                                             String stokesOrder) {
        double c = Math.cos(2 * angleRad);
        double s = Math.sin(2 * angleRad);
        double sign = "-1".equals(rotationSign) ? -1.0 : 1.0;
        double[] canonical = {1.0, c, sign * s, 0.0};
        return fromCanonical(id, canonical, stokesOrder, rotationSign, "lab-optical-axis");
    }

    public static Stokes fromCanonical(String id, double[] canonical, String stokesOrder,
                                       String rotationSign, String angleBasis) {
        double[] ordered;
        if (CANONICAL_ORDER.equals(stokesOrder)) {
            ordered = Arrays.copyOf(canonical, 4);
        } else {
            String[] labels = stokesOrder.split(",");
            ordered = new double[4];
            for (int i = 0; i < 4; i++) {
                int idx = Arrays.asList(labels).indexOf("S" + i);
                ordered[idx] = canonical[i];
            }
        }
        return new Stokes(id, ordered, stokesOrder, rotationSign, angleBasis);
    }
}
