package polwork.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Unit/coordinate handling. Raw imports keep their original representation
 * verbatim; only the derived layer normalizes to SI. Values that cannot be
 * converted stay at their original location with a diagnostic instead of
 * being dropped or guessed.
 */
public final class Units {

    private Units() {
    }

    public record Conversion(boolean converted, double siValue, String siUnit, String diagnostic) {
    }

    private static final Map<String, double[]> SI_FACTORS = new LinkedHashMap<>();

    static {
        // length -> metre
        SI_FACTORS.put("m", new double[] {1e0, 1, 0});
        SI_FACTORS.put("mm", new double[] {1e-3, 1, 0});
        SI_FACTORS.put("um", new double[] {1e-6, 1, 0});
        SI_FACTORS.put("nm", new double[] {1e-9, 1, 0});
        SI_FACTORS.put("angstrom", new double[] {1e-10, 1, 0});
        SI_FACTORS.put("a.u.", new double[] {Double.NaN, 1, 0}); // dimensionless intensity
        SI_FACTORS.put("count", new double[] {Double.NaN, 1, 0});
        SI_FACTORS.put("deg", new double[] {Math.PI / 180, 1, 0});
        SI_FACTORS.put("rad", new double[] {1.0, 1, 0});
        SI_FACTORS.put("s", new double[] {1.0, 1, 0});
        SI_FACTORS.put("ms", new double[] {1e-3, 1, 0});
    }

    /**
     * @param expectedKind "length","angle","intensity","time" or "any"
     */
    public static Conversion normalize(double value, String unit, String expectedKind) {
        if (unit == null) {
            return new Conversion(false, value, null, "missing unit; value left at original location");
        }
        String key = unit.trim().toLowerCase(Locale.ROOT).replace("µm", "um").replace("å", "a");
        double[] rule = SI_FACTORS.get(key);
        if (rule == null) {
            return new Conversion(false, value, null,
                    "unknown unit '" + unit + "'; no conversion attempted, value preserved");
        }
        String kind = switch (key) {
            case "m", "mm", "um", "nm", "angstrom" -> "length";
            case "deg", "rad" -> "angle";
            case "count", "a.u." -> "intensity";
            case "s", "ms" -> "time";
            default -> "any";
        };
        if (!"any".equals(expectedKind) && !"any".equals(kind) && !kind.equals(expectedKind)) {
            return new Conversion(false, value, null,
                    "unit '" + unit + "' is " + kind + ", expected " + expectedKind + "; value preserved");
        }
        if (Double.isNaN(rule[0])) {
            return new Conversion(true, value, "a.u.", null);
        }
        String si = kind.equals("length") ? "m" : kind.equals("angle") ? "rad" : kind.equals("time") ? "s" : "a.u.";
        return new Conversion(true, value * rule[0], si, null);
    }
}
