package pol.mat;

/**
 * Stokes encodings and rotation conventions.
 *
 * <p>Stokes order is an explicit, fingerprinted convention:
 * {@code SIHV} = (S0, S1, S2, S3) and {@code SIVH} = (S0, S3, S1, S2).
 * Rotation sign {@code +} uses the optics convention (positive rotation about
 * propagation), {@code -} flips the S2/S3 generator sign. Both versions are kept
 * as distinct interpretation branches; they are never silently rewritten.
 */
public final class OpticalStates {
    private OpticalStates() {}

    public static double[] normalize(double[] s) {
        double n = Math.sqrt(s[0] == 0 ? 0 : s[1] * s[1] + s[2] * s[2] + s[3] * s[3]) / Math.max(1e-12, s[0]);
        double[] out = s.clone();
        if (n > 1 + 1e-9) {
            for (int k = 1; k < 4; k++) {
                out[k] = s[k] / n;
            }
        }
        return out;
    }

    /** Linear polariser Stokes vector at angle theta(rad); rotationSign = +1 / -1. */
    public static double[] linearPolarizer(double theta, int rotationSign, String stokesOrder) {
        double c2 = Math.cos(2 * theta);
        double s2 = rotationSign * Math.sin(2 * theta);
        double[] s;
        if ("SIVH".equals(stokesOrder)) {
            s = new double[] {1, 0, c2, s2};
        } else {
            s = new double[] {1, c2, s2, 0};
        }
        return s;
    }

    /** Quarter-wave plate fast axis at theta(rad). */
    public static double[] quarterWavePlate(double theta, int rotationSign, String stokesOrder) {
        double c4 = Math.cos(4 * theta);
        double s4 = rotationSign * Math.sin(4 * theta);
        double[] s;
        if ("SIVH".equals(stokesOrder)) {
            s = new double[] {1, s4, c4, 0};
        } else {
            s = new double[] {1, c4, 0, s4};
        }
        return s;
    }
}
