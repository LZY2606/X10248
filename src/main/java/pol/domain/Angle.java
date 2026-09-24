package pol.domain;

/**
 * Angle conversion that preserves the original representation. An
 * unconvertible token ({@code value == null}) stays at its source location
 * with a diagnostic instead of being dropped or coerced to zero.
 */
public record Angle(String token, Double radians, String unit, String diagnostic) {

    public static Angle parse(String token, String declaredUnit) {
        if (token == null || token.isBlank()) {
            return new Angle(token, null, declaredUnit, "missing angle token");
        }
        String t = token.trim();
        String unit = declaredUnit;
        if (t.endsWith("deg") || t.endsWith("\u00b0")) {
            unit = "deg";
            t = t.replace("deg", "").replace("\u00b0", "").trim();
        } else if (t.endsWith("rad")) {
            unit = "rad";
            t = t.replace("rad", "").trim();
        }
        try {
            double value = Double.parseDouble(t);
            double rad = "rad".equalsIgnoreCase(unit) ? value : Math.toRadians(value);
            return new Angle(token, rad, unit, null);
        } catch (NumberFormatException e) {
            return new Angle(token, null, unit, "unconvertible angle token: " + token);
        }
    }
}
