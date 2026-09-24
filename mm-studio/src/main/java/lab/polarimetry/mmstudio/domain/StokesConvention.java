package lab.polarimetry.mmstudio.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Encodes how optical-state parameter maps onto a Stokes vector.
 * Both the Stokes component order and the rotation sign enter the rule
 * fingerprint, so changing either creates a new rule version with its own
 * derived artifacts.
 */
public record StokesConvention(List<String> stokesOrder, String rotationSign,
                               String rotationAxis, String angleUnit) {
    public static final List<String> CANONICAL = List.of("I", "Q", "U", "V");

    public static StokesConvention iquvRightHandedDeg() {
        return new StokesConvention(CANONICAL, "positive_is_counterclockwise_beam_view",
                "optical_axis_along_propagation", "deg");
    }

    public static StokesConvention fromMap(Map<String, Object> m) {
        @SuppressWarnings("unchecked")
        List<String> order = m.get("stokesOrder") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList()
                : CANONICAL;
        String sign = String.valueOf(m.getOrDefault("rotationSign",
                "positive_is_counterclockwise_beam_view"));
        String axis = String.valueOf(m.getOrDefault("rotationAxis", "optical_axis_along_propagation"));
        String unit = String.valueOf(m.getOrDefault("angleUnit", "deg"));
        return new StokesConvention(order, sign, axis, unit);
    }

    /** Permute canonical I,Q,U,V components into the configured storage order. */
    public double[] toStorageOrder(double[] canonicalIquv) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            int idx = CANONICAL.indexOf(stokesOrder.get(i));
            if (idx < 0) throw new IllegalArgumentException("unknown Stokes component: " + stokesOrder.get(i));
            out[i] = canonicalIquv[idx];
        }
        return out;
    }

    public double[] toCanonical(double[] storage) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) out[CANONICAL.indexOf(stokesOrder.get(i))] = storage[i];
        return out;
    }

    /**
     * Ideal linear polarizer at angle theta:
     * S = 1/2 [1, cos(2 theta'), sin(2 theta'), 0], theta' signed per convention.
     */
    public double[] linearPolarizer(double angleDeg) {
        double signed = rotationSign.contains("clockwise") ? -angleDeg : angleDeg;
        double t = Math.toRadians(signed);
        double[] canon = {1, Math.cos(2 * t), Math.sin(2 * t), 0};
        double[] s = toStorageOrder(canon);
        s[0] *= 0.5;
        for (int i = 1; i < 4; i++) s[i] *= 0.5;
        return s;
    }

    /**
     * Standard generator/analyzer state Stokes vectors keyed by state id.
     * Coordinates follow the configured storage order. Angles obey the rotation sign.
     */
    public double[] standardState(String stateId) {
        double theta;
        switch (stateId) {
            case "H", "P0" -> { return scaled(linearPolarizer(0)); }
            case "V", "P90" -> { return scaled(linearPolarizer(90)); }
            case "P", "P45" -> { return scaled(linearPolarizer(45)); }
            case "M", "P135" -> { return scaled(linearPolarizer(135)); }
            case "RCP", "R" -> {
                double[] canon = {0.5, 0, 0, 0.5 * (rotationSign.contains("clockwise") ? -1 : 1)};
                return toStorageOrder(canon);
            }
            case "LCP", "L" -> {
                double[] canon = {0.5, 0, 0, -0.5 * (rotationSign.contains("clockwise") ? -1 : 1)};
                return toStorageOrder(canon);
            }
            default -> {
                if (stateId.startsWith("THETA=")) {
                    theta = Double.parseDouble(stateId.substring(6));
                    return linearPolarizer(theta);
                }
                throw new IllegalArgumentException("unknown standard optical state: " + stateId);
            }
        }
    }

    private static double[] scaled(double[] v) { return v; }

    public String fingerprint() {
        return "order=" + String.join(">", stokesOrder)
                + "|rotsign=" + rotationSign
                + "|axis=" + rotationAxis
                + "|angleUnit=" + angleUnit;
    }

    public Map<String, Object> toMap() {
        return Map.of("stokesOrder", stokesOrder, "rotationSign", rotationSign,
                "rotationAxis", rotationAxis, "angleUnit", angleUnit);
    }

    @Override public String toString() { return fingerprint(); }
}
