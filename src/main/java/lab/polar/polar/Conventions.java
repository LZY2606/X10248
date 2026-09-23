package lab.polar.polar;

import java.util.List;
import java.util.Map;

/** Coordinate/order conventions that participate in result fingerprints. */
public final class Conventions {
    private Conventions() {}

    /** Stokes component ordering, e.g. [S0,S1,S2,S3] or [S0,S2,S1,S3]. */
    public static final class StokesOrder {
        public final String code;
        public final List<String> components;

        public StokesOrder(String code, List<String> components) {
            if (components.size() != 4 || !components.containsAll(List.of("S0", "S1", "S2", "S3"))) {
                throw new IllegalArgumentException("Stokes order must permute S0..S3: " + components);
            }
            this.code = code;
            this.components = List.copyOf(components);
        }

        /** Position of component S{k} in this ordering. */
        public int indexOf(String component) {
            return components.indexOf(component);
        }

        /** Convert a vector expressed in this order to canonical [S0,S1,S2,S3]. */
        public double[] toCanonical(double[] v) {
            double[] out = new double[4];
            for (int i = 0; i < 4; i++) out[components.indexOf("S" + i)] = v[i];
            return out;
        }

        /** Convert canonical [S0,S1,S2,S3] into this order. */
        public double[] fromCanonical(double[] v) {
            double[] out = new double[4];
            for (int i = 0; i < 4; i++) out[i] = v[components.indexOf("S" + i)];
            return out;
        }

        public Map<String, Object> descriptor() {
            return Map.of("code", code, "components", components);
        }

        public static StokesOrder canonical() {
            return new StokesOrder("STOKES_0123", List.of("S0", "S1", "S2", "S3"));
        }

        public static StokesOrder swapped12() {
            return new StokesOrder("STOKES_0213", List.of("S0", "S2", "S1", "S3"));
        }
    }

    /**
     * Rotation sign convention. Positive angle is counter-clockwise when looking INTO the beam
     * (towards the source) for sign = +1. sign = -1 mirrors every resolved angle.
     */
    public static final class RotationConvention {
        public static final String FRAME = "TRANSVERSE_PLANE_LOOKING_INTO_BEAM";
        public final String code;
        public final int sign;

        public RotationConvention(String code, int sign) {
            if (sign != 1 && sign != -1) throw new IllegalArgumentException("sign must be +/-1");
            this.code = code;
            this.sign = sign;
        }

        public double resolveAngle(double theta) {
            return sign * theta;
        }

        public Map<String, Object> descriptor() {
            return Map.of("code", code, "sign", sign, "frame", FRAME, "zero", "OPTIC_FAST_AXIS_ALIGNED_X");
        }

        public static RotationConvention ccwIntoBeam() {
            return new RotationConvention("CCW_LOOKING_INTO_BEAM", +1);
        }

        public static RotationConvention cwIntoBeam() {
            return new RotationConvention("CW_LOOKING_INTO_BEAM", -1);
        }
    }
}
