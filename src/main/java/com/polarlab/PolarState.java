package com.polarlab;

import java.util.Locale;

/**
 * A polarization state in Stokes form.
 *
 * <p>Two conventions are FIRST-CLASS and never silently mixed:
 * <ul>
 *   <li>{@link Order#S0123}: S=(S0,S1,S2,S3), S3 = right-circular minus left-circular (common physics);</li>
 *   <li>{@link Order#S0S2S1S3}: the permuted ordering some instruments export.</li>
 * </ul>
 * Rotation sign convention (positive angle = clockwise or counterclockwise when looking into the
 * beam) is part of every encoded state and enters the fingerprint.
 */
public final class PolarState {

    public enum Order { S0123, S0S2S1S3 }

    /** Rotation sign convention: POS_CCW = +theta rotates counterclockwise looking INTO the beam. */
    public enum RotSign { POS_CCW, POS_CW }

    public final double[] s;            // always stored canonically in the declared order
    public final Order order;
    public final RotSign rotSign;
    public final String stateId;

    public PolarState(double[] s, Order order, RotSign rotSign, String stateId) {
        if (s.length != 4) {
            throw new IllegalArgumentException("Stokes vector must have 4 components");
        }
        this.s = s.clone();
        this.order = order;
        this.rotSign = rotSign;
        this.stateId = stateId;
    }

    /** Vector in canonical S0123 ordering, regardless of stored order. */
    public double[] canonical() {
        if (order == Order.S0123) {
            return s.clone();
        }
        return new double[] { s[0], s[2], s[1], s[3] };
    }

    /** Linear polarizer at physical angle theta (radians), built under declared conventions. */
    public static PolarState linear(double thetaDeg, Order order, RotSign sign, String id) {
        double signed = sign == RotSign.POS_CCW ? Math.toRadians(thetaDeg) : -Math.toRadians(thetaDeg);
        double c2 = Math.cos(2 * signed);
        double si2 = Math.sin(2 * signed);
        double[] can = { 1.0, c2, si2, 0.0 };
        return fromCanonical(can, order, sign, id);
    }

    public static PolarState quarterWave(double fastAxisDeg, Order order, RotSign sign, String id) {
        double signed = sign == RotSign.POS_CCW ? Math.toRadians(fastAxisDeg) : -Math.toRadians(fastAxisDeg);
        double c = Math.cos(signed);
        double si = Math.sin(signed);
        // unit-incident intensity Stokes after a QWP (fast axis at signed angle), S3 convention fixed
        double[] can = {
                1.0,
                c * c - si * si,
                2 * c * si,
                sign == RotSign.POS_CCW ? 1.0 : -1.0
        };
        return fromCanonical(can, order, sign, id);
    }

    static PolarState fromCanonical(double[] can, Order order, RotSign sign, String id) {
        if (order == Order.S0123) {
            return new PolarState(can, order, sign, id);
        }
        return new PolarState(new double[] { can[0], can[2], can[1], can[3] }, order, sign, id);
    }

    public String conventionTag() {
        return String.format(Locale.ROOT, "stokes=%s;rotsign=%s", order.name(), rotSign.name());
    }
}
