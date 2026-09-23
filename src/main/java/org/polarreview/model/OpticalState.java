package org.polarreview.model;

import java.util.Arrays;

/**
 * One optical state of a polarization state generator (PSG) or analyzer (PSA).
 *
 * <p>{@link #encoder} keeps the original hardware description (e.g.
 * "QWP theta=45 deg"), while {@link #stokes} is the state's normalized Stokes
 * vector expressed in the declared {@link #stokesOrder} and rotation
 * convention. Ordering and convention never silently change: they are part of
 * the fingerprint of every derived result.
 */
public final class OpticalState {
    public final String code;
    public final String device;            // PSG | PSA
    public final String encoder;           // original representation
    public final Q[] components;           // original component settings, with units/basis
    public final double[] stokes;          // normalized Stokes vector (S0..S3), null if undetermined
    public final String stokesOrder;       // e.g. "S0,S1,S2,S3"
    public final String rotationConvention; // "right-handed-positive" | ...
    public final String diagnostic;

    public OpticalState(String code, String device, String encoder, Q[] components,
                        double[] stokes, String stokesOrder, String rotationConvention,
                        String diagnostic) {
        this.code = code;
        this.device = device;
        this.encoder = encoder;
        this.components = components;
        this.stokes = stokes;
        this.stokesOrder = stokesOrder;
        this.rotationConvention = rotationConvention;
        this.diagnostic = diagnostic;
    }

    public String fingerprintMaterial() {
        return code + "|" + device + "|" + encoder + "|"
                + (components == null ? "" : Arrays.toString(components))
                + "|" + stokesOrder + "|" + rotationConvention;
    }
}
