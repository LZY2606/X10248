package org.polarworkbench.services;

import org.polarworkbench.math.PolarMath;

public final class PolarBridge {
    private PolarBridge() {
    }

    public static double[] stokes(String name, double angle, String sign, String order) {
        double angleDegrees = switch (name.toUpperCase()) {
            case "H" -> 0;
            case "V" -> 90;
            case "P", "P_NEAR" -> name.equals("P_NEAR") ? 45.00000001 : 45;
            case "M" -> -45;
            case "R" -> 0;
            case "L" -> 0;
            default -> angle;
        };
        return PolarMath.stokesFromRaw(name.toUpperCase().equals("P_NEAR") ? "LINEAR" : name,
                angleDegrees, sign, order, "degree");
    }
}
